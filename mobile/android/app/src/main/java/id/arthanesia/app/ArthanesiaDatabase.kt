package id.arthanesia.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Offline-first persistence for the installed app.
 * Cloud identity/sync is added through the authenticated Supabase layer;
 * transaction data must never be seeded with fake demo rows.
 */
internal class ArthanesiaDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "arthanesia_local.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE transactions (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                category TEXT NOT NULL,
                amount INTEGER NOT NULL CHECK(amount > 0),
                income INTEGER NOT NULL CHECK(income IN (0,1)),
                transaction_date TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                currency TEXT NOT NULL DEFAULT 'IDR',
                original_amount REAL NOT NULL DEFAULT 0,
                fx_rate_to_idr REAL NOT NULL DEFAULT 1,
                owner_user_id TEXT NOT NULL DEFAULT ''
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE saving_goals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                current_amount INTEGER NOT NULL DEFAULT 0 CHECK(current_amount >= 0),
                target_amount INTEGER NOT NULL CHECK(target_amount > 0),
                due TEXT NOT NULL,
                owner_user_id TEXT NOT NULL DEFAULT ''
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE app_settings (
                setting_key TEXT PRIMARY KEY,
                setting_value TEXT NOT NULL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN currency TEXT NOT NULL DEFAULT 'IDR'")
            db.execSQL("ALTER TABLE transactions ADD COLUMN original_amount REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE transactions ADD COLUMN fx_rate_to_idr REAL NOT NULL DEFAULT 1")
            db.execSQL("UPDATE transactions SET original_amount = amount WHERE original_amount = 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN owner_user_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE saving_goals ADD COLUMN owner_user_id TEXT NOT NULL DEFAULT ''")
        }
    }

    fun bindUnclaimedDataToFirstAccount(userId: String) {
        if (setting("local_data_claimed") != "true") {
            val values = ContentValues().apply { put("owner_user_id", userId) }
            writableDatabase.update("transactions", values, "owner_user_id = ''", null)
            writableDatabase.update("saving_goals", values, "owner_user_id = ''", null)
            saveSetting("local_data_claimed", "true")
        }
    }

    fun transactions(userId: String? = null): List<MoneyEntry> {
        val result = mutableListOf<MoneyEntry>()
        readableDatabase.query(
            "transactions",
            arrayOf("id", "title", "category", "amount", "income", "transaction_date", "currency", "original_amount", "fx_rate_to_idr"),
            userId?.let { "owner_user_id = ?" }, userId?.let { arrayOf(it) }, null, null, "transaction_date DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MoneyEntry(
                    id = cursor.getLong(0),
                    title = cursor.getString(1),
                    category = cursor.getString(2),
                    amount = cursor.getLong(3),
                    income = cursor.getInt(4) == 1,
                    date = cursor.getString(5),
                    currency = cursor.getString(6),
                    originalAmount = cursor.getDouble(7),
                    fxRateToIdr = cursor.getDouble(8)
                )
            }
        }
        return result
    }

    fun saveTransaction(entry: MoneyEntry, userId: String = "") {
        val values = ContentValues().apply {
            put("id", entry.id)
            put("title", entry.title.trim())
            put("category", entry.category.trim())
            put("amount", entry.amount)
            put("income", if (entry.income) 1 else 0)
            put("transaction_date", entry.date)
            put("updated_at", System.currentTimeMillis())
            put("currency", entry.currency)
            put("original_amount", entry.originalAmount)
            put("fx_rate_to_idr", entry.fxRateToIdr)
            put("owner_user_id", userId)
        }
        writableDatabase.insertWithOnConflict(
            "transactions", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deleteTransaction(id: Long, userId: String? = null) {
        val where = if (userId == null) "id = ?" else "id = ? AND owner_user_id = ?"
        val args = if (userId == null) arrayOf(id.toString()) else arrayOf(id.toString(), userId)
        writableDatabase.delete("transactions", where, args)
    }

    fun goals(userId: String? = null): List<SavingGoal> {
        val result = mutableListOf<SavingGoal>()
        readableDatabase.query(
            "saving_goals",
            arrayOf("name", "current_amount", "target_amount", "due"),
            userId?.let { "owner_user_id = ?" }, userId?.let { arrayOf(it) }, null, null, "id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += SavingGoal(
                    name = cursor.getString(0),
                    current = cursor.getLong(1),
                    target = cursor.getLong(2),
                    due = cursor.getString(3)
                )
            }
        }
        return result
    }

    fun saveGoal(goal: SavingGoal, userId: String = "") {
        val values = ContentValues().apply {
            put("name", goal.name.trim())
            put("current_amount", goal.current)
            put("target_amount", goal.target)
            put("due", goal.due)
            put("owner_user_id", userId)
        }
        writableDatabase.insert("saving_goals", null, values)
    }

    fun setting(key: String, defaultValue: String = ""): String {
        readableDatabase.query(
            "app_settings", arrayOf("setting_value"),
            "setting_key = ?", arrayOf(key), null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else defaultValue
        }
    }

    fun saveSetting(key: String, value: String) {
        val values = ContentValues().apply {
            put("setting_key", key)
            put("setting_value", value)
        }
        writableDatabase.insertWithOnConflict(
            "app_settings", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun hasWorkspace(): Boolean =
        setting("owner").isNotBlank() && setting("business").isNotBlank()

    fun saveWorkspace(owner: String, business: String) {
        saveSetting("owner", owner.trim())
        saveSetting("business", business.trim())
    }
}
