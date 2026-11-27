package journal

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun d(value: String) = BigDecimal(value)

private fun sample(vararg changes: Pair<Setting, String>): Settings =
    Settings.parse(Setting.entries.zip(listOf("1000", "1000", "1000", "5", "0.06",
        "0.06", "0.02", "0.001", "0.001", "5", "0.1")).toMap() + changes)

private fun assertAmount(expected: String, actual: BigDecimal?) {
    assertEquals(d(expected).display(), actual?.display())
}

class CalculatorTest {
    @Test
    fun longAndShortReachBudgetAndMeetFeeAdjustedTargetAtNearestTick() {
        for (direction in Direction.entries) {
            val entry = d("60000")
            val stop = if (direction == Direction.Long) d("59700") else d("60300")
            val settings = sample()
            val plan = calculate(settings, direction, entry, stop)
            val stopFill = stop * (BigDecimal.ONE - direction.sign * d("0.0002"))
            val lossPerBtc = direction.sign * (entry - stopFill) + (entry + stopFill) * d("0.0006")
            val rewardPerBtc = direction.sign * (plan.target - entry) - (entry + plan.target) * d("0.0006")
            assertAmount("10", plan.budget)
            assertAmount("0.026", plan.quantity)
            assertEquals(0, plan.quantity.remainder(d("0.001")).signum())
            assertEquals(0, plan.target.remainder(d("0.1")).signum())
            assertEquals(0, (lossPerBtc * plan.quantity).compareTo(plan.risk))
            assertEquals(0, (rewardPerBtc * plan.quantity).compareTo(plan.reward))
            assertTrue(plan.risk <= plan.budget)
            assertTrue(lossPerBtc * (plan.quantity + d("0.001")) > plan.budget)
            assertTrue(plan.reward >= plan.risk * d("2"))
            assertEquals(plan.minimumTarget, plan.target)
            val worse = plan.target - direction.sign * d("0.1")
            assertFailsWith<IllegalArgumentException> { calculate(settings, direction, entry, stop, worse) }
            val better = plan.target + direction.sign * d("0.1")
            assertEquals(better, calculate(settings, direction, entry, stop, better).target)
        }
    }

    @Test
    fun marginCapsSizeWithoutChangingBudget() {
        val settings = sample(Setting.Available to "100")
        val plan = calculate(settings, Direction.Long, d("60000"), d("59700"))
        assertAmount("10", plan.budget)
        assertAmount("0.008", plan.quantity)
        assertTrue(plan.margin <= settings[Setting.Available])
        val moreLeverage = calculate(sample(Setting.Available to "100", Setting.Leverage to "10"),
            Direction.Long, d("60000"), d("59700"))
        assertTrue(moreLeverage.quantity > plan.quantity)
        assertEquals(plan.budget, moreLeverage.budget)
    }

    @Test
    fun rejectsInvalidStopsTicksAndExchangeMinimumsWithoutRoundingUp() {
        val entry = d("60000")
        val stop = d("59700")
        for ((direction, invalidStop) in listOf(Direction.Long to d("60001"), Direction.Short to stop,
            Direction.Long to entry, Direction.Long to d("59700.01"))) {
            assertFailsWith<IllegalArgumentException> { calculate(sample(), direction, entry, invalidStop) }
        }
        for (change in listOf(Setting.Minimum to "0.027", Setting.Notional to "1561",
            Setting.Available to "12", Setting.Step to "1")) {
            val error = assertFailsWith<IllegalArgumentException> {
                calculate(sample(change), Direction.Long, entry, stop)
            }
            assertTrue(error.message.orEmpty().contains("contract requires"))
        }
        val impossibleTarget = assertFailsWith<IllegalArgumentException> {
            calculate(sample(Setting.Equity to "100000"), Direction.Short, d("60000"), d("91000"))
        }
        assertTrue(impossibleTarget.message.orEmpty().contains("No positive price"))
    }
}

class JournalTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val journals = mutableListOf<Journal>()

    private fun open(file: java.nio.file.Path) = Journal(file, clock).also(journals::add)

    @After
    fun closeJournals() {
        journals.asReversed().forEach(Journal::close)
    }

    @Test
    fun reopensPlansAndFillsAndRecognizesOnlyClosedPnlAtFinalExit() {
        val file = temporary.root.toPath().resolve("journal.sqlite3")
        val journal = open(file)
        journal.saveSettings(sample())
        val id = journal.savePlan(Direction.Short, d("60000"), d("60300"), null, "Original notes")
        val plan = journal.trades().single().plan
        journal.saveSettings(sample(Setting.Equity to "2500"))
        val reopened = open(file)
        assertEquals(plan, reopened.trades().single().plan)
        assertAmount("2500", reopened.settings()?.get(Setting.Equity))
        assertEquals(1, reopened.equity().size)

        fun fill(seconds: Long, kind: FillKind, price: String, qty: String, fee: String) =
            Fill(now.plusSeconds(seconds), kind, d(price), d(qty), d(fee))
        reopened.addFill(id, fill(1, FillKind.Entry, "60000", "0.010", "0.4"))
        reopened.addFill(id, fill(2, FillKind.Entry, "60100", "0.005", "-0.1"))
        reopened.addFill(id, fill(3, FillKind.Exit, "59500", "0.010", "0.2"))
        assertFailsWith<IllegalArgumentException> {
            reopened.addFill(id, fill(4, FillKind.Exit, "59500", "0.006", "0"))
        }
        assertFailsWith<IllegalArgumentException> { reopened.closeTrade(id, d("0.25")) }
        reopened.addFill(id, fill(5, FillKind.Exit, "59000", "0.005", "0.3"))
        reopened.transfer(d("50"), now.plusSeconds(4))
        reopened.transfer(d("-10"), now.plusSeconds(6))
        assertAmount("0", reopened.equity().last().tradingPnl)

        assertAmount("9.95", reopened.closeTrade(id, d("0.25")))
        reopened.saveNotes(id, "Reviewed")
        val persisted = open(file)
        val trade = persisted.trades().single()
        assertEquals("Reviewed", trade.notes)
        assertEquals(Status.Closed, trade.status)
        assertEquals(now.plusSeconds(5), trade.closed)
        assertEquals(4, trade.fills.size)
        assertTrue(trade.deviations().isNotEmpty())
        assertEquals(ratio(d("9.95"), plan.risk), trade.realizedR)
        assertEquals(listOf(null, now.plusSeconds(4), now.plusSeconds(5), now.plusSeconds(6)),
            persisted.equity().map { it.time })
        val snapshot = persisted.snapshot()
        assertEquals(listOf(trade), snapshot.trades)
        assertAmount("2500", snapshot.settings?.get(Setting.Equity))
        assertAmount("1049.95", snapshot.equity.last().equity)
        assertAmount("9.95", snapshot.equity.last().tradingPnl)
    }

    @Test
    fun backupRestoresSettingsTradesFillsAndTransfersAndRejectsInvalidSource() {
        val directory = temporary.root.toPath()
        val journal = open(directory.resolve("live.sqlite3"))
        val backup = directory.resolve("backup.sqlite3")
        journal.backup(backup)
        journal.validateBackup(backup)
        val original = sample()
        journal.saveSettings(original)
        val id = journal.savePlan(Direction.Long, d("60000"), d("59700"), null, "Keep")
        journal.addFill(id, Fill(now, FillKind.Entry, d("60000"), d("0.01"), d("0.02")))
        journal.addFill(id, Fill(now.plusSeconds(1), FillKind.Exit, d("60500"), d("0.01"), d("0.03")))
        journal.closeTrade(id, d("-0.2"))
        val cancelled = journal.savePlan(Direction.Short, d("60000"), d("60300"), null, "Cancelled")
        journal.cancel(cancelled)
        assertNull(journal.trades().first().pnl)
        journal.transfer(d("10"))
        val trades = journal.trades()
        val points = journal.equity()
        journal.backup(backup)
        journal.saveSettings(sample(Setting.Equity to "1900"))
        journal.saveNotes(id, "Discard after restore")
        journal.transfer(d("20"))
        journal.validateBackup(backup)
        assertAmount("1900", journal.settings()?.get(Setting.Equity))

        val invalid = Files.writeString(directory.resolve("invalid.sqlite3"), "invalid")
        assertFails { journal.restore(invalid) }
        assertAmount("1900", journal.settings()?.get(Setting.Equity))
        journal.restore(backup)
        assertEquals(original, journal.settings())
        assertEquals(trades, journal.trades())
        assertEquals(points, journal.equity())
    }

    @Test
    fun fileOperationsRejectLiveDatabaseAndSidecarPaths() {
        val file = temporary.root.toPath().resolve("live.sqlite3")
        val journal = open(file)
        journal.saveSettings(sample())

        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val target = file.resolveSibling("${file.fileName}$suffix")
            assertFailsWith<IllegalArgumentException> { journal.backup(target) }
            assertFailsWith<IllegalArgumentException> { journal.export(target) }
            assertFailsWith<IllegalArgumentException> { journal.restore(target) }
        }

        assertEquals(sample(), journal.settings())
    }

    @Test
    fun csvExportReplacesDestinationAndEscapesNotes() {
        val directory = temporary.root.toPath()
        val journal = open(directory.resolve("live.sqlite3"))
        journal.saveSettings(sample())
        journal.savePlan(Direction.Long, d("60000"), d("59700"), null, "=1+\"2\"")
        val destination = Files.writeString(directory.resolve("trades.csv"), "previous export")

        journal.export(destination)

        val csv = Files.readString(destination)
        assertTrue(csv.startsWith("\uFEFF\"ID\","))
        assertTrue(csv.contains("\"'=1+\"\"2\"\"\""))
        assertTrue("previous export" !in csv)
    }

    @Test
    fun restorePreservesHistoricalEstimatesAndRejectsInvalidSnapshots() {
        val directory = temporary.root.toPath()
        val journal = open(directory.resolve("live.sqlite3"))
        journal.saveSettings(sample())
        journal.savePlan(Direction.Long, d("60000"), d("59700"), null, "Original")
        val backup = directory.resolve("backup.sqlite3")
        journal.backup(backup)
        BundledSQLiteDriver().open(backup.toString()).use {
            it.execSQL("UPDATE trades SET margin = '314.159'")
        }
        journal.restore(backup)
        assertAmount("314.159", journal.trades().single().plan.margin)

        BundledSQLiteDriver().open(backup.toString()).use {
            it.execSQL("UPDATE trades SET risk = '0'")
        }
        assertFails { journal.restore(backup) }
        assertTrue(journal.trades().single().plan.risk.signum() > 0)
    }

    @Test
    fun migratesVersionTwoInPlaceAndRestoresVersionTwoBackup() {
        val file = temporary.root.toPath().resolve("existing.sqlite3")
        val backup = file.resolveSibling("legacy-backup.sqlite3")
        val settings = sample()
        val plan = calculate(settings, Direction.Long, d("60000"), d("59700"))
        val encoded = Setting.entries.joinToString("|") { settings[it].toString() }
        BundledSQLiteDriver().open(file.toString()).use { db ->
            db.execSQL("CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), data TEXT NOT NULL)")
            db.execSQL("""
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
            db.execSQL("""
                CREATE TABLE fills (
                    id INTEGER PRIMARY KEY, trade_id INTEGER NOT NULL REFERENCES trades(id),
                    time TEXT NOT NULL, kind TEXT NOT NULL, price TEXT NOT NULL,
                    quantity TEXT NOT NULL, fee TEXT NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX fills_trade ON fills(trade_id)")
            db.execSQL("CREATE TABLE transfers (id INTEGER PRIMARY KEY, time TEXT NOT NULL, amount TEXT NOT NULL)")
            db.execSQL("INSERT INTO settings VALUES (1, '$encoded')")
            db.execSQL("""
                INSERT INTO trades VALUES (42, '$now', 'Open', 'Long', '${plan.entry}', '${plan.stop}',
                    '${plan.target}', '${plan.minimumTarget}', '${plan.quantity}', '${plan.budget}',
                    '${plan.risk}', '${plan.reward}', '${plan.margin}', '${plan.entryFee}', '${plan.stopFee}',
                    '${plan.targetFee}', '$encoded', 'Legacy notes', NULL, NULL, NULL)
            """.trimIndent())
            db.execSQL("INSERT INTO fills VALUES (87, 42, '$now', 'Entry', '60000', '0.010', '0.012345678900')")
            db.execSQL("INSERT INTO transfers VALUES (24, '$now', '25.000000000001')")
            db.execSQL("PRAGMA user_version=2")
        }
        Files.copy(file, backup)

        val room = open(file)
        val trade = room.trades().single()
        assertEquals(42L, trade.id)
        assertEquals(plan, trade.plan)
        assertEquals(settings, room.settings())
        assertEquals("Legacy notes", trade.notes)
        assertEquals(d("0.012345678900"), trade.fills.single().fee)
        assertAmount("1025.000000000001", room.equity().last().equity)
        room.saveNotes(42, "Edited in Room")
        assertEquals("Edited in Room", room.trades().single().notes)
        room.restore(backup)
        assertEquals(trade, room.trades().single())
        assertEquals(43L, room.savePlan(Direction.Long, d("60000"), d("59700"), null, "New plan"))

        BundledSQLiteDriver().open(file.toString()).use { db ->
            db.prepare("PRAGMA user_version").use {
                assertTrue(it.step())
                assertEquals(3L, it.getLong(0))
            }
        }
        BundledSQLiteDriver().open(backup.toString()).use { db ->
            db.prepare("PRAGMA user_version").use {
                assertTrue(it.step())
                assertEquals(2L, it.getLong(0))
            }
        }
    }
}

