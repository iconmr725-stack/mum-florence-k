package com.mumflorence.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Settings (SharedPreferences) + Mum Florence's own database (SQLite). */
class Store(ctx: Context) : SQLiteOpenHelper(ctx, "florence.db", null, 1) {
    private val prefs = ctx.getSharedPreferences("florence", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE taught(phrase TEXT PRIMARY KEY, reply TEXT)")
        db.execSQL("CREATE TABLE facts(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE contacts(name TEXT PRIMARY KEY, number TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun get(k: String, d: String = ""): String = prefs.getString(k, d) ?: d
    fun put(k: String, v: String) { prefs.edit().putString(k, v).apply() }

    fun teach(phrase: String, reply: String) =
        writableDatabase.execSQL("INSERT OR REPLACE INTO taught VALUES(?,?)", arrayOf<Any>(phrase, reply))

    fun taught(phrase: String): String? = one("SELECT reply FROM taught WHERE phrase=?", phrase)

    fun setFact(k: String, v: String) =
        writableDatabase.execSQL("INSERT OR REPLACE INTO facts VALUES(?,?)", arrayOf<Any>(k, v))

    fun fact(k: String): String? = one("SELECT v FROM facts WHERE k=?", k)

    fun saveContact(name: String, number: String) =
        writableDatabase.execSQL("INSERT OR REPLACE INTO contacts VALUES(?,?)", arrayOf<Any>(name, number))

    fun savedContacts(): Map<String, String> {
        val m = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT name, number FROM contacts", null).use {
            while (it.moveToNext()) m[it.getString(0)] = it.getString(1)
        }
        return m
    }

    private fun one(sql: String, arg: String): String? =
        readableDatabase.rawQuery(sql, arrayOf(arg)).use { if (it.moveToFirst()) it.getString(0) else null }
}
