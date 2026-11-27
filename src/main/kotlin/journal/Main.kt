package journal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.io.File
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser

private const val MARKET = "Bitget  /  BTCUSDT USDT-M futures  /  5 minutes"
private val zone = ZoneId.systemDefault()
private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)

fun main() = application {
    val opened by produceState<Result<Journal>?>(null) {
        var journal: Journal? = null
        try {
            value = withContext(journalIo) {
                runCatching { Journal(journalPath()).also { journal = it } }
            }
            awaitCancellation()
        } finally {
            withContext(journalIo + NonCancellable) { journal?.close() }
        }
    }
    Window(onCloseRequest = ::exitApplication, title = "Trading Journal",
        state = rememberWindowState(width = 1150.dp, height = 820.dp)) {
        SideEffect { window.minimumSize = Dimension(860, 640) }
        MaterialTheme(colorScheme = lightColorScheme(
            primary = Color(0xFF236050),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD9EEE5),
            secondary = Color(0xFF315B82),
            background = Color(0xFFF5F7F5),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE8EEE9),
            onSurface = Color(0xFF172A24)
        )) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val result = opened
                val journal = result?.getOrNull()
                if (result == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Opening journal…")
                    }
                } else if (journal == null) {
                    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Heading("The journal could not be opened")
                        Text(result.exceptionOrNull()?.message ?: "Unknown database error")
                        Button(onClick = ::exitApplication) { Text("Exit") }
                    }
                } else {
                    JournalApp(journal)
                }
            }
        }
    }
}

private fun journalPath(): Path {
    val directory = System.getenv("LOCALAPPDATA")?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".local", "share")
    val data = System.getProperty("tradingjournal.dataDir")?.let(Path::of) ?: directory.resolve("TradingJournal")
    return data.resolve("journal-v2.sqlite3")
}

internal enum class Screen(val label: String) {
    Dashboard("Dashboard"), Calculator("Trade Calculator"), History("Trade History"), Settings("Settings")
}

@Composable
private fun JournalApp(journal: Journal) {
    val scope = rememberCoroutineScope()
    val ui = remember(journal, scope) { UiState(journal, scope) }
    LaunchedEffect(ui) {
        ui.action {
            ui.refresh()
            if (ui.settings == null) ui.screen = Screen.Settings
        }
    }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp)) {
                Text("Trading Journal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(MARKET, color = MaterialTheme.colorScheme.secondary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (screen in Screen.entries) {
                FilterChip(selected = ui.screen == screen, label = { Text(screen.label) }, onClick = {
                    ui.screen = screen
                    ui.feedback = null
                    ui.selected = null
                })
            }
        }
        ui.feedback?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 28.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp)) {
            when (ui.screen) {
                Screen.Dashboard -> Dashboard(journal, ui)
                Screen.Calculator -> Calculator(journal, ui)
                Screen.History -> History(journal, ui)
                Screen.Settings -> SettingsScreen(journal, ui)
            }
        }
    }

    if (ui.busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Working…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {}
        )
    }
    ui.error?.let { error ->
        AlertDialog(onDismissRequest = { ui.error = null }, title = { Text("Check this operation") },
            text = { Text(error) }, confirmButton = {
                TextButton(onClick = { ui.error = null }) { Text("OK") }
            })
    }
    ui.backupToRestore?.let { source ->
        AlertDialog(onDismissRequest = { ui.backupToRestore = null }, title = { Text("Replace the current journal?") },
            text = { Text("Restoring ${source.fileName} replaces all current settings, trades, fills, and transfers.") },
            dismissButton = { TextButton(onClick = { ui.backupToRestore = null }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = {
                    ui.backupToRestore = null
                    ui.action("Backup restored.") {
                        withContext(journalIo) { journal.restore(source) }
                        ui.selected = null
                        ui.refresh()
                        ui.restoreCount++
                    }
                }) { Text("Restore backup") }
            })
    }
}

@Composable
private fun Heading(value: String) {
    Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit,
                  modifier: Modifier = Modifier.fillMaxWidth(), multiline: Boolean = false) {
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, singleLine = !multiline,
        minLines = if (multiline) 2 else 1)
}

