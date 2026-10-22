package com.mumflorence.app

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object Contacts {
    private val digitWords = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "one" to "1", "two" to "2", "three" to "3",
        "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        "plus" to "+"
    )

    /** "zero eight zero three ..." -> "0803..." (the offline recogniser speaks numbers as words). */
    fun spokenDigits(q: String): String? {
        val sb = StringBuilder()
        for (p in q.trim().split(Regex("\\s+"))) {
            val d = digitWords[p] ?: (if (p.matches(Regex("\\+?\\d+"))) p else return null)
            sb.append(d)
        }
        return if (sb.length >= 6) sb.toString() else null
    }

    fun clean(store: Store, number: String): String {
        var n = number.replace(Regex("[^+\\d]"), "")
        val cc = store.get("country_code")
        if (cc.isNotBlank() && n.startsWith("0")) n = cc + n.substring(1)
        return n
    }

    fun all(ctx: Context, store: Store): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    m[name.lowercase()] = num
                }
            }
        } catch (e: SecurityException) {
        }
        for ((k, v) in store.savedContacts()) m[k.lowercase()] = v
        return m
    }

    /** Returns (display name, dialable number) or null. */
    fun find(ctx: Context, store: Store, query: String): Pair<String, String>? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        spokenDigits(q)?.let { return q to clean(store, it) }
        if (q.matches(Regex("\\+?[\\d\\s\\-]{6,}"))) return q to clean(store, q)
        val all = all(ctx, store)
        all[q]?.let { return q to clean(store, it) }
        val partial = all.keys.filter { it.contains(q) || q.contains(it) }.minByOrNull { it.length }
        if (partial != null) return partial to clean(store, all.getValue(partial))
        val close = all.keys.minByOrNull { lev(it, q) }
        if (close != null && lev(close, q) <= maxOf(2, q.length / 4)) return close to clean(store, all.getValue(close))
        return null
    }

    fun nameFor(ctx: Context, number: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
