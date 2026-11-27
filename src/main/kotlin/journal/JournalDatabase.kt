package journal

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal const val LEGACY_JOURNAL_VERSION = 2
internal const val JOURNAL_VERSION = 3

internal val journalColumns = linkedMapOf(
    "settings" to "id,data",
    "trades" to "id,created,status,direction,entry,stop,target,minimum_target,quantity,budget,risk,reward,margin,entry_fee,stop_fee,target_fee,assumptions,notes,closed,pnl,funding",
    "fills" to "id,trade_id,time,kind,price,quantity,fee",
    "transfers" to "id,time,amount"
)

@Entity(tableName = "settings")
internal data class SettingsRow(
    @PrimaryKey val id: Long,
    val data: String
)

@Entity(tableName = "trades")
internal data class TradeRow(
    @PrimaryKey val id: Long,
    val created: String,
    val status: String,
    val direction: String,
    val entry: String,
    val stop: String,
    val target: String,
    @ColumnInfo(name = "minimum_target") val minimumTarget: String,
    val quantity: String,
    val budget: String,
    val risk: String,
    val reward: String,
    val margin: String,
    @ColumnInfo(name = "entry_fee") val entryFee: String,
    @ColumnInfo(name = "stop_fee") val stopFee: String,
    @ColumnInfo(name = "target_fee") val targetFee: String,
    val assumptions: String,
    val notes: String,
    val closed: String?,
    val pnl: String?,
    val funding: String?
)

@Entity(
    tableName = "fills",
    foreignKeys = [ForeignKey(entity = TradeRow::class, parentColumns = ["id"], childColumns = ["trade_id"])],
    indices = [Index(value = ["trade_id"], name = "fills_trade")]
)
internal data class FillRow(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "trade_id") val tradeId: Long,
    val time: String,
    val kind: String,
    val price: String,
    val quantity: String,
    val fee: String
)

@Entity(tableName = "transfers")
internal data class TransferRow(
    @PrimaryKey val id: Long,
    val time: String,
    val amount: String
)

@Dao
internal interface JournalDao {
    @Query("SELECT data FROM settings WHERE id = 1")
    suspend fun settings(): String?

    @Query("INSERT OR REPLACE INTO settings (id, data) VALUES (1, :data)")
    suspend fun saveSettings(data: String)

    @Query("UPDATE trades SET notes = :notes WHERE id = :id")
    suspend fun saveNotes(id: Long, notes: String): Int

    @Query("UPDATE trades SET status = 'Cancelled' WHERE id = :id AND status = 'Planned'")
    suspend fun cancel(id: Long): Int
}

@Database(
    entities = [SettingsRow::class, TradeRow::class, FillRow::class, TransferRow::class],
    version = JOURNAL_VERSION
)
internal abstract class JournalDatabase : RoomDatabase() {
    abstract fun journalDao(): JournalDao
}

internal val migrateJournalToRoom = object : Migration(LEGACY_JOURNAL_VERSION, JOURNAL_VERSION) {
    override fun migrate(connection: SQLiteConnection) {
        // Room expects explicitly non-null primary keys. Copy records inside Room's migration transaction.
        for (table in journalColumns.keys) {
            connection.execSQL("ALTER TABLE $table RENAME TO legacy_$table")
        }
        connection.execSQL("DROP INDEX fills_trade")
        connection.execSQL("""
            CREATE TABLE settings (
                id INTEGER NOT NULL PRIMARY KEY, data TEXT NOT NULL
            )
        """.trimIndent())
        connection.execSQL("""
            CREATE TABLE trades (
                id INTEGER NOT NULL PRIMARY KEY, created TEXT NOT NULL, status TEXT NOT NULL,
                direction TEXT NOT NULL, entry TEXT NOT NULL, stop TEXT NOT NULL, target TEXT NOT NULL,
                minimum_target TEXT NOT NULL, quantity TEXT NOT NULL, budget TEXT NOT NULL,
                risk TEXT NOT NULL, reward TEXT NOT NULL, margin TEXT NOT NULL, entry_fee TEXT NOT NULL,
                stop_fee TEXT NOT NULL, target_fee TEXT NOT NULL, assumptions TEXT NOT NULL,
                notes TEXT NOT NULL, closed TEXT, pnl TEXT, funding TEXT
            )
        """.trimIndent())
        connection.execSQL("""
            CREATE TABLE fills (
                id INTEGER NOT NULL PRIMARY KEY, trade_id INTEGER NOT NULL, time TEXT NOT NULL,
                kind TEXT NOT NULL, price TEXT NOT NULL, quantity TEXT NOT NULL, fee TEXT NOT NULL,
                FOREIGN KEY(trade_id) REFERENCES trades(id)
            )
        """.trimIndent())
        connection.execSQL("""
            CREATE TABLE transfers (
                id INTEGER NOT NULL PRIMARY KEY, time TEXT NOT NULL, amount TEXT NOT NULL
            )
        """.trimIndent())
        connection.execSQL("CREATE INDEX fills_trade ON fills(trade_id)")
        for ((table, columns) in journalColumns) {
            connection.execSQL("INSERT INTO $table ($columns) SELECT $columns FROM legacy_$table")
        }
        for (table in journalColumns.keys.reversed()) {
            connection.execSQL("DROP TABLE legacy_$table")
        }
    }
}