@Composable
private fun <T> Choice(label: String, value: T, options: List<T>, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label · $value ▾") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { item ->
                DropdownMenuItem(text = { Text(item.toString()) }, onClick = {
                    onChange(item)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun Dashboard(journal: Journal, ui: UiState) {
    var transfer by remember { mutableStateOf("") }
    var timestamp by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Dashboard")
        if (ui.equity.isEmpty()) {
            Text("Save your opening balance in Settings to start the realized equity graph.")
            Button(onClick = { ui.screen = Screen.Settings }) { Text("Open Settings") }
        } else {
            val latest = ui.equity.last()
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SeriesCard("Realized equity", latest.equity, ui.equity.map { it.equity },
                    MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                SeriesCard("Cumulative net trading P&L", latest.tradingPnl, ui.equity.map { it.tradingPnl },
                    MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
            }
            Text(latest.time?.let { "Opening balance  →  ${dateTime.format(it)}" } ?: "Opening balance",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Record a deposit or withdrawal", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Field("Amount USDT (+ deposit, − withdrawal)", transfer, { transfer = it }, Modifier.weight(1f))
                Field("Local time YYYY-MM-DD HH:MM (optional)", timestamp, { timestamp = it }, Modifier.weight(1f))
                Button(onClick = {
                    ui.action("Transfer recorded.") {
                        val amount = decimal(transfer, "transfer amount")
                        val time = parseTimestamp(timestamp)
                        withContext(journalIo) { journal.transfer(amount, time) }
                        transfer = ""
                        timestamp = ""
                        ui.refresh()
                    }
                }) { Text("Record transfer") }
            }
        }
    }
}

@Composable
private fun SeriesCard(label: String, latest: BigDecimal, values: List<BigDecimal>, color: Color, modifier: Modifier) {
    val low = values.min()
    val high = values.max()
    val grid = MaterialTheme.colorScheme.outlineVariant
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text("${latest.display()} USDT", style = MaterialTheme.typography.headlineSmall, color = color)
            Text("High  ${high.display()}", style = MaterialTheme.typography.labelSmall)
            Canvas(Modifier.fillMaxWidth().height(165.dp)) {
                val inset = 5.dp.toPx()
                for (fraction in listOf(0f, .5f, 1f)) {
                    val y = inset + fraction * (size.height - 2 * inset)
                    drawLine(grid, Offset(0f, y), Offset(size.width, y))
                }
                fun point(index: Int): Offset {
                    val x = inset + (size.width - 2 * inset) * index / (values.size - 1).coerceAtLeast(1)
                    val fraction = if (high.compareTo(low) == 0) .5f
                        else ratio(values[index] - low, high - low).toFloat()
                    return Offset(x, size.height - inset - fraction * (size.height - 2 * inset))
                }
                for (i in 1..values.lastIndex) drawLine(color, point(i - 1), point(i), 2.dp.toPx())
                drawCircle(color, 4.dp.toPx(), point(values.lastIndex))
            }
            Text("Low  ${low.display()}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Calculator(journal: Journal, ui: UiState) {
    var direction by remember { mutableStateOf(Direction.Long) }
    var entry by remember { mutableStateOf("") }
    var stop by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var result by remember(ui.settings, direction, entry, stop, target) { mutableStateOf<Plan?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("Trade Calculator")
        val settings = ui.settings
        if (settings == null) {
            Text("Save your account figures and contract settings before calculating a trade.")
            Button(onClick = { ui.screen = Screen.Settings }) { Text("Open Settings") }
            return@Column
        }
        Text("Sizing equity  ${settings[Setting.Equity].display()} USDT  ·  Fixed risk budget  1%")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Direction", direction, Direction.entries) { direction = it }
                    Field("Entry price (USDT)", entry, { entry = it })
                    Field("Stop-loss price (USDT)", stop, { stop = it })
                    Field("Take profit (optional)", target, { target = it })
                    Field("Notes", notes, { notes = it }, multiline = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fun targetPrice() = target.takeIf(String::isNotBlank)?.let { decimal(it, "target price") }
                        OutlinedButton(onClick = { ui.action {
                            result = calculate(settings, direction, decimal(entry, "entry price"),
                                decimal(stop, "stop price"), targetPrice())
                        } }) { Text("Calculate") }
                        Button(onClick = { ui.action("Trade plan saved.") {
                            val side = direction
                            val entryPrice = decimal(entry, "entry price")
                            val stopPrice = decimal(stop, "stop price")
                            val targetPrice = targetPrice()
                            val planNotes = notes
                            val id = withContext(journalIo) {
                                journal.savePlan(side, entryPrice, stopPrice, targetPrice, planNotes)
                            }
                            ui.refresh()
                            ui.selected = id
                            ui.screen = Screen.History
                        } }) { Text("Save plan") }
                    }
                }
            }
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Position estimate", style = MaterialTheme.typography.titleLarge)
                    result?.let { PlanLines(it) } ?: Text("Calculate a plan to see size, estimated loss, and take profit.")
                    Text("Actual stop fills can exceed planned risk.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PlanLines(plan: Plan) {
    val values = listOf(
        "Quantity" to "${plan.quantity.display()} BTC",
        "1% risk budget" to "${plan.budget.display()} USDT",
        "Estimated planned loss" to "${plan.risk.display()} USDT",
        "Estimated margin with fee reserve" to "${plan.margin.display()} USDT",
        "Minimum take profit" to "${plan.minimumTarget.display()} USDT",
        "Chosen take profit" to "${plan.target.display()} USDT",
        "Estimated net reward" to "${plan.reward.display()} USDT",
        "Risk-to-reward" to "1 : ${plan.rewardMultiple.display()}",
        "Estimated entry / stop / target fees" to
            "${plan.entryFee.display()} / ${plan.stopFee.display()} / ${plan.targetFee.display()} USDT"
    )
    for ((label, value) in values) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun History(journal: Journal, ui: UiState) {
    var from by remember { mutableStateOf("") }
    var through by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("All") }
    var status by remember { mutableStateOf("All") }
    val selected = ui.trades.firstOrNull { it.id == ui.selected }
    if (selected != null) {
        key(selected.id) { Detail(journal, ui, selected) }
        return
    }
    val start = runCatching { LocalDate.parse(from.trim()) }.getOrNull()
    val end = runCatching { LocalDate.parse(through.trim()) }.getOrNull()
    val datesValid = (from.isBlank() || start != null) && (through.isBlank() || end != null) &&
        (start == null || end == null || start <= end)
    val filtered = if (datesValid) ui.trades.filter {
        val date = it.created.atZone(zone).toLocalDate()
        (direction == "All" || direction == it.plan.direction.name) &&
            (status == "All" || status == it.status.name) &&
            (start == null || date >= start) && (end == null || date <= end)
    } else emptyList()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Heading("Trade History")
            OutlinedButton(onClick = { ui.action {
                chooseFile("Export trade history", "Trade-History.csv")?.let {
                    withContext(journalIo) { journal.export(it) }
                    ui.feedback = "Trade history exported."
                }
            } }) { Text("Export CSV") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("From date (YYYY-MM-DD)", from, { from = it }, Modifier.weight(1f))
            Field("Through date (YYYY-MM-DD)", through, { through = it }, Modifier.weight(1f))
            Choice("Direction", direction, listOf("All") + Direction.entries.map { it.name }) { direction = it }
            Choice("Status", status, listOf("All") + Status.entries.map { it.name }) { status = it }
        }
        if (!datesValid) Text("Enter valid YYYY-MM-DD dates in chronological order.",
            color = MaterialTheme.colorScheme.error)
        if (datesValid && filtered.isEmpty()) Text("No trades match these filters.")
        if (filtered.isNotEmpty()) {
            HistoryRow(listOf("Created (local)", "Direction", "Status", "Entry", "BTC", "Net P&L (USDT)"))
            HorizontalDivider()
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(filtered, key = { it.id }) { trade ->
                Card(Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable { ui.selected = trade.id },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    HistoryRow(listOf(dateTime.format(trade.created), trade.plan.direction.name, trade.status.name,
                        trade.plan.entry.display(), trade.plan.quantity.display(), trade.pnl?.display() ?: "—"))
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(values: List<String>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        values.forEachIndexed { index, value ->
            Text(value, Modifier.weight(if (index == 0) 1.75f else 1f),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Detail(journal: Journal, ui: UiState, trade: Trade) {
    val plan = trade.plan
    var notes by remember(trade.notes) { mutableStateOf(trade.notes) }
    var kind by remember { mutableStateOf(FillKind.Entry) }
    var time by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("0") }
    var funding by remember { mutableStateOf("0") }
    var cancel by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = { ui.selected = null; ui.feedback = null }) { Text("‹ Trade History") }
        Heading("Trade #${trade.id} · ${plan.direction} · ${trade.status}")
        Text("Created ${dateTime.format(trade.created)}   ·   Entry ${plan.entry.display()}   ·   Stop ${plan.stop.display()}")
        trade.pnl?.let { pnl ->
            Text("Net P&L  ${pnl.display()} USDT   ·   ${trade.realizedR?.display()} R   ·   Funding  ${trade.funding?.display()} USDT",
                fontWeight = FontWeight.SemiBold)
            Text("Final exit  ${trade.closed?.let(dateTime::format)}")
        }
        for (flag in trade.deviations()) Text(flag, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Original plan", style = MaterialTheme.typography.titleMedium)
                PlanLines(plan)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Original sizing assumptions", style = MaterialTheme.typography.titleMedium)
                for (setting in Setting.entries.filterNot { it == Setting.Opening }) {
                    Text("${setting.label}  ·  ${plan.assumptions[setting].display()}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider()
        Text("Fills · ${trade.open.display()} BTC open", style = MaterialTheme.typography.titleMedium)
        if (trade.fills.isEmpty()) Text("No fills recorded.")
        else {
            HistoryRow(listOf("Time (local)", "Type", "Price (USDT)", "Quantity (BTC)", "Fee (USDT)"))
            trade.fills.forEach { fill ->
                HistoryRow(listOf(dateTime.format(fill.time), fill.kind.name, fill.price.display(),
                    fill.quantity.display(), fill.fee.display()))
            }
        }
        if (trade.status == Status.Planned || trade.status == Status.Open) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Choice("Fill type", kind, FillKind.entries) { kind = it }
                Field("Fill price", price, { price = it }, Modifier.weight(1f))
                Field("BTC quantity", quantity, { quantity = it }, Modifier.weight(1f))
                Field("Actual fee (USDT)", fee, { fee = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Field("Local time YYYY-MM-DD HH:MM (optional)", time, { time = it }, Modifier.weight(1f))
                Button(onClick = { ui.action("Fill recorded.") {
                    val fill = Fill(parseTimestamp(time), kind, decimal(price, "fill price"),
                        decimal(quantity, "fill quantity"), decimal(fee, "fill fee"))
                    withContext(journalIo) { journal.addFill(trade.id, fill) }
                    price = ""
                    quantity = ""
                    fee = "0"
                    time = ""
                    ui.refresh()
                } }) { Text("Add fill") }
            }
            Text("Negative fees represent rebates. Record fills in execution order.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (trade.status == Status.Open) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Field("Signed funding USDT (+ received, − paid)", funding, { funding = it }, Modifier.weight(1f))
                Button(enabled = trade.open.signum() == 0, onClick = { ui.action("Trade closed.") {
                    val signedFunding = decimal(funding, "signed funding")
                    withContext(journalIo) { journal.closeTrade(trade.id, signedFunding) }
                    ui.refresh()
                } }) { Text("Close trade") }
            }
        }
        Field("Notes", notes, { notes = it }, multiline = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { ui.action("Notes saved.") {
                val savedNotes = notes
                withContext(journalIo) { journal.saveNotes(trade.id, savedNotes) }
                ui.refresh()
            } }) { Text("Save notes") }
            if (trade.status == Status.Planned) TextButton(onClick = { cancel = true }) { Text("Cancel plan") }
        }
    }
    if (cancel) AlertDialog(onDismissRequest = { cancel = false }, title = { Text("Cancel this plan?") },
        text = { Text("The plan will stay in history with Cancelled status.") },
        confirmButton = { Button(onClick = {
            cancel = false
            ui.action("Plan cancelled.") {
                withContext(journalIo) { journal.cancel(trade.id) }
                ui.refresh()
            }
        }) { Text("Cancel plan") } },
        dismissButton = { TextButton(onClick = { cancel = false }) { Text("Keep plan") } })
}

@Composable
private fun SettingsScreen(journal: Journal, ui: UiState) {
    val fields = remember(ui.settings, ui.restoreCount) {
        mutableStateMapOf<Setting, String>().apply {
            for (setting in Setting.entries) put(setting, ui.settings?.get(setting)?.display().orEmpty())
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading("Settings")
        Text("Enter your current Bitget account figures and contract limits. Percentage example: 0.06 means 0.06%.")
        for (pair in Setting.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { setting ->
                    Field(setting.label, fields.getValue(setting), { fields[setting] = it }, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Button(onClick = { ui.action("Settings saved.") {
            val settings = Settings.parse(fields)
            withContext(journalIo) { journal.saveSettings(settings) }
            ui.refresh()
        } }) { Text("Save settings") }
        HorizontalDivider()
        Text("Database", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { ui.action {
                chooseFile("Save database backup", "TradingJournal-Backup.sqlite3")?.let {
                    withContext(journalIo) { journal.backup(it) }
                    ui.feedback = "Database backup saved."
                }
            } }) { Text("Back up database") }
            OutlinedButton(onClick = { ui.action {
                chooseFile("Choose database backup")?.let {
                    withContext(journalIo) { journal.validateBackup(it) }
                    ui.backupToRestore = it
                }
            } }) { Text("Restore backup") }
        }
    }
}

private fun chooseFile(title: String, suggested: String? = null): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        if (suggested != null) selectedFile = File(suggested)
    }
    val result = if (suggested != null) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
    if (result != JFileChooser.APPROVE_OPTION) return null
    if (suggested != null && chooser.selectedFile.exists()) {
        val replace = javax.swing.JOptionPane.showConfirmDialog(chooser,
            "Replace ${chooser.selectedFile.name}?", title, javax.swing.JOptionPane.YES_NO_OPTION)
        if (replace != javax.swing.JOptionPane.YES_OPTION) return null
    }
    return chooser.selectedFile.toPath()
}

private fun parseTimestamp(value: String): Instant {
    if (value.isBlank()) return Instant.now()
    val text = value.trim().replace(' ', 'T')
    return runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text).atZone(zone).toInstant() }.getOrNull()
        ?: throw IllegalArgumentException("Enter a valid local time as YYYY-MM-DD HH:MM.")
}

