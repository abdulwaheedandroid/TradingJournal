package journal

import org.sqlite.SQLiteConfig
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant

class Journal(private val path: Path, private val clock: Clock = Clock.systemUTC()) {
    private val tables = linkedMapOf(
        "settings" to "id,data",
        "trades" to "id,created,status,direction,entry,stop,target,minimum_target,quantity,budget,risk,reward,margin,entry_fee,stop_fee,target_fee,assumptions,notes,closed,pnl,funding",
        "fills" to "id,trade_id,time,kind,price,quantity,fee",
        "transfers" to "id,time,amount"
    )

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        write { db ->
            when (db.version()) {
                0 -> {
                    require(db.query("SELECT name FROM sqlite_master WHERE type='table'") { getString(1) }.isEmpty()) {
                        "The existing database is not an empty Trading Journal database."
                    }
                    db.exec("CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), data TEXT NOT NULL)")
                    db.exec("""
                        CREATE TABLE trades (
                            id INTEGER PRIMARY KEY, created TEXT NOT NULL, status TEXT NOT NULL,
                            direction TEXT NOT NULL, entry TEXT NOT NULL, stop TEXT NOT NULL,
                            target TEXT NOT NULL, minimum_target TEXT NOT NULL, quantity TEXT NOT NULL,
                            budget TEXT NOT NULL, risk TEXT NOT NULL, reward TEXT NOT NULL,
                            margin TEXT NOT NULL, entry_fee TEXT NOT NULL, stop_fee TEXT NOT NULL,
                            target_fee TEXT NOT NULL, assumptions TEXT NOT NULL, notes TEXT NOT NULL,
                            closed TEXT, pnl TEXT, funding TEXT
                        )
                    """.trimIndent())
                    db.exec("""
                        CREATE TABLE fills (
                            id INTEGER PRIMARY KEY, trade_id INTEGER NOT NULL REFERENCES trades(id),
                            time TEXT NOT NULL, kind TEXT NOT NULL, price TEXT NOT NULL,
                            quantity TEXT NOT NULL, fee TEXT NOT NULL
                        )
                    """.trimIndent())
                    db.exec("CREATE INDEX fills_trade ON fills(trade_id)")
                    db.exec("CREATE TABLE transfers (id INTEGER PRIMARY KEY, time TEXT NOT NULL, amount TEXT NOT NULL)")
                    db.exec("PRAGMA user_version=2")
                }
                2 -> Unit
                else -> error("Unsupported Trading Journal database version.")
            }
        }
    }

    private fun connect(mode: SQLiteConfig.TransactionMode = SQLiteConfig.TransactionMode.IMMEDIATE): Connection {
        val config = SQLiteConfig().apply {
            enforceForeignKeys(true)
            setBusyTimeout(5000)
            setTransactionMode(mode)
        }
        return DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}", config.toProperties())
    }

    private fun <T> read(action: (Connection) -> T): T =
        connect(SQLiteConfig.TransactionMode.DEFERRED).use { db ->
            db.autoCommit = false
            action(db)
        }

    private fun <T> write(action: (Connection) -> T): T = connect().use { db ->
        db.autoCommit = false
        try {
            action(db).also { db.commit() }
        } catch (failure: Exception) {
            try {
                db.rollback()
            } catch (rollback: Exception) {
                failure.addSuppressed(rollback)
            }
            throw failure
        }
    }

    fun settings(): Settings? = connect().use { settings(it) }

    fun saveSettings(settings: Settings) = write {
        it.exec("INSERT OR REPLACE INTO settings VALUES (1, ?)", settings.encode())
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
        db.query("SELECT last_insert_rowid()") { getLong(1) }.single()
    }

    fun trades(): List<Trade> = read { trades(it) }

    private fun trades(db: Connection, schema: String = "main", id: Long? = null): List<Trade> {
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
                closed = getString("closed")?.let(Instant::parse),
                pnl = getString("pnl")?.toBigDecimal(),
                funding = getString("funding")?.toBigDecimal())
        }
    }

    private fun trade(db: Connection, id: Long): Trade =
        requireNotNull(trades(db, id = id).singleOrNull()) { "Trade #$id does not exist." }

    fun saveNotes(id: Long, notes: String) = write {
        require(it.exec("UPDATE trades SET notes=? WHERE id=?", notes, id) == 1) { "Trade no longer exists." }
    }

    fun cancel(id: Long) = write {
        require(it.exec("UPDATE trades SET status=? WHERE id=? AND status=?", Status.Cancelled, id, Status.Planned) == 1) {
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

    fun equity(): List<EquityPoint> = read { db ->
        var equity = settings(db)?.get(Setting.Opening) ?: return@read emptyList()
        var pnl = BigDecimal.ZERO
        val events = db.query("""
            SELECT time, amount, 0 AS trading, id FROM transfers
            UNION ALL SELECT closed, pnl, 1, id FROM trades WHERE status='Closed'
            ORDER BY trading, id
        """.trimIndent()) { Triple(Instant.parse(getString(1)), amount("amount"), getBoolean("trading")) }
        buildList {
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
        val absolute = target.toAbsolutePath()
        val temp = Files.createTempFile(absolute.parent, ".journal-backup-", ".sqlite3")
        try {
            connect().use { it.exec("VACUUM INTO ?", temp.toString()) }
            Files.move(temp, absolute, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun validateBackup(source: Path) = withBackup(source, replace = false)
    fun restore(source: Path) = withBackup(source, replace = true)

    private fun withBackup(source: Path, replace: Boolean) {
        separate(source)
        require(Files.isRegularFile(source)) { "Choose an existing Trading Journal backup." }
        // ATTACH keeps validation and the replacement in one transaction.
        write { db ->
            db.exec("ATTACH DATABASE ? AS backup", source.toAbsolutePath().toUri().toASCIIString() + "?mode=ro")
            require(db.version("backup") == 2 &&
                db.query("PRAGMA backup.integrity_check") { getString(1) } == listOf("ok") &&
                db.query("PRAGMA backup.foreign_key_check") { getString(1) }.isEmpty()) {
                "Choose a valid Trading Journal backup."
            }
            val actualTables = db.query("SELECT name FROM backup.sqlite_master WHERE type='table'") { getString(1) }
            require(actualTables.toSet() == tables.keys) { "The backup has an unexpected database schema." }
            for ((table, columns) in tables) {
                val actual = db.query("PRAGMA backup.table_info($table)") { getString("name") }
                require(actual == columns.split(',')) { "The backup has an unexpected $table schema." }
            }
            settings(db, "backup")
            for (trade in trades(db, "backup")) {
                val plan = trade.plan
                check(plan == calculate(plan.assumptions, plan.direction, plan.entry, plan.stop, plan.target)) {
                    "Backup contains an inconsistent plan for trade #${trade.id}."
                }
                var open = BigDecimal.ZERO
                var previous: Instant? = null
                for (fill in trade.fills) {
                    check(previous?.let { fill.time >= it } != false) { "Backup fills are out of order." }
                    open += if (fill.kind == FillKind.Entry) fill.quantity else -fill.quantity
                    check(open.signum() >= 0) { "Backup contains an exit that exceeds the open quantity." }
                    previous = fill.time
                }
                when (trade.status) {
                    Status.Closed -> check(trade.funding != null && trade.closed == trade.fills.lastOrNull()?.time &&
                        trade.pnl?.compareTo(trade.netPnl(trade.funding)) == 0) {
                        "Backup contains an inconsistent closed trade."
                    }
                    else -> check(trade.closed == null && trade.pnl == null && trade.funding == null &&
                        (trade.fills.isNotEmpty() == (trade.status == Status.Open))) {
                        "Backup contains an inconsistent active or cancelled trade."
                    }
                }
            }
            db.query("SELECT time, amount FROM backup.transfers") {
                Instant.parse(getString(1))
                check(amount("amount").signum() != 0) { "Backup contains a zero transfer." }
            }
            if (replace) {
                for (table in tables.keys.reversed()) db.exec("DELETE FROM main.$table")
                for ((table, columns) in tables)
                    db.exec("INSERT INTO main.$table ($columns) SELECT $columns FROM backup.$table")
            }
        }
    }

    fun export(target: Path) {
        separate(target)
        Files.newBufferedWriter(target).use { writer ->
            fun row(vararg values: Any?) {
                writer.appendLine(values.joinToString(",") {
                    val escaped = it?.toString().orEmpty().replace("\"", "\"\"")
                    "\"$escaped\""
                })
            }
            writer.write("\uFEFF")
            row("ID", "Created UTC", "Status", "Exchange", "Market", "Timeframe", "Direction", "Sizing equity USDT",
                "Entry", "Stop", "Target", "Minimum target", "BTC", "Budget USDT", "Estimated risk USDT",
                "Estimated reward USDT", "Estimated margin USDT", "Reward per unit risk", "Closed UTC",
                "Net P&L USDT", "Funding USDT", "Realized R", "Notes", "Deviations", "Fills (UTC/type/price/BTC/fee)")
            for (trade in trades()) {
                val p = trade.plan
                val notes = if (trade.notes.trimStart().firstOrNull() in listOf('=', '+', '-', '@'))
                    "'${trade.notes}" else trade.notes
                row(trade.id, trade.created, trade.status, "Bitget", "BTCUSDT USDT-M", "5m", p.direction,
                    p.assumptions[Setting.Equity].display(), p.entry.display(), p.stop.display(),
                    p.target.display(), p.minimumTarget.display(), p.quantity.display(), p.budget.display(),
                    p.risk.display(), p.reward.display(), p.margin.display(), p.rewardMultiple.display(),
                    trade.closed, trade.pnl?.display(), trade.funding?.display(), trade.realizedR?.display(),
                    notes, trade.deviations().joinToString("\n"), trade.fills.joinToString("\n") {
                        "${it.time} / ${it.kind} / ${it.price.display()} / ${it.quantity.display()} / ${it.fee.display()}"
                    })
            }
        }
    }

    private fun separate(file: Path) {
        require(file.toAbsolutePath().normalize() != path.toAbsolutePath().normalize() &&
            !(Files.exists(file) && Files.isSameFile(file, path))) {
            "Choose a path other than the live journal database."
        }
    }
}

private fun Settings.encode(): String = Setting.entries.joinToString("|") { get(it).toString() }

private fun decodeSettings(raw: String): Settings {
    val values = raw.split('|')
    require(values.size == Setting.entries.size) { "Invalid saved settings." }
    return Settings(Setting.entries.associateWith { values[it.ordinal].toBigDecimal() })
}

private fun settings(db: Connection, schema: String = "main"): Settings? =
    db.query("SELECT data FROM $schema.settings WHERE id=1") { decodeSettings(getString(1)) }.singleOrNull()

private fun Connection.version(schema: String = "main"): Int =
    query("PRAGMA $schema.user_version") { getInt(1) }.single()

private fun ResultSet.amount(name: String): BigDecimal = getString(name).toBigDecimal()

private fun Connection.exec(sql: String, vararg arguments: Any): Int =
    prepareStatement(sql).use { statement ->
        arguments.forEachIndexed { index, value -> statement.setString(index + 1, value.toString()) }
        statement.executeUpdate()
    }

private fun <T> Connection.query(sql: String, vararg arguments: Any, read: ResultSet.() -> T): List<T> =
    prepareStatement(sql).use { statement ->
        arguments.forEachIndexed { index, value -> statement.setString(index + 1, value.toString()) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.read()) } }
    }

