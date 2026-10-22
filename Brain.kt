package com.mumflorence.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Online brain (Claude). Returns null whenever offline or anything goes wrong. */
object Brain {
    const val MODEL = "claude-sonnet-5-5"
    const val SYSTEM = "You are Mum Florence, a warm, caring personal voice assistant on the user's phone. " +
        "Answer clearly in one to three short spoken sentences unless asked for more. " +
        "No markdown, no emojis, no lists."

    private val history = ArrayList<Pair<String, String>>()

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Blocking: call from a background thread. */
    fun ask(ctx: Context, store: Store, q: String, system: String = SYSTEM, keep: Boolean = true): String? {
        val key = store.get("api_key").trim()
        if (key.isEmpty() || !online(ctx)) return null
        return try {
            val msgs = JSONArray()
            if (keep) for ((r, c) in history) msgs.put(JSONObject().put("role", r).put("content", c))
            msgs.put(JSONObject().put("role", "user").put("content", q))
            val body = JSONObject().put("model", MODEL).put("max_tokens", 400)
                .put("system", system).put("messages", msgs)
            val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val resp = conn.inputStream.bufferedReader().readText()
            val content = JSONObject(resp).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            val text = sb.toString().trim()
            if (keep && text.isNotEmpty()) {
                history.add("user" to q)
                history.add("assistant" to text)
                while (history.size > 12) history.removeAt(0)
            }
            text.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Optional: your own database/API. We call  URL?q=<question>  and read the answer. */
    fun askDatabase(url: String, q: String): String? = try {
        val sep = if (url.contains("?")) "&" else "?"
        val conn = URL(url + sep + "q=" + java.net.URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.inputStream.bufferedReader().readText().take(500)
    } catch (e: Exception) {
        null
    }
}
