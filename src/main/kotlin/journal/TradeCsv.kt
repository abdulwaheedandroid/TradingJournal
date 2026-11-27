package journal

import java.nio.file.Files
import java.nio.file.Path

internal fun writeTradeCsv(target: Path, trades: List<Trade>) {
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
        for (trade in trades) {
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
