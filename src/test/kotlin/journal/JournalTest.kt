package journal

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

    @Test
    fun reopensPlansAndFillsAndRecognizesOnlyClosedPnlAtFinalExit() {
        val file = temporary.root.toPath().resolve("journal.sqlite3")
        val journal = Journal(file, clock)
        journal.saveSettings(sample())
        val id = journal.savePlan(Direction.Short, d("60000"), d("60300"), null, "Original notes")
        val plan = journal.trades().single().plan
        journal.saveSettings(sample(Setting.Equity to "2500"))
        val reopened = Journal(file, clock)
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
        val persisted = Journal(file, clock)
        val trade = persisted.trades().single()
        assertEquals("Reviewed", trade.notes)
        assertEquals(Status.Closed, trade.status)
        assertEquals(now.plusSeconds(5), trade.closed)
        assertEquals(4, trade.fills.size)
        assertTrue(trade.deviations().isNotEmpty())
        assertEquals(ratio(d("9.95"), plan.risk), trade.realizedR)
        assertEquals(listOf(null, now.plusSeconds(4), now.plusSeconds(5), now.plusSeconds(6)),
            persisted.equity().map { it.time })
        assertAmount("1049.95", persisted.equity().last().equity)
        assertAmount("9.95", persisted.equity().last().tradingPnl)
    }

    @Test
    fun backupRestoresSettingsTradesFillsAndTransfersAndRejectsInvalidSource() {
        val directory = temporary.root.toPath()
        val journal = Journal(directory.resolve("live.sqlite3"), clock)
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
}

