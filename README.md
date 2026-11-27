# Trading Journal

Offline Windows desktop journal for Bitget BTCUSDT USDT-M futures on the five-minute timeframe. Built with Kotlin/JVM, Compose Multiplatform Material 3, and Room with the bundled SQLite driver. The app records manual plans and fills.

## Run and package on Windows

Install a 64-bit JDK 21 and set `JAVA_HOME` to it. The first Gradle run downloads the distribution and dependencies. For MSI packaging, the Compose plugin downloads WiX for the JDK packaging tool. [Compose packaging guide](https://github.com/JetBrains/kotlin-multiplatform-dev-docs/blob/master/topics/compose/compose-native-distribution.md)

```powershell
.\gradlew.bat run
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat packageMsi
```

The MSI is written under `build/compose/binaries/main/msi/`. Use `.\gradlew.bat createDistributable` for a runnable Windows application image with a bundled Java runtime.

## Journal workflow

1. In **Settings**, save your opening journal balance, current futures account equity, available margin, leverage, fee and slippage estimates, and Bitget contract limits. Enter `0.06` for `0.06%`. As a test example only, use 1,000 USDT for each balance, 5× leverage, 0.06% entry and exit fees, 0.02% stop slippage, 0.001 BTC increment and minimum, 5 USDT minimum notional, and 0.1 USDT price tick. Supply your own account figures and contract settings.
2. In **Trade Calculator**, enter direction, entry, and stop. Leave take profit blank to use the fee-adjusted 1:2 target. The calculator floors BTC size to the configured increment under both the fixed 1% equity budget and the available-margin limit. Estimated margin includes initial margin and a reserve for estimated entry and stop fees. Actual stop fills can exceed planned risk.
3. In **Trade History**, open a plan and record fills in execution order. Leave a timestamp blank for the current time. Fees are in USDT and a negative fee represents a rebate. After exiting the full entered quantity, enter signed funding and close the trade. Use positive funding for receipts. Realized R uses the original estimated planned loss as its denominator.
4. On **Dashboard**, record deposits and withdrawals as signed USDT amounts. Realized equity starts from the opening balance and books a closed trade's entire net P&L at its final exit. The cumulative trading P&L series excludes transfers. Points are spaced by event order. Updating sizing equity or available margin affects subsequent plans.

## Database and export

On Windows the database is `%LOCALAPPDATA%\TradingJournal\journal-v2.sqlite3`. Room upgrades the previous Kotlin app's database from schema version 2 to 3 in place on first launch. Restore accepts backups from both Kotlin versions. The Python prototype uses a separate file named `journal.sqlite3`. A separate test data directory can be selected with the JVM system property `tradingjournal.dataDir`.

Use **Settings** to back up the database or restore a validated backup. Restore replaces the current journal after confirmation. Use **Trade History** to export the full trade history and fills to CSV.

