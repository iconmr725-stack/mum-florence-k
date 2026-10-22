package com.mumflorence.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.widget.Toast
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.Random
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Mum Florence. Runs all the time as a foreground service, listens OFFLINE for her name,
 * then listens for your command, and answers by voice (or text).
 */
class FlorenceService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile var instance: FlorenceService? = null
        private const val WAKE_GRAMMAR = "[\"mum florence\", \"mom florence\", \"mummy florence\", \"florence\", \"[unk]\"]"
    }

    private lateinit var store: Store
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newCachedThreadPool()

    private var model: Model? = null
    private var speech: SpeechService? = null
    private var rec: Recognizer? = null

    private var busy = false                       // true while she is speaking / in a conversation
    private var next: ((String) -> Unit)? = null   // one-shot handler for the next thing you say
    private val callbacks = HashMap<String, () -> Unit>()
    private val queue = LinkedList<String>()       // announcements waiting for her turn to speak
    private val seen = LinkedHashSet<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringing = false
    private var ringNumber: String? = null

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("florence", "Mum Florence is listening", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("florence_text", "Mum Florence answers", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "florence")
            .setContentTitle("Mum Florence is listening")
            .setContentText("Say \"Mum Florence\" whenever you need me")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "florence:listen")
            .apply { setReferenceCounted(false); acquire() }

        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(phoneReceiver, f)

        tts = TextToSpeech(this, this)
        bg.execute { prepareModel() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        stopListening()
        try { unregisterReceiver(phoneReceiver) } catch (e: Exception) {}
        try { tts.shutdown() } catch (e: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ voice out (female voice)
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val locale = Locale.getDefault().let { if (tts.isLanguageAvailable(it) >= 0) it else Locale.US }
        tts.language = locale
        try {
            val hints = listOf("female", "-tpf-", "-sfg-", "-tpc-", "-gba-", "-afc-")
            tts.voices?.filter { it.locale.language == locale.language }
                ?.firstOrNull { v -> val n = v.name.lowercase(); !v.isNetworkConnectionRequired && hints.any { n.contains(it) } }
                ?.let { tts.voice = it }
        } catch (e: Exception) {}
        tts.setPitch(1.15f)
        tts.setSpeechRate(0.95f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { callbacks.remove(utteranceId)?.invoke() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { callbacks.remove(utteranceId)?.invoke() } }
        })
        ttsReady = true
    }

    private fun say(text: String, then: (() -> Unit)? = null) {
        if (!ttsReady) { if (then != null) main.postDelayed(then, 500); return }
        val id = UUID.randomUUID().toString()
        if (then != null) {
            callbacks[id] = then
            // safety net in case the speech engine never reports back
            main.postDelayed({ callbacks.remove(id)?.invoke() }, 6000L + text.length * 110L)
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun showText(t: String) {
        val n = Notification.Builder(this, "florence_text")
            .setContentTitle("Mum Florence")
            .setContentText(t)
            .setStyle(Notification.BigTextStyle().bigText(t))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(Random().nextInt(100000) + 10, n)
        Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    }

    private fun reply(text: String, asText: Boolean = false) {
        if (asText) { showText(text); finish() } else { busy = true; say(text) { finish() } }
    }

    /** Back to idle: say anything that was waiting, otherwise go back to listening for her name. */
    private fun finish() {
        next = null
        val q = queue.poll()
        if (q != null) { busy = true; stopListening(); say(q) { finish() }; return }
        busy = false
        startWake()
    }

    private fun announce(text: String) {
        if (busy) { queue.add(text); return }
        busy = true
        stopListening()
        say(text) { finish() }
    }

    private fun ask(question: String, onAnswer: (String) -> Unit) {
        busy = true
        stopListening()
        say(question) { next = onAnswer; startCommand() }
    }

    // ------------------------------------------------------------------ voice in (offline, Vosk)
    private fun prepareModel() {
        try {
            val dir = File(filesDir, "model")
            if (!File(dir, "am/final.mdl").exists()) copyAssets("model", dir)
            model = Model(dir.absolutePath)
            main.post { say("Hello. Mum Florence is ready. Just say my name when you need me."); startWake() }
        } catch (e: Throwable) {
            main.post { say("Sorry, I could not load my listening brain.") }
        }
    }

    private fun copyAssets(path: String, dest: File) {
        val list = assets.list(path) ?: return
        if (list.isEmpty()) {
            try {
                dest.parentFile?.mkdirs()
                assets.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            } catch (e: Exception) {}
            return
        }
        dest.mkdirs()
        for (n in list) copyAssets("$path/$n", File(dest, n))
    }

    private fun stopListening() {
        try { speech?.stop() } catch (e: Exception) {}
        speech = null
        try { rec?.close() } catch (e: Exception) {}
        rec = null
    }

    private fun startWake() {
        val m = model ?: return
        stopListening()
        try {
            val r = Recognizer(m, 16000f, WAKE_GRAMMAR)
            rec = r
            speech = SpeechService(r, 16000f).also { it.startListening(wakeListener) }
        } catch (e: Exception) {
            main.postDelayed({ if (!busy) startWake() }, 3000)
        }
    }

    private fun startCommand() {
        val m = model ?: return
        stopListening()
        try {
            val r = Recognizer(m, 16000f)
            rec = r
            speech = SpeechService(r, 16000f).also { it.startListening(cmdListener, 9000) }
        } catch (e: Exception) {
            finish()
        }
    }

    private val wakeListener = object : RecognitionListener {
        override fun onPartialResult(h: String?) = check(h)
        override fun onResult(h: String?) = check(h)
        override fun onFinalResult(h: String?) {}
        override fun onError(e: Exception?) { main.postDelayed({ if (!busy) startWake() }, 3000) }
        override fun onTimeout() { if (!busy) startWake() }
        private fun check(h: String?) {
            if (h != null && !busy && h.contains("florence")) onWake()
        }
    }

    private val cmdListener = object : RecognitionListener {
        override fun onPartialResult(h: String?) {}
        override fun onResult(h: String?) {
            val t = try { JSONObject(h ?: "").optString("text") } catch (e: Exception) { "" }
            if (t.isBlank()) return
            stopListening()
            val n = next
            next = null
            if (n != null) n(t) else handle(t)
        }
        override fun onFinalResult(h: String?) {}
        override fun onError(e: Exception?) { stopListening(); say("Sorry, something went wrong.") { finish() } }
        override fun onTimeout() {
            stopListening()
            val n = next
            next = null
            if (n != null) n("") else say("I didn't hear anything.") { finish() }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "florence:screen")
                .acquire(20000)
        } catch (e: Exception) {}
    }

    private fun onWake() {
        busy = true
        stopListening()
        wakeScreen()
        say("Yes, how can I help you?") { startCommand() }
    }

    // ------------------------------------------------------------------ things that happen to the phone
    fun onNotification(pkg: String, title: String, text: String, isCall: Boolean) {
        main.post {
            if (isCall) {
                ask("WhatsApp call from $title. Should I answer?") { a ->
                    if (yes(a)) answerCall() else if (no(a)) declineCall()
                    finish()
                }
                return@post
            }
            if (!seen.add("$pkg|$title|$text")) return@post
            while (seen.size > 200) seen.remove(seen.first())
            val app = pkg.substringAfterLast('.')
            if (store.get("announce", "on") == "on") announce("New $app notification from $title. $text")
            if (store.get("autoreply") == "on" && pkg in FlorenceNotificationListener.WHATSAPP &&
                Replies.find(title) != null && Contacts.all(this, store).containsKey(title.lowercase())
            ) {
                bg.execute {
                    val msg = draft(title, text)
                    main.post { Replies.send(this, title, msg) }
                }
            }
        }
    }

    fun onSms(from: String, body: String) {
        main.post {
            val name = Contacts.nameFor(this, from)
            if (store.get("announce", "on") == "on") announce("New text message from ${name ?: from}. $body")
            if (store.get("autoreply") == "on" && name != null) {
                bg.execute {
                    val msg = draft(name, body)
                    main.post { sendSms(Contacts.clean(store, from), msg) }
                }
            }
        }
    }

    private val phoneReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            val state = i.getStringExtra(TelephonyManager.EXTRA_STATE)
            i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.let { ringNumber = it }
            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                if (!ringing) {
                    ringing = true
                    main.postDelayed({
                        val who = ringNumber?.let { Contacts.nameFor(this@FlorenceService, it) ?: it } ?: "Someone"
                        ask("$who is calling. Should I answer?") { a ->
                            if (yes(a)) answerCall() else if (no(a)) declineCall()
                            finish()
                        }
                    }, 1200)
                }
            } else {
                ringing = false
                ringNumber = null
            }
        }
    }

    private fun draft(sender: String, text: String): String {
        val prompt = "$sender sent me this message: \"$text\". Write a short, friendly reply from me. Reply with the message text only."
        return Brain.ask(this, store, prompt, "You write short replies on behalf of the user.", false)
            ?: store.get("away", "Sorry, I can't talk right now. I will get back to you soon.")
    }

    // ------------------------------------------------------------------ actions
    private fun yes(a: String) = Regex("\\b(yes|yeah|yep|yup|sure|ok|okay|send|go ahead|answer|do it|please)\\b").containsMatchIn(a)
    private fun no(a: String) = Regex("\\b(no|nope|decline|reject|cancel|don't|dont|hang)\\b").containsMatchIn(a)

    private fun answerCall() {
        try { getSystemService(TelecomManager::class.java).acceptRingingCall() } catch (e: Exception) {}
        try { Replies.callAnswer?.actionIntent?.send() } catch (e: Exception) {}
    }

    private fun declineCall() {
        try { if (Build.VERSION.SDK_INT >= 28) getSystemService(TelecomManager::class.java).endCall() } catch (e: Exception) {}
        try { Replies.callDecline?.actionIntent?.send() } catch (e: Exception) {}
    }

    private fun sendSms(number: String, msg: String) {
        val sm = getSystemService(SmsManager::class.java)
        sm.sendMultipartTextMessage(number, null, sm.divideMessage(msg), null, null)
    }

    private fun appIntent(name: String): Intent? {
        val pm = packageManager
        val q = name.lowercase().trim()
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
        return hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
    }

    private fun openApp(name: String): Boolean {
        val i = appIntent(name) ?: return false
        return try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    }

    private fun confirm(question: String, onYes: () -> Unit) {
        if (store.get("confirm_send", "on") == "off") { onYes(); return }
        ask(question) { a -> if (yes(a)) onYes() else reply("Okay, I did not send it.") }
    }

    private fun offlineAnswer(c: String): String? = when {
        Regex("\\bwhat('s| is)? the time\\b|\\bwhat time\\b").containsMatchIn(c) ->
            "It is " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        Regex("\\bwhat('s| is)? (the )?(date|day)( today)?\\b|\\btoday's date\\b").containsMatchIn(c) ->
            "Today is " + DateFormat.getDateInstance(DateFormat.FULL).format(Date())
        c.contains("battery") ->
            "Your battery is at " + getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent."
        else -> null
    }

    // ------------------------------------------------------------------ the command brain
    private fun handle(raw: String) {
        val c0 = raw.trim().lowercase().trim('.', ',', '!', '?')
        val asText = Regex("\\b(in text|as text|show me|write it)\\b").containsMatchIn(c0) || store.get("text_mode") == "on"
        val c = c0.replace(Regex("\\b(in text|as text|write it)\\b"), "").trim()
        fun out(t: String) = reply(t, asText)

        if (Regex("^(goodbye|bye|go to sleep|stop listening)$").matches(c)) { out("Goodbye. Say my name whenever you need me."); return }
        if (c == "shut down") { say("Shutting down. Open the app to wake me again.") { stopSelf() }; return }

        Regex("^(auto ?reply|text mode|announce notifications) (on|off)$").find(c)?.let { m ->
            val key = when (m.groupValues[1].replace(" ", "")) { "autoreply" -> "autoreply"; "textmode" -> "text_mode"; else -> "announce" }
            store.put(key, m.groupValues[2]); out("Okay, ${m.groupValues[1]} is ${m.groupValues[2]}."); return
        }
        if (c.contains("stop asking before sending")) { store.put("confirm_send", "off"); out("Okay, I will send without asking."); return }
        if (c.contains("ask before sending")) { store.put("confirm_send", "on"); out("Okay, I will ask before sending."); return }

        // ---- WhatsApp / SMS / calls
        Regex("^(?:whatsapp|send (?:a )?whatsapp(?: message)? to)\\s+(.+?)\\s+(?:saying|that says|say|that)\\s+(.+)$").find(c)?.let { m ->
            val who = m.groupValues[1]; val msg = m.groupValues[2]
            if (Replies.find(who) != null) {
                confirm("I will reply to $who on WhatsApp: $msg. Should I send it?") {
                    if (Replies.send(this, who, msg)) out("Done. I replied to $who.") else out("Sorry, I could not send it.")
                }
                return
            }
            val hit = Contacts.find(this, store, who)
            if (hit == null) { out("I couldn't find $who in your contacts."); return }
            confirm("I will WhatsApp ${hit.first}: $msg. Should I send it?") {
                val link = Uri.parse("https://wa.me/${hit.second.removePrefix("+")}?text=${Uri.encode(msg)}")
                try { startActivity(Intent(Intent.ACTION_VIEW, link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                main.postDelayed({
                    if (FlorenceAccessibility.click("Send")) out("Done. I sent your WhatsApp to ${hit.first}.")
                    else out("I opened the chat with ${hit.first}. Please tap send.")
                }, 4500)
            }
            return
        }
        Regex("^(?:send (?:a )?(?:text|sms|message)(?: message)? to|text|sms|message|tell)\\s+(.+?)\\s+(?:saying|that says|say|that)\\s+(.+)$").find(c)?.let { m ->
            val hit = Contacts.find(this, store, m.groupValues[1])
            if (hit == null) { out("I couldn't find ${m.groupValues[1]} in your contacts."); return }
            confirm("I will text ${hit.first}: ${m.groupValues[2]}. Should I send it?") {
                try { sendSms(hit.second, m.groupValues[2]); out("Done. I sent your message to ${hit.first}.") }
                catch (e: Exception) { out("Sorry, I could not send the message.") }
            }
            return
        }
        Regex("^(?:call|phone|ring|dial)\\s+(.+)$").find(c)?.let { m ->
            val hit = Contacts.find(this, store, m.groupValues[1])
            if (hit == null) { out("I couldn't find ${m.groupValues[1]} in your contacts."); return }
            try {
                getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", hit.second, null), Bundle())
                out("Calling ${hit.first}.")
            } catch (e: Exception) { out("Sorry, I could not place the call.") }
            return
        }
        if (Regex("\\b(answer|pick up|accept)\\b.*\\bcall\\b|^answer$").containsMatchIn(c)) { answerCall(); out("Answering the call."); return }
        if (Regex("\\b(reject|decline|end|hang up|cut)\\b.*\\bcall\\b|^hang up$").containsMatchIn(c)) { declineCall(); out("Done."); return }

        // ---- notifications, apps, screen
        if (Regex("read (my )?notifications?").containsMatchIn(c)) {
            val l = FlorenceNotificationListener.instance
            if (l == null) { out("Please give me notification access in the app first."); return }
            val items = l.activeNotifications.filter { n -> FlorenceNotificationListener.SKIP.none { n.packageName.startsWith(it) } && n.packageName != packageName }
            if (items.isEmpty()) { out("You have no new notifications."); return }
            val lines = items.take(8).map { n ->
                val e = n.notification.extras
                "${n.packageName.substringAfterLast('.')}. ${e.getCharSequence(Notification.EXTRA_TITLE) ?: ""}. ${e.getCharSequence(Notification.EXTRA_TEXT) ?: ""}"
            }
            val all = "You have ${items.size} notifications. " + lines.joinToString(" Next. ")
            out(all); return
        }
        Regex("^open\\s+(.+)$").find(c)?.let { m ->
            out(if (openApp(m.groupValues[1])) "Opening ${m.groupValues[1]}." else "I couldn't find an app called ${m.groupValues[1]}."); return
        }
        Regex("^read (?:my |the )?(.+?)(?: for me)?$").find(c)?.let { m ->
            val target = m.groupValues[1]
            val doRead = {
                val t = FlorenceAccessibility.readScreen()
                out(if (t.isBlank()) "I couldn't read anything. Please turn on screen reading for me in the app." else t)
            }
            if (Regex("screen|this|page").containsMatchIn(target)) { doRead(); return }
            if (!openApp(target)) { out("I couldn't find $target."); return }
            busy = true
            main.postDelayed({ doRead() }, 3500)
            return
        }

        // ---- training and memory
        Regex("^(?:when i say|if i say)\\s+(.+?),?\\s+(?:you say|say|reply|answer)\\s+(.+)$").find(c)?.let { m ->
            store.teach(m.groupValues[1], m.groupValues[2]); out("Learned. I will remember that."); return
        }
        Regex("^save contact\\s+(.+?)\\s+(?:as|number|is)\\s+(.+)$").find(c)?.let { m ->
            val num = Contacts.spokenDigits(m.groupValues[2])
            if (num == null) { out("I didn't catch the number. Please try again."); return }
            store.saveContact(m.groupValues[1], num); out("Saved ${m.groupValues[1]}."); return
        }
        Regex("^remember (?:that )?my (.+?) is (.+)$").find(c)?.let { m ->
            store.setFact(m.groupValues[1], m.groupValues[2]); out("Got it. I will remember."); return
        }
        Regex("^what(?:'s| is) my (.+)$").find(c)?.let { m ->
            store.fact(m.groupValues[1])?.let { out("Your ${m.groupValues[1]} is $it."); return }
        }
        Regex("^(?:check|ask|search) (?:my )?database (?:for |about )?(.+)$").find(c)?.let { m ->
            val url = store.get("db_url")
            if (url.isBlank()) { out("Please add your database address in the app first."); return }
            busy = true
            bg.execute { val a = Brain.askDatabase(url, m.groupValues[1]); main.post { out(a ?: "I couldn't reach your database.") } }
            return
        }
        store.taught(c)?.let { out(it); return }

        // ---- general questions: built-in answers first, then Claude (online)
        offlineAnswer(c)?.let { out(it); return }
        busy = true
        bg.execute {
            val ans = Brain.ask(this, store, c)
            main.post {
                out(
                    ans ?: when {
                        store.get("api_key").isBlank() -> "I can help with your phone, calls and messages. To answer any question, please add my key in the app."
                        !Brain.online(this) -> "I'm offline right now, so I can only help with your phone, calls, messages and things you taught me."
                        else -> "Sorry, I couldn't think of an answer."
                    }
                )
            }
        }
    }
}
