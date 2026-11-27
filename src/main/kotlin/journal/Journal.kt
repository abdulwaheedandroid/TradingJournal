package journal

import androidx.room.Room
import androidx.room.Transactor
import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_URI
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Clock
import java.time.Instant

internal data class JournalSnapshot(
    val settings: Settings?,
    val trades: List<Trade>,
    val equity: List<EquityPoint>
)

class Journal(private val path: Path, private val clock: Clock = Clock.systemUTC()) : AutoCloseable {
    private val database: JournalDatabase

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        val bundledDriver = BundledSQLiteDriver()
        val driver = object : SQLiteDriver {
            override fun open(fileName: String) = bundledDriver.open(
                fileName, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_URI
            )
        }
        database = Room.databaseBuilder<JournalDatabase>(path.toAbsolutePath().toString())
            .setDriver(driver)
            .addMigrations(migrateJournalToRoom)
            .build()
        try {
            // Open eagerly so startup can report an incompatible database or a failed migration.
            runBlocking { database.useReaderConnection { it.version() } }
        } catch (failure: Exception) {
            database.close()
            throw failure
        }
    }

    override fun close() = database.close()

    private fun <T> read(action: suspend (Transactor) -> T): T = runBlocking {
        database.useReaderConnection { db -> db.deferredTransaction { action(db) } }
    }

    private fun <T> write(action: suspend (Transactor) -> T): T = runBlocking {
        database.useWriterConnection { db -> db.immediateTransaction { action(db) } }
    }

    fun settings(): Settings? = runBlocking { database.journalDao().settings()?.let(::decodeSettings) }

    fun saveSettings(settings: Settings) = runBlocking {
        database.journalDao().saveSettings(settings.encode())
    }

    fun savePlan(
        direction: Direction, entry: BigDecimal, stop: BigDecimal, target: BigDecimal?, notes: String
    ): Long = write { db ->
        val settings = requireNotNull(settings(db)) { "Save your account and contract settings first." }
        val plan = calculate(settings, direction, entry, stop, target)
        db.exec("""
            INSERT INTO trades(created,status,direction,entry,stop,target,minimum_target,quantity,budget,
                risk,reward,margin,entry_fee,stop_fee,target_fee,assumptions,notes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(), clock.instant(), Status.Planned, direction, entry, stop, plan.target,
            plan.minimumTarget, plan.quantity, plan.budget, plan.risk, plan.reward, plan.margin,
            plan.entryFee, plan.stopFee, plan.targetFee, settings.encode(), notes)
        db.query("SELECT last_insert_rowid()") { getLong(0) }.single()
    }

    fun trades(): List<Trade> = read { trades(it) }

    internal fun snapshot(): JournalSnapshot = read { db ->
        val currentSettings = settings(db)
        JournalSnapshot(currentSettings, trades(db), equity(db, currentSettings))
    }

    private suspend fun trades(db: Transactor, schema: String = "main", id: Long? = null): List<Trade> {
        val predicate = if (id == null) "" else " WHERE id=?"
        val args = listOfNotNull(id).toTypedArray()
        val fillPredicate = if (id == null) "" else " WHERE trade_id=?"
        val fills = db.query("SELECT * FROM $schema.fills$fillPredicate ORDER BY id", *args) {
            getLong("trade_id") to Fill(Instant.parse(getString("time")), FillKind.valueOf(getString("kind")),
                amount("price"), amount("quantity"), amount("fee"))
        }.groupBy({ it.first }, { it.second })
        return db.query("SELECT * FROM $schema.trades$predicate ORDER BY id DESC", *args) {
            val tradeId = getLong("id")
            Trade(tradeId, Instant.parse(getString("created")), Status.valueOf(getString("status")),
                Plan(Direction.valueOf(getString("direction")), amount("entry"), amount("stop"), amount("target"),
                    amount("minimum_target"), amount("quantity"), amount("budget"), amount("risk"),
                    amount("reward"), amount("margin"), amount("entry_fee"), amount("stop_fee"),
                    amount("target_fee"), decodeSettings(getString("assumptions"))),
                notes = getString("notes"),
                fills = fills[tradeId].orEmpty(),
                closed = nullableText("closed")?.let(Instant::parse),
                pnl = nullableText("pnl")?.toBigDecimal(),
                funding = nullableText("funding")?.toBigDecimal())
        }
    }

    private suspend fun trade(db: Transactor, id: Long): Trade =
        requireNotNull(trades(db, id = id).singleOrNull()) { "Trade #$id does not exist." }

    fun saveNotes(id: Long, notes: String) = runBlocking {
        require(database.journalDao().saveNotes(id, notes) == 1) { "Trade no longer exists." }
    }

    fun cancel(id: Long) = runBlocking {
        require(database.journalDao().cancel(id) == 1) {
            "Only planned trades can be cancelled."
        }
    }

    fun addFill(id: Long, fill: Fill) = write { db ->
        val trade = trade(db, id)
        require(trade.status == Status.Planned || trade.status == Status.Open) { "This trade is no longer active." }
        require(fill.kind == FillKind.Entry || fill.quantity <= trade.open) {
            "Exit quantity exceeds the recorded open quantity of ${trade.open.display()} BTC."
        }
        require(trade.fills.lastOrNull()?.time?.let { fill.time >= it } != false) {
            "Enter fills in execution order. This timestamp precedes the last recorded fill."
        }
        db.exec("INSERT INTO fills(trade_id,time,kind,price,quantity,fee) VALUES (?,?,?,?,?,?)",
            id, fill.time, fill.kind, fill.price, fill.quantity, fill.fee)
        db.exec("UPDATE trades SET status=? WHERE id=?", Status.Open, id)
        Unit
    }

    fun closeTrade(id: Long, funding: BigDecimal): BigDecimal = write { db ->
        val trade = trade(db, id)
        require(trade.status == Status.Open) { "Only open trades can be closed." }
        val pnl = trade.netPnl(funding)
        val lastExit = trade.fills.last { it.kind == FillKind.Exit }.time
        db.exec("UPDATE trades SET status=?, closed=?, pnl=?, funding=? WHERE id=?",
            Status.Closed, lastExit, pnl, funding, id)
        pnl
    }

    fun transfer(amount: BigDecimal, time: Instant = clock.instant()) = write {
        require(amount.signum() != 0) { "Enter a nonzero transfer amount." }
        require(settings(it) != null) { "Save the opening balance in Settings first." }
        it.exec("INSERT INTO transfers(time,amount) VALUES (?,?)", time, amount)
        Unit
    }

    fun equity(): List<EquityPoint> = read { db -> equity(db, settings(db)) }

    private suspend fun equity(db: Transactor, settings: Settings?): List<EquityPoint> {
        var equity = settings?.get(Setting.Opening) ?: return emptyList()
        var pnl = BigDecimal.ZERO
        val events = db.query("""
            SELECT time, amount, 0 AS trading, id FROM transfers
            UNION ALL SELECT closed, pnl, 1, id FROM trades WHERE status='Closed'
            ORDER BY trading, id
        """.trimIndent()) { Triple(Instant.parse(getText(0)), amount("amount"), getLong("trading") != 0L) }
        return buildList {
            add(EquityPoint(null, equity, pnl))
            for ((time, amount, trading) in events.sortedBy { it.first }) {
                equity += amount
                if (trading) pnl += amount
                add(EquityPoint(time, equity, pnl))
            }
        }
    }

    fun backup(target: Path) {
        separate(target)
        writeAtomically(target) { temp ->
            runBlocking { database.useWriterConnection { it.exec("VACUUM INTO ?", temp.toString()) } }
        }
    }

    fun validateBackup(source: Path) = withBackup(source, replace = false)
    fun restore(source: Path) = withBackup(source, replace = true)

    private fun withBackup(source: Path, replace: Boolean) {
        separate(source)
        require(Files.isRegularFile(source)) { "Choose an existing Trading Journal backup." }
        runBlocking {
            database.useWriterConnection { db ->
                db.exec("ATTACH DATABASE ? AS backup", source.toAbsolutePath().toUri().toASCIIString() + "?mode=ro")
                try {
                    // Validate the same snapshot that will be copied. Room keeps its own schema metadata.
                    db.withTransaction(if (replace) Transactor.SQLiteTransactionType.IMMEDIATE
                        else Transactor.SQLiteTransactionType.DEFERRED) {
                        checkBackup(db)
                        if (replace) {
                            for (table in journalColumns.keys.reversed()) db.exec("DELETE FROM main.$table")
                            for ((table, columns) in journalColumns)
                                db.exec("INSERT INTO main.$table ($columns) SELECT $columns FROM backup.$table")
                        }
                    }
                } finally {
                    db.exec("DETACH DATABASE backup")
                }
            }
        }
    }

    private suspend fun checkBackup(db: Transactor) {
        val version = db.version("backup")
        require(version in setOf(LEGACY_JOURNAL_VERSION, JOURNAL_VERSION) &&
            db.query("PRAGMA backup.integrity_check") { getText(0) } == listOf("ok") &&
            db.query("PRAGMA backup.foreign_key_check") { getText(0) }.isEmpty()) {
            "Choose a valid Trading Journal backup."
        }
        val actualTables = db.query("SELECT name FROM backup.sqlite_master WHERE type='table'") { getText(0) }.toSet()
        val expected = if (version == JOURNAL_VERSION) journalColumns.keys + "room_master_table" else journalColumns.keys
        require(actualTables == expected) { "The backup has an unexpected database schema." }
        for ((table, columns) in journalColumns) {
            val actual = db.query("PRAGMA backup.table_info($table)") { getString("name") }
            require(actual == columns.split(',')) { "The backup has an unexpected $table schema." }
        }
        settings(db, "backup")
        for (trade in trades(db, "backup")) {
            trade.validateStoredRecord()
        }
        db.query("SELECT time, amount FROM backup.transfers") {
            Instant.parse(getText(0))
            check(amount("amount").signum() != 0) { "Backup contains a zero transfer." }
        }
    }

    fun export(target: Path) {
        separate(target)
        val records = trades()
        writeAtomically(target) { writeTradeCsv(it, records) }
    }

    private fun separate(file: Path) {
        val target = file.toAbsolutePath().normalize()
        val live = path.toRealPath()
        val parent = target.parent?.takeIf(Files::exists)?.toRealPath()
        val resolved = parent?.resolve(target.fileName) ?: target
        val protectedFiles = listOf("", "-wal", "-shm", "-journal").map { suffix ->
            live.resolveSibling("${live.fileName}$suffix")
        }
        require(protectedFiles.none { protected ->
            resolved == protected ||
                (Files.exists(target) && Files.exists(protected) && Files.isSameFile(target, protected))
        }) { "Choose a path outside the live journal database files." }
    }
}

private fun writeAtomically(target: Path, write: (Path) -> Unit) {
    val absolute = target.toAbsolutePath()
    val temp = Files.createTempFile(absolute.parent, ".journal-", ".tmp")
    try {
        write(temp)
        Files.move(temp, absolute, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
}

// This is the on-disk order used by schema versions 2 and 3, independent of the UI order.
private val storedSettingsOrder = listOf(
    Setting.Opening, Setting.Equity, Setting.Available, Setting.Leverage,
    Setting.EntryFee, Setting.ExitFee, Setting.Slippage, Setting.Step,
    Setting.Minimum, Setting.Notional, Setting.Tick
)

private fun Settings.encode(): String = storedSettingsOrder.joinToString("|") { get(it).toString() }

private fun decodeSettings(raw: String): Settings {
    val values = raw.split('|')
    require(values.size == storedSettingsOrder.size) { "Invalid saved settings." }
    return Settings(storedSettingsOrder.zip(values.map(String::toBigDecimal)).toMap())
}

private suspend fun settings(db: Transactor, schema: String = "main"): Settings? =
    db.query("SELECT data FROM $schema.settings WHERE id=1") { decodeSettings(getText(0)) }.singleOrNull()

private suspend fun Transactor.version(schema: String = "main"): Int =
    query("PRAGMA $schema.user_version") { getLong(0).toInt() }.single()

private fun SQLiteStatement.amount(name: String): BigDecimal = getString(name).toBigDecimal()
private fun SQLiteStatement.getString(name: String): String = getText(column(name))
private fun SQLiteStatement.getLong(name: String): Long = getLong(column(name))

private fun SQLiteStatement.nullableText(name: String): String? {
    val index = column(name)
    return if (isNull(index)) null else getText(index)
}

private fun SQLiteStatement.column(name: String): Int =
    (0 until getColumnCount()).first { getColumnName(it) == name }

private suspend fun Transactor.exec(sql: String, vararg arguments: Any) {
    usePrepared(sql) { statement ->
        arguments.forEachIndexed { index, value -> statement.bindText(index + 1, value.toString()) }
        statement.step()
    }
}

private suspend fun <T> Transactor.query(
    sql: String, vararg arguments: Any, read: SQLiteStatement.() -> T
): List<T> = usePrepared(sql) { statement ->
    arguments.forEachIndexed { index, value -> statement.bindText(index + 1, value.toString()) }
    buildList { while (statement.step()) add(statement.read()) }
    }

