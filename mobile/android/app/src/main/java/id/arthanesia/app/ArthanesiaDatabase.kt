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
    SQLiteOpenHelper(context.applicationContext, "arthanesia_local.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE transactions (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                category TEXT NOT NULL,
                amount INTEGER NOT NULL CHECK(amount > 0),
                income INTEGER NOT NULL CHECK(income IN (0,1)),
                transaction_date TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE saving_goals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                current_amount INTEGER NOT NULL DEFAULT 0 CHECK(current_amount >= 0),
                target_amount INTEGER NOT NULL CHECK(target_amount > 0),
                due TEXT NOT NULL
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
        // Use explicit, non-destructive migrations for future schema versions.
    }

    fun transactions(): List<MoneyEntry> {
        val result = mutableListOf<MoneyEntry>()
        readableDatabase.query(
            "transactions",
            arrayOf("id", "title", "category", "amount", "income", "transaction_date"),
            null, null, null, null, "transaction_date DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MoneyEntry(
                    id = cursor.getLong(0),
                    title = cursor.getString(1),
                    category = cursor.getString(2),
                    amount = cursor.getLong(3),
                    income = cursor.getInt(4) == 1,
                    date = cursor.getString(5)
                )
            }
        }
        return result
    }

    fun saveTransaction(entry: MoneyEntry) {
        val values = ContentValues().apply {
            put("id", entry.id)
            put("title", entry.title.trim())
            put("category", entry.category.trim())
            put("amount", entry.amount)
            put("income", if (entry.income) 1 else 0)
            put("transaction_date", entry.date)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "transactions", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deleteTransaction(id: Long) {
        writableDatabase.delete("transactions", "id = ?", arrayOf(id.toString()))
    }

    fun goals(): List<SavingGoal> {
        val result = mutableListOf<SavingGoal>()
        readableDatabase.query(
            "saving_goals",
            arrayOf("name", "current_amount", "target_amount", "due"),
            null, null, null, null, "id DESC"
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

    fun saveGoal(goal: SavingGoal) {
        val values = ContentValues().apply {
            put("name", goal.name.trim())
            put("current_amount", goal.current)
            put("target_amount", goal.target)
            put("due", goal.due)
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
