package journal

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

private val ZERO = BigDecimal.ZERO
private val ONE = BigDecimal.ONE
private val TWO = BigDecimal("2")

fun decimal(raw: String, label: String): BigDecimal {
    val value = raw.trim().toBigDecimalOrNull()
    require(value != null && value.scale() in -15..12 && value.abs() <= BigDecimal("1e15")) {
        "Enter a valid $label (up to 12 decimal places and magnitude 10^15)."
    }
    return value
}

fun BigDecimal.display(): String = stripTrailingZeros().toPlainString()
fun ratio(numerator: BigDecimal, denominator: BigDecimal): BigDecimal =
    numerator.divide(denominator, 6, RoundingMode.HALF_UP)

enum class Direction(val sign: BigDecimal) { Long(ONE), Short(ONE.negate()) }
enum class Status { Planned, Open, Closed, Cancelled }
enum class FillKind { Entry, Exit }

enum class Setting(val label: String) {
    Opening("Opening journal balance (USDT)"),
    Equity("Current futures account equity (USDT)"),
    Available("Available margin (USDT)"),
    Leverage("Leverage (×)"),
    EntryFee("Estimated entry fee (%)"),
    ExitFee("Estimated exit fee (%)"),
    Slippage("Estimated stop slippage (%)"),
    Step("BTC quantity increment"),
    Minimum("Minimum BTC quantity"),
    Notional("Minimum notional (USDT)"),
    Tick("Price tick (USDT)")
}

data class Settings(private val amounts: Map<Setting, BigDecimal>) {
    init {
        for (key in Setting.entries) {
            val value = get(key)
            when (key) {
                Setting.Equity, Setting.Step, Setting.Minimum, Setting.Tick ->
                    require(value > ZERO) { "${key.label} must be positive." }
                Setting.Leverage -> require(value >= ONE) { "Leverage must be at least 1×." }
                Setting.EntryFee, Setting.ExitFee, Setting.Slippage ->
                    require(value >= ZERO && value < BigDecimal("100")) {
                        "${key.label} must be at least zero and below 100%."
                    }
                else -> require(value >= ZERO) { "${key.label} must be at least zero." }
            }
        }
    }

    operator fun get(key: Setting): BigDecimal = amounts.getValue(key)

    companion object {
        fun parse(fields: Map<Setting, String>) =
            Settings(Setting.entries.associateWith { decimal(fields.getValue(it), it.label) })
    }
}

data class Plan(
    val direction: Direction,
    val entry: BigDecimal,
    val stop: BigDecimal,
    val target: BigDecimal,
    val minimumTarget: BigDecimal,
    val quantity: BigDecimal,
    val budget: BigDecimal,
    val risk: BigDecimal,
    val reward: BigDecimal,
    val margin: BigDecimal,
    val entryFee: BigDecimal,
    val stopFee: BigDecimal,
    val targetFee: BigDecimal,
    val assumptions: Settings
) {
    val rewardMultiple get() = ratio(reward, risk)
}

fun calculate(
    settings: Settings,
    direction: Direction,
    entry: BigDecimal,
    stop: BigDecimal,
    target: BigDecimal? = null
): Plan {
    val sign = direction.sign
    val tick = settings[Setting.Tick]
    require(entry > ZERO && stop > ZERO) { "Entry and stop prices must be positive." }
    require(sign * (entry - stop) > ZERO) {
        "Place a Long stop below entry or a Short stop above entry."
    }
    require((entry % tick).signum() == 0 && (stop % tick).signum() == 0) {
        "Use entry and stop prices in increments of ${tick.display()} USDT."
    }

    val entryRate = settings[Setting.EntryFee].movePointLeft(2)
    val exitRate = settings[Setting.ExitFee].movePointLeft(2)
    val slippage = settings[Setting.Slippage].movePointLeft(2)
    val stopFill = stop * (ONE - sign * slippage)
    val entryFeePerBtc = entry * entryRate
    val stopFeePerBtc = stopFill * exitRate
    val reserveFees = entryFeePerBtc + stopFeePerBtc
    val lossPerBtc = sign * (entry - stopFill) + reserveFees
    val budget = settings[Setting.Equity].movePointLeft(2)
    val step = settings[Setting.Step]
    val leverage = settings[Setting.Leverage]
    val riskUnits = budget.divide(lossPerBtc * step, 0, RoundingMode.FLOOR)
    // Multiply through by leverage to avoid rounding the margin before sizing.
    val marginUnits = (settings[Setting.Available] * leverage)
        .divide((entry + reserveFees * leverage) * step, 0, RoundingMode.FLOOR)
    val quantity = riskUnits.min(marginUnits) * step
    val minimum = settings[Setting.Minimum]
    val notional = settings[Setting.Notional]
    require(quantity >= minimum && quantity * entry >= notional) {
        val constraint = when {
            marginUnits < riskUnits -> "Available margin"
            marginUnits > riskUnits -> "The 1% risk budget"
            else -> "Available margin and the 1% risk budget"
        }
        "$constraint allows ${quantity.display()} BTC. The contract requires at least " +
            "${minimum.display()} BTC and ${notional.display()} USDT of entry notional. " +
            "Check your account and contract settings or revise the plan."
    }

    val rounding = if (direction == Direction.Long) RoundingMode.CEILING else RoundingMode.FLOOR
    val minimumTarget = (sign * entry + entryFeePerBtc + TWO * lossPerBtc)
        .divide((sign - exitRate) * tick, 0, rounding) * tick
    fun rewardPerBtc(price: BigDecimal) = sign * (price - entry) - entryFeePerBtc - price * exitRate
    require(minimumTarget > ZERO && rewardPerBtc(minimumTarget) >= TWO * lossPerBtc) {
        "No positive price on the configured tick meets the 1:2 minimum. Revise the stop."
    }
    val chosen = target ?: minimumTarget
    require(chosen > ZERO && (chosen % tick).signum() == 0) {
        "Use a positive take-profit price in increments of ${tick.display()} USDT."
    }
    val reward = rewardPerBtc(chosen) * quantity
    val risk = lossPerBtc * quantity
    require(reward >= TWO * risk) {
        val comparison = if (direction == Direction.Long) "at least" else "at most"
        "Use a take-profit price of $comparison ${minimumTarget.display()} USDT to meet 1:2 after fees."
    }
    val feeReserve = quantity * reserveFees
    val margin = (quantity * entry).divide(leverage, maxOf(12, feeReserve.scale()), RoundingMode.CEILING) + feeReserve
    return Plan(direction, entry, stop, chosen, minimumTarget, quantity, budget, risk, reward, margin,
        quantity * entryFeePerBtc, quantity * stopFeePerBtc, quantity * chosen * exitRate, settings)
}

data class Fill(val time: Instant, val kind: FillKind, val price: BigDecimal, val quantity: BigDecimal,
                val fee: BigDecimal) {
    init {
        require(price > ZERO && quantity > ZERO) { "Fill price and quantity must be positive." }
    }
}

data class Trade(
    val id: Long, val created: Instant, val status: Status, val plan: Plan, val notes: String,
    val fills: List<Fill>, val closed: Instant?, val pnl: BigDecimal?, val funding: BigDecimal?
) {
    val entered get() = fills.filter { it.kind == FillKind.Entry }.sumOf { it.quantity }
    val exited get() = fills.filter { it.kind == FillKind.Exit }.sumOf { it.quantity }
    val open get() = entered - exited
    val realizedR get() = pnl?.let { ratio(it, plan.risk) }

    fun netPnl(signedFunding: BigDecimal): BigDecimal {
        require(entered > ZERO && open.signum() == 0) { "Exit the entire entered quantity before closing." }
        val cash = fills.sumOf {
            it.price * it.quantity * if (it.kind == FillKind.Exit) ONE else ONE.negate()
        }
        return plan.direction.sign * cash - fills.sumOf { it.fee } + signedFunding
    }

    fun deviations(): List<String> = buildList {
        if (fills.any { it.kind == FillKind.Entry && it.price.compareTo(plan.entry) != 0 })
            add("An entry fill differed from the planned entry price.")
        if (entered > plan.quantity || (status == Status.Closed && entered < plan.quantity))
            add("Total entered quantity differed from the plan.")
        if (fills.any { it.kind == FillKind.Exit && it.price.compareTo(plan.stop) != 0 &&
                    it.price.compareTo(plan.target) != 0 })
            add("An exit fill differed from the planned stop and target prices.")
        if (pnl != null && -pnl > plan.risk) add("Realized loss exceeded the estimated planned loss.")
        if (pnl != null && -pnl > plan.budget) add("Realized loss exceeded the original 1% budget.")
    }
}

data class EquityPoint(val time: Instant?, val equity: BigDecimal, val tradingPnl: BigDecimal)

