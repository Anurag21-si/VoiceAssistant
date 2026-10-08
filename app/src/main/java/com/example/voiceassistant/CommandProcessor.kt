package com.example.voiceassistant

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The "brain": rule-based, fully local. It understands English and Hindi phrasing at the
 * same time (whatever language is selected), and answers in the selected language.
 * No network, no cloud, no API keys.
 */
class CommandProcessor(private val context: Context) {

    companion object {
        /** Used to build wa.me links when a contact's number has no country code (India = 91). */
        private const val DEFAULT_COUNTRY_CODE = "91"
    }

    /** Optional on-device LLM hook (see SETUP_GUIDE.md, MediaPipe section). */
    var llmHook: ((String) -> String?)? = null

    /** Set by the activity: asks Android for a runtime permission (contacts / camera). */
    var onNeedPermission: ((String) -> Unit)? = null

    private var lang = AppLang.EN
    private var torchOn = false
    private var pendingQuery: String? = null // unrecognised sentence we offered to search for

    private fun t(en: String, hi: String): String = if (lang == AppLang.HI) hi else en

    private val nameRx = Lex.rx(
        "^(?:(?:hey|hi|hello|ok|okay|हे|हेलो|हैलो|ओके)\\s+)?" +
                "(?:sunita|sunitha|sonita|suneeta|सुनीता|सुनिता)(?=[\\s,]|\$),?\\s*"
    )
    private val wakeGreet = Lex.nl("hey", "hi", "hello", "ok", "okay", "हे", "हेलो", "हैलो", "ओके").toSet()
    private val wakeName = Lex.nl("sunita", "sunitha", "sonita", "suneeta", "सुनीता", "सुनिता").toSet()

    /** Removes "Hey Sunita," from the front of the spoken words. */
    private fun withoutName(words: List<Word>): List<Word> = when {
        words.size >= 2 && words[0].n in wakeGreet && words[1].n in wakeName -> words.drop(2)
        words.isNotEmpty() && words[0].n in wakeName -> words.drop(1)
        else -> words
    }

    // =====================================================================
    // Entry point
    // =====================================================================

    fun process(raw: String, language: AppLang): Reply {
        lang = language
        var q = Lex.norm(raw).trim().trimEnd('.', '?', '!', ',', '।')
        val addressed = nameRx.containsMatchIn(q)
        q = q.replace(nameRx, "").trim()
        if (q.isEmpty()) {
            return if (addressed) greeting() else Reply(t("I didn't catch that.", "मुझे समझ नहीं आया।"))
        }
        val tokSet = Lex.tokens(q).toSet()
        val words = withoutName(Lex.words(raw))

        // Follow-up to "shall I search Google or YouTube?"
        followUp(tokSet)?.let { return it }

        // 1) End of conversation
        if (q in Lex.END || q.contains("goodbye")) {
            return Reply(
                t("Goodbye! Say my name whenever you need me.", "अलविदा! ज़रूरत हो तो मेरा नाम पुकारिए।"),
                endConversation = true
            )
        }

        // 2) Greetings
        if (q in Lex.GREETINGS) return greeting()
        if (q in Lex.GOOD_TIME) return Reply(t("Hello! How can I help?", "नमस्ते! मैं आपकी क्या मदद कर सकती हूँ?"))

        // 3) Switch language by voice
        languageSwitch(tokSet)?.let { return it }

        // 4) Timer & alarm
        if (Lex.hasAny(q, Lex.TIMER_W)) return timerReply(q)
        if (Lex.hasAny(q, Lex.ALARM_W) && !Regex("^(?:open|launch)\\s").containsMatchIn(q)) return alarmReply(q)

        // 5) Time & date
        if (Lex.hasAny(q, Lex.TIME_Q)) return timeReply()
        if (Lex.hasAny(q, Lex.DATE_Q)) return dateReply()

        // 6) WhatsApp message
        if (Lex.mentionsApp(q, "whatsapp") && (Lex.tokenAny(tokSet, Lex.WA_VERBS) || q.contains("भेज"))) {
            return whatsappReply(words)
        }

        // 7) Math
        tryMath(q)?.let { return Reply(it) }

        // 8) Volume
        volumeCommand(q)?.let { return it }

        // 9) Flashlight
        if (Lex.hasAny(q, Lex.TORCH_W)) {
            val off = Lex.tokenAny(tokSet, Lex.OFF_W)
            val on = Lex.tokenAny(tokSet, Lex.ON_W)
            return setTorch(if (off) false else if (on) true else !torchOn)
        }

        // 10) Battery
        if (Lex.hasAny(q, Lex.BATTERY_W)) return batteryReply()

        // 11) Calls
        callTarget(q)?.let { return callReply(it) }

        // 12) Wikipedia
        if (Lex.hasAny(q, Lex.WIKI_W)) {
            val topic = Lex.strip(words, Lex.SEARCH_EDGE, Lex.SEARCH_EDGE).joinToString(" ") { it.o }
            if (topic.isNotBlank()) return wikipedia(topic)
        }

        // 13) Search Google / YouTube
        val wantsYoutube = Lex.mentionsApp(q, "youtube")
        val wantsGoogle = tokSet.contains("google") || tokSet.contains(Lex.norm("गूगल"))
        if (wantsYoutube || wantsGoogle || Lex.tokenAny(tokSet, Lex.SEARCH_TRIG)) {
            val query = Lex.strip(words, Lex.SEARCH_EDGE, Lex.SEARCH_EDGE).joinToString(" ") { it.o }
            if (query.isNotBlank()) {
                val youtube = wantsYoutube || tokSet.any { it in Lex.PLAY_W }
                return searchReply(query, youtube)
            }
        }

        // 14) Open an app
        Lex.rx("^(?:open|launch|start|run)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?\$").find(q)?.let {
            return openAppReply(it.groupValues[1])
        }
        Lex.rx("^(?:ओपन|खोलो|खोलिए|खोलिये|लॉन्च)\\s+(.+)\$").find(q)?.let {
            return openAppReply(it.groupValues[1])
        }
        Lex.rx(
            "^(.+?)\\s+(?:को\\s+)?(?:खोलो|खोलिए|खोलिये|खोलें|खोल दो|खोल दीजिए|ओपन करो|ओपन कीजिए|" +
                    "लॉन्च करो|चलाओ|चालू करो|शुरू करो)\$"
        ).find(q)?.let { return openAppReply(it.groupValues[1]) }

        // 15) Small talk
        presetAnswer(q)?.let { return Reply(it) }

        // 16) Optional on-device LLM
        llmHook?.invoke(raw)?.takeIf { it.isNotBlank() }?.let { return Reply(it) }

        // 17) Not understood: offer to search
        return offerSearch(words.joinToString(" ") { it.o })
    }

    private fun greeting(): Reply = Reply(
        t(
            listOf("Hello! I'm Sunita. How can I help?", "Hi there! What can I do for you?").random(),
            listOf("नमस्ते! मैं सुनीता हूँ। मैं आपकी क्या मदद कर सकती हूँ?", "नमस्ते! बताइए, क्या करूँ?").random()
        )
    )

    // =====================================================================
    // Language switching and search follow-up
    // =====================================================================

    private fun languageSwitch(tokSet: Set<String>): Reply? {
        val wantsHindi = Lex.tokenAny(tokSet, Lex.HINDI_NAMES)
        val wantsEnglish = Lex.tokenAny(tokSet, Lex.ENGLISH_NAMES)
        if (wantsHindi == wantsEnglish) return null // neither, or both
        if (!Lex.tokenAny(tokSet, Lex.LANG_SWITCH_W)) return null
        if (Lex.tokenAny(tokSet, Lex.SEARCH_TRIG)) return null // "search Hindi songs"
        return if (wantsHindi) {
            Reply("ठीक है, अब मैं हिंदी में बात करूँगी।", switchTo = AppLang.HI)
        } else {
            Reply("Okay, I'll speak English now.", switchTo = AppLang.EN)
        }
    }

    private fun offerSearch(sentence: String): Reply {
        pendingQuery = sentence
        return Reply(
            t(
                "I'm not sure about that. Shall I search Google or YouTube for “$sentence”? Say Google, YouTube, or no.",
                "मुझे इसके बारे में पक्का नहीं पता। क्या मैं “$sentence” गूगल या यूट्यूब पर खोजूँ? गूगल, यूट्यूब या नहीं बोलिए।"
            )
        )
    }

    private fun followUp(tokSet: Set<String>): Reply? {
        val pq = pendingQuery ?: return null
        pendingQuery = null
        return when {
            Lex.tokenAny(tokSet, Lex.NO) -> Reply(t("Okay.", "ठीक है।"))
            tokSet.any { Lex.canonicalApp(it) == "youtube" } || tokSet.contains("you") && tokSet.contains("tube") ->
                searchReply(pq, youtube = true)
            Lex.tokenAny(tokSet, Lex.YES) -> searchReply(pq, youtube = false)
            else -> null
        }
    }

    // =====================================================================
    // Time, date
    // =====================================================================

    private fun periodHi(h24: Int): String = when (h24) {
        in 4..11 -> "सुबह"
        in 12..15 -> "दोपहर"
        in 16..19 -> "शाम"
        else -> "रात"
    }

    /** "सुबह के 7 बजकर 30 मिनट" */
    private fun clockHi(h24: Int, min: Int): String {
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val body = if (min == 0) "$h12 बजे" else "$h12 बजकर $min मिनट"
        return "${periodHi(h24)} के $body"
    }

    private fun timeReply(): Reply {
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR_OF_DAY)
        val m = now.get(Calendar.MINUTE)
        return if (lang == AppLang.HI) {
            Reply("अभी ${clockHi(h, m)} ${if (m == 0) "हैं" else "हुए हैं"}।")
        } else {
            Reply("It's ${SimpleDateFormat("h:mm a", Locale.US).format(Date())}.")
        }
    }

    private fun dateReply(): Reply = if (lang == AppLang.HI) {
        Reply("आज की तारीख ${SimpleDateFormat("EEEE, d MMMM yyyy", AppLang.HI.locale).format(Date())} है।")
    } else {
        Reply("Today is ${SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())}.")
    }

    // =====================================================================
    // Timer & alarm (AlarmClock intents)
    // =====================================================================

    private fun timerReply(q: String): Reply {
        val s = Lex.numbers(q)
            .replace("half an hour", "30 minutes").replace("half hour", "30 minutes")
            .replace(Lex.norm("आधे घंटे"), "30 मिनट").replace(Lex.norm("आधा घंटा"), "30 मिनट")
            .replace(Lex.norm("डेढ़ घंटे"), "90 मिनट").replace(Lex.norm("डेढ़ घंटा"), "90 मिनट")
            .replace(Regex("\\ban?\\s+(?=hour|minute|second)"), "1 ")
        var total = 0.0
        Lex.rx(
            "(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?|घंटे|घंटा|मिनट|सेकंड|सेकेंड)"
        ).findAll(s).forEach { m ->
            val n = m.groupValues[1].toDouble()
            val unit = m.groupValues[2]
            total += when {
                unit.startsWith("h") || unit.startsWith("घ") -> n * 3600
                unit.startsWith("m") || unit.startsWith("म") -> n * 60
                else -> n
            }
        }
        val seconds = total.roundToInt()
        if (seconds <= 0) {
            return Reply(
                t(
                    "How long should the timer be? For example, say set a timer for 10 minutes.",
                    "टाइमर कितनी देर का लगाऊँ? जैसे, कहिए 10 मिनट का टाइमर लगाओ।"
                )
            )
        }
        val spoken = durationText(seconds)
        return Reply(t("Timer set for $spoken.", "ठीक है, $spoken का टाइमर लगा दिया।")) {
            safeStart(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Sunita")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            )
        }
    }

    private fun durationText(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = mutableListOf<String>()
        if (lang == AppLang.HI) {
            if (h > 0) parts += "$h घंटा"
            if (m > 0) parts += "$m मिनट"
            if (s > 0) parts += "$s सेकंड"
        } else {
            if (h > 0) parts += "$h ${if (h == 1) "hour" else "hours"}"
            if (m > 0) parts += "$m ${if (m == 1) "minute" else "minutes"}"
            if (s > 0) parts += "$s ${if (s == 1) "second" else "seconds"}"
        }
        return parts.joinToString(" ")
    }

    private fun alarmReply(q: String): Reply {
        val s = Lex.numbers(q).replace(Regex("\\b([ap])\\.\\s?m\\.?"), "\$1m")
        val askAgain = Reply(
            t(
                "What time should I set the alarm for? For example, set an alarm for 7 30 am.",
                "अलार्म कितने बजे का लगाऊँ? जैसे, सुबह 7 बजे का अलार्म लगाओ।"
            )
        )

        var h: Int
        var min: Int
        var ap = ""

        val frac = Lex.rx("(साढे|सवा|पौने)\\s*(\\d{1,2})").find(s)
        if (frac != null) {
            // साढ़े सात = 7:30, सवा सात = 7:15, पौने सात = 6:45
            val base = frac.groupValues[2].toInt()
            when (frac.groupValues[1]) {
                Lex.norm("सवा") -> { h = base; min = 15 }
                Lex.norm("पौने") -> { h = if (base == 1) 12 else base - 1; min = 45 }
                else -> { h = base; min = 30 }
            }
        } else {
            val m = Regex("(\\d{1,2})(?:[:.\\s](\\d{2}))?\\s*(am|pm)?").find(s) ?: return askAgain
            h = m.groupValues[1].toInt()
            min = m.groupValues[2].toIntOrNull() ?: 0
            ap = m.groupValues[3]
        }
        if (h > 23 || min > 59) {
            return Reply(t("I didn't understand that alarm time.", "मैं अलार्म का समय समझ नहीं पाई।"))
        }

        if (ap.isEmpty()) {
            ap = when {
                Lex.hasAny(s, Lex.MORNING) -> "am"
                Lex.hasAny(s, Lex.EVENING) -> "pm"
                Lex.hasAny(s, Lex.NIGHT) -> if (h == 12 || h in 1..4) "am" else "pm"
                else -> ""
            }
        }
        val hour24 = when {
            ap == "am" -> if (h == 12) 0 else h
            ap == "pm" -> if (h < 12) h + 12 else h
            h in 1..12 -> nextOccurrenceHour(h, min) // no am/pm said: take the next upcoming one
            else -> h
        }

        val spoken = if (lang == AppLang.HI) {
            "ठीक है, ${clockHi(hour24, min)} का अलार्म लगा दिया।"
        } else {
            val h12 = if (hour24 % 12 == 0) 12 else hour24 % 12
            "Alarm set for ${String.format(Locale.US, "%d:%02d %s", h12, min, if (hour24 < 12) "AM" else "PM")}."
        }
        return Reply(spoken) {
            safeStart(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour24)
                    .putExtra(AlarmClock.EXTRA_MINUTES, min)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Sunita")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            )
        }
    }

    /** "7 o'clock" with no am/pm: whichever of 7 AM / 7 PM comes next. */
    private fun nextOccurrenceHour(h: Int, min: Int): Int {
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val morning = h % 12
        val evening = h % 12 + 12
        return listOf(morning, evening).firstOrNull { it * 60 + min > nowMinutes } ?: morning
    }

    // =====================================================================
    // WhatsApp
    // =====================================================================

    private fun whatsappPackage(): String? =
        listOf("com.whatsapp", "com.whatsapp.w4b")
            .firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

    private fun whatsappReply(words: List<Word>): Reply {
        var idx = -1
        var len = 0
        for (i in words.indices) {
            if (Lex.canonicalApp(words[i].n) == "whatsapp") { idx = i; len = 1; break }
            if (i + 1 < words.size && Lex.canonicalApp(words[i].n + words[i + 1].n) == "whatsapp") {
                idx = i; len = 2; break
            }
        }
        val usage = Reply(
            t(
                "What should the message say? Try: send WhatsApp message hello.",
                "संदेश क्या होगा? कहिए: व्हाट्सएप पर मैसेज भेजो हेलो।"
            )
        )
        if (idx < 0) return usage

        var pre = words.subList(0, idx)
        val post = words.subList(idx + len, words.size)
        var name: String? = null

        // Hindi order: "राहुल को व्हाट्सएप पर ... भेजो"
        if (pre.isNotEmpty() && pre.last().n == Lex.norm("को")) {
            name = pre.dropLast(1).joinToString(" ") { it.o }
            pre = emptyList()
        }

        var rest = Lex.strip(post, Lex.WA_LEAD_STOP, Lex.WA_TRAIL_STOP)

        // English order: "send WhatsApp message to mom saying ..."
        if (name == null && rest.size >= 2 && rest[0].n == "to") {
            var end = -1
            for (j in 1 until rest.size) {
                if (rest[j].n in Lex.WA_DELIM) {
                    name = rest.subList(1, j).joinToString(" ") { it.o }
                    end = j + 1
                    break
                }
                if (rest[j].o.endsWith(":")) {
                    name = rest.subList(1, j + 1).joinToString(" ") { it.o }
                    end = j + 1
                    break
                }
            }
            if (end < 0) { name = rest[1].o; end = 2 }
            rest = Lex.strip(rest.subList(end, rest.size), Lex.WA_LEAD_STOP, Lex.WA_TRAIL_STOP)
        }

        val contactName = name?.trim(':', ',', ' ')?.takeIf { it.isNotEmpty() }
        val msgWords = if (rest.isNotEmpty()) rest else Lex.strip(pre, Lex.WA_LEAD_STOP, Lex.WA_TRAIL_STOP)
        val message = msgWords.joinToString(" ") { it.o }.trim()
        if (message.isEmpty()) return usage

        if (whatsappPackage() == null) {
            return Reply(t("WhatsApp isn't installed on this phone.", "इस फोन में व्हाट्सएप इंस्टॉल नहीं है।"))
        }

        var number: String? = null
        var found: String? = null
        if (contactName != null) {
            if (!hasContactsPermission()) {
                return Reply(
                    t(
                        "I need permission to read your contacts to find $contactName. Please allow it, then ask me again.",
                        "$contactName को ढूँढने के लिए मुझे कॉन्टैक्ट्स की अनुमति चाहिए। कृपया अनुमति दें और फिर से कहें।"
                    )
                ) { onNeedPermission?.invoke(Manifest.permission.READ_CONTACTS) }
            }
            findContact(contactName)?.let {
                found = it.first
                number = waNumber(it.second)
            }
        }

        val finalNumber = number
        val spoken = when {
            contactName == null -> t(
                "Opening WhatsApp with your message. Pick the chat and tap send.",
                "आपके संदेश के साथ व्हाट्सएप खोल रही हूँ। चैट चुनकर सेंड दबाइए।"
            )
            found != null -> t(
                "Opening your WhatsApp chat with $found. Tap send.",
                "$found की व्हाट्सएप चैट खोल रही हूँ। सेंड दबाइए।"
            )
            else -> t(
                "I couldn't find $contactName in your contacts. Pick the chat in WhatsApp and tap send.",
                "मुझे कॉन्टैक्ट्स में $contactName नहीं मिले। व्हाट्सएप में चैट चुनकर सेंड दबाइए।"
            )
        }
        return Reply(spoken) { sendWhatsapp(message, finalNumber) }
    }

    /** Pre-fills WhatsApp. You still tap the send button yourself. */
    private fun sendWhatsapp(message: String, number: String?) {
        val pkg = whatsappPackage()
        if (pkg == null) {
            Toast.makeText(context, "WhatsApp isn't installed", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = if (number != null) {
            Intent(Intent.ACTION_VIEW, "https://wa.me/$number?text=${Uri.encode(message)}".toUri()).setPackage(pkg)
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(pkg)
                putExtra(Intent.EXTRA_TEXT, message)
            }
        }
        safeStart(intent)
    }

    /** wa.me needs digits only, with country code. */
    private fun waNumber(raw: String): String? {
        val plus = raw.trim().startsWith("+")
        var digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        if (!plus) {
            digits = digits.trimStart('0')
            if (digits.length == 10) digits = DEFAULT_COUNTRY_CODE + digits
        }
        return digits
    }

    // =====================================================================
    // Calls and contacts
    // =====================================================================

    private fun callTarget(q: String): String? {
        Regex("^(?:call|dial|phone|ring)\\s+(.+)\$").find(q)?.let { return it.groupValues[1] }
        Lex.rx("^(.+?)\\s+को\\s+(?:फोन|कॉल)(?:\\s+(?:करो|लगाओ|कीजिए|करना))?\$").find(q)?.let { return it.groupValues[1] }
        Lex.rx("^(?:फोन|कॉल)\\s+(?:करो|लगाओ)\\s+(.+)\$").find(q)?.let { return it.groupValues[1] }
        return null
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    private fun callReply(target: String): Reply {
        val tgt = target.trim()
        if (Regex("^[\\d\\s\\-+()]{5,}\$").matches(tgt)) {
            val number = tgt.replace(Regex("[^\\d+]"), "")
            return Reply(
                t("Dialing ${number.toList().joinToString(" ")}.", "${number.toList().joinToString(" ")} डायल कर रही हूँ।")
            ) { dial(number) }
        }
        if (!hasContactsPermission()) {
            return Reply(
                t(
                    "I need permission to read your contacts. Please allow it, then ask me again.",
                    "मुझे कॉन्टैक्ट्स पढ़ने की अनुमति चाहिए। कृपया अनुमति दें और फिर से कहें।"
                )
            ) { onNeedPermission?.invoke(Manifest.permission.READ_CONTACTS) }
        }
        val found = findContact(tgt)
            ?: return Reply(t("I couldn't find $tgt in your contacts.", "मुझे कॉन्टैक्ट्स में $tgt नहीं मिले।"))
        return Reply(t("Calling ${found.first}.", "${found.first} को कॉल कर रही हूँ।")) { dial(found.second) }
    }

    /** Opens the dialer with the number filled in; you press the green call button. */
    private fun dial(number: String) {
        safeStart(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    }

    private fun findContact(name: String): Pair<String, String>? = try {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )?.use { c -> if (c.moveToFirst()) Pair(c.getString(0), c.getString(1)) else null }
    } catch (e: Exception) {
        null
    }

    // =====================================================================
    // Flashlight, battery, volume
    // =====================================================================

    private fun setTorch(on: Boolean): Reply {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Reply(t("This phone doesn't have a flashlight.", "इस फोन में टॉर्च नहीं है।"))
            cm.setTorchMode(id, on)
            torchOn = on
            Reply(if (on) t("Flashlight on.", "टॉर्च चालू कर दी।") else t("Flashlight off.", "टॉर्च बंद कर दी।"))
        } catch (e: SecurityException) {
            Reply(
                t(
                    "I need camera permission to use the flashlight. Please allow it, then ask me again.",
                    "टॉर्च के लिए मुझे कैमरा अनुमति चाहिए। कृपया अनुमति दें और फिर से कहें।"
                )
            ) { onNeedPermission?.invoke(Manifest.permission.CAMERA) }
        } catch (e: Exception) {
            Reply(t("I couldn't control the flashlight.", "मैं टॉर्च नहीं चला पाई।"))
        }
    }

    private fun batteryReply(): Reply {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return Reply(
            t(
                "Your battery is at $pct percent and it is ${if (charging) "charging" else "not charging"}.",
                "आपकी बैटरी $pct प्रतिशत है और ${if (charging) "चार्ज हो रही है" else "चार्जिंग पर नहीं है"}।"
            )
        )
    }

    private fun volumeCommand(q: String): Reply? {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)

        val n = Lex.numbers(q, convertAmbiguous = true)
        val set = Regex("(?:set|change|put)\\s+(?:the\\s+)?volume\\s+(?:to\\s+|at\\s+)?(\\d{1,3})").find(n)
            ?: Lex.rx("(?:आवाज|वॉल्यूम)\\s*(\\d{1,3})").find(n)
        if (set != null) {
            val pct = set.groupValues[1].toInt().coerceIn(0, 100)
            audio.setStreamVolume(stream, (max * pct / 100.0).roundToInt(), 0)
            return Reply(t("Volume set to $pct percent.", "आवाज़ $pct प्रतिशत कर दी।"))
        }
        return when {
            Lex.hasAny(q, Lex.VOL_MAX) -> {
                audio.setStreamVolume(stream, max, 0)
                Reply(t("Volume is at maximum.", "आवाज़ पूरी कर दी।"))
            }
            Lex.hasAny(q, Lex.VOL_UP) -> {
                repeat(2) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0) }
                Reply(t("Louder.", "आवाज़ बढ़ा दी।"))
            }
            Lex.hasAny(q, Lex.VOL_DOWN) -> {
                repeat(2) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0) }
                Reply(t("Quieter.", "आवाज़ कम कर दी।"))
            }
            else -> null
        }
    }

    // =====================================================================
    // Opening apps
    // =====================================================================

    private fun openAppReply(rawName: String): Reply {
        val name = rawName.trim()
        val key = Lex.canonicalApp(name)
        val pm = context.packageManager

        val primary: Intent? = when (key) {
            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "calculator" -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
            "youtube" -> pm.getLaunchIntentForPackage("com.google.android.youtube")
            "whatsapp" -> whatsappPackage()?.let { pm.getLaunchIntentForPackage(it) }
            "chrome" -> pm.getLaunchIntentForPackage("com.android.chrome")
            else -> null
        }
        val byLabel: Intent? = findLaunchIntent(key ?: name)
        val web: Intent? =
            if (key == "youtube") Intent(Intent.ACTION_VIEW, "https://www.youtube.com".toUri()) else null

        val candidates = listOfNotNull(primary, byLabel, web)
        if (candidates.isEmpty()) {
            return Reply(t("I couldn't find an app called $name.", "मुझे $name नाम का ऐप नहीं मिला।"))
        }
        return Reply(t("Opening $name.", "$name खोल रही हूँ।")) {
            var opened = false
            for (c in candidates) {
                if (safeStart(c, toastOnFail = false)) { opened = true; break }
            }
            if (!opened) Toast.makeText(context, "Couldn't open $name", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun findLaunchIntent(name: String): Intent? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(main, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(main, 0)
        }
        val target = Lex.norm(name)
        val match = apps.firstOrNull { Lex.norm(it.loadLabel(pm).toString()) == target }
            ?: apps.firstOrNull { Lex.norm(it.loadLabel(pm).toString()).contains(target) }
        return match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
    }

    // =====================================================================
    // Search
    // =====================================================================

    private fun searchReply(query: String, youtube: Boolean): Reply {
        val q = query.trim()
        return if (youtube) {
            Reply(t("Searching YouTube for $q.", "यूट्यूब पर $q खोज रही हूँ।")) { youtubeSearch(q) }
        } else {
            Reply(t("Searching Google for $q.", "गूगल पर $q खोज रही हूँ।")) { googleSearch(q) }
        }
    }

    private fun googleSearch(q: String) {
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        if (!safeStart(web, toastOnFail = false)) {
            safeStart(Intent(Intent.ACTION_VIEW, ("https://www.google.com/search?q=" + Uri.encode(q)).toUri()))
        }
    }

    private fun youtubeSearch(q: String) {
        val yt = Intent(Intent.ACTION_SEARCH)
            .setPackage("com.google.android.youtube")
            .putExtra("query", q)
        if (safeStart(yt, toastOnFail = false)) return
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, "$q youtube")
        if (safeStart(web, toastOnFail = false)) return
        safeStart(
            Intent(Intent.ACTION_VIEW, ("https://www.youtube.com/results?search_query=" + Uri.encode(q)).toUri())
        )
    }

    private fun wikipedia(topic: String): Reply {
        val host = if (lang == AppLang.HI) "hi" else "en"
        val url = "https://$host.wikipedia.org/wiki/Special:Search?go=Go&search=" + Uri.encode(topic.trim())
        return Reply(
            t("Looking up $topic on Wikipedia.", "विकिपीडिया पर $topic खोज रही हूँ।")
        ) { safeStart(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    /** Starts an activity from outside an Activity; false if nothing can handle it. */
    private fun safeStart(intent: Intent, toastOnFail: Boolean = true): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        if (toastOnFail) Toast.makeText(context, "No app found to do that.", Toast.LENGTH_SHORT).show()
        false
    } catch (e: SecurityException) {
        if (toastOnFail) Toast.makeText(context, "Permission missing for that action.", Toast.LENGTH_SHORT).show()
        false
    }

    // =====================================================================
    // Small talk
    // =====================================================================

    private fun presetAnswer(q: String): String? = when {
        Lex.hasAny(q, Lex.HOW_ARE_YOU) ->
            t("I'm running smoothly, thanks for asking!", "मैं बिल्कुल ठीक हूँ, पूछने के लिए धन्यवाद!")
        Lex.hasAny(q, Lex.NAME_Q) ->
            t("My name is Sunita, your offline voice assistant.", "मेरा नाम सुनीता है, मैं आपकी ऑफलाइन वॉइस असिस्टेंट हूँ।")
        Lex.hasAny(q, Lex.THANKS) -> t("You're welcome!", "आपका स्वागत है!")
        Lex.hasAny(q, Lex.JOKE) -> if (lang == AppLang.HI) {
            listOf(
                "पप्पू ने मोबाइल से पूछा, तुम इतने स्मार्ट क्यों हो? मोबाइल बोला, क्योंकि मैं रोज़ अपडेट होता हूँ।",
                "मरीज़ बोला, डॉक्टर साहब, मुझे भूलने की बीमारी है। डॉक्टर ने पूछा, कब से? मरीज़ बोला, कब से क्या?",
                "टीचर ने पूछा, बताओ, सबसे तेज़ क्या चलता है? बच्चा बोला, मैडम, खर्चा!"
            ).random()
        } else {
            listOf(
                "Why do programmers prefer dark mode? Because light attracts bugs.",
                "I told my phone a joke, but it didn't get the reference. It had no context.",
                "There are 10 kinds of people: those who understand binary and those who don't."
            ).random()
        }
        Lex.hasAny(q, Lex.COIN) ->
            if ((0..1).random() == 0) t("It's heads.", "चित आया।") else t("It's tails.", "पट आया।")
        Lex.hasAny(q, Lex.DICE) -> {
            val n = (1..6).random()
            t("You rolled a $n.", "पासे पर $n आया।")
        }
        Lex.hasAny(q, Lex.HELP) -> t(
            "I can set timers and alarms, send WhatsApp messages, call your contacts, control the flashlight " +
                    "and volume, tell the time, date and battery, do math, open apps, and search Google or YouTube. " +
                    "You can also say speak in Hindi.",
            "मैं टाइमर और अलार्म लगा सकती हूँ, व्हाट्सएप मैसेज भेज सकती हूँ, कॉल कर सकती हूँ, टॉर्च और आवाज़ " +
                    "बदल सकती हूँ, समय, तारीख और बैटरी बता सकती हूँ, हिसाब लगा सकती हूँ, ऐप खोल सकती हूँ, " +
                    "और गूगल या यूट्यूब पर खोज सकती हूँ। आप अंग्रेजी में बोलो भी कह सकते हैं।"
        )
        else -> null
    }

    // =====================================================================
    // Math (English and Hindi operator words)
    // =====================================================================

    private fun tryMath(q: String): String? {
        var e = q
        for (f in Lex.MATH_FILLER) e = e.replace(f, " ")
        e = e.trim()

        Regex("^(\\d+(?:\\.\\d+)?)\\s*(?:percent|%)\\s+of\\s+(\\d+(?:\\.\\d+)?)\$").find(e)?.let {
            val r = it.groupValues[1].toDouble() / 100.0 * it.groupValues[2].toDouble()
            return t(
                "${it.groupValues[1]} percent of ${it.groupValues[2]} is ${format(r)}.",
                "${it.groupValues[2]} का ${it.groupValues[1]} प्रतिशत ${format(r)} है।"
            )
        }

        e = Lex.numbers(e, convertAmbiguous = true)
            .replace(Regex("(?<=\\d),(?=\\d{3})"), "")
            .replace("to the power of", "^")
            .replace("multiplied by", "*")
            .replace("divided by", "/")
            .replace(Regex("\\btimes\\b"), "*")
            .replace(Regex("\\bplus\\b"), "+")
            .replace(Regex("\\bminus\\b"), "-")
            .replace(Regex("\\bover\\b"), "/")
            .replace(Regex("\\bsquared\\b"), "^2")
            .replace(Regex("\\bcubed\\b"), "^3")
            .replace(Lex.rx("(प्लस|जमा|जोडो|जोड)"), "+")
            .replace(Lex.rx("(माइनस|घटाओ|घटा)"), "-")
            .replace(Lex.rx("(गुणा|गुना|टाइम्स)"), "*")
            .replace(Lex.rx("(भाग|बटा|डिवाइड)"), "/")
            .replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), "*")
            .replace("÷", "/").replace("×", "*")
            .replace(" ", "")

        if (!Regex("^[0-9+\\-*/^().]+\$").matches(e)) return null
        if (!e.any { it.isDigit() } || !e.any { it in "+-*/^" }) return null

        return try {
            val result = ExprParser(e).parse()
            if (result.isNaN() || result.isInfinite()) {
                t("That result is too large to say.", "यह संख्या बहुत बड़ी है।")
            } else {
                t("The answer is ${format(result)}.", "जवाब है ${format(result)}।")
            }
        } catch (ex: ArithmeticException) {
            t("I can't divide by zero.", "शून्य से भाग नहीं दिया जा सकता।")
        } catch (ex: Exception) {
            null
        }
    }

    private fun format(d: Double): String =
        if (d == floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString()
        else String.format(Locale.US, "%.6f", d).trimEnd('0').trimEnd('.')

    /** Safe recursive-descent evaluator: + - * / ^ ( ) and unary minus. No eval(). */
    private class ExprParser(private val s: String) {
        private var pos = 0

        fun parse(): Double {
            val v = expr()
            if (pos != s.length) throw IllegalArgumentException("Unexpected input")
            return v
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
        private fun eat(c: Char): Boolean {
            if (peek() == c) { pos++; return true }
            return false
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                v = when {
                    eat('+') -> v + term()
                    eat('-') -> v - term()
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = unary()
            while (true) {
                v = when {
                    eat('*') -> v * unary()
                    eat('/') -> {
                        val d = unary()
                        if (d == 0.0) throw ArithmeticException("Division by zero")
                        v / d
                    }
                    else -> return v
                }
            }
        }

        private fun unary(): Double = when {
            eat('-') -> -unary()
            eat('+') -> unary()
            else -> power()
        }

        private fun power(): Double {
            val base = primary()
            return if (eat('^')) base.pow(unary()) else base
        }

        private fun primary(): Double {
            if (eat('(')) {
                val v = expr()
                if (!eat(')')) throw IllegalArgumentException("Missing )")
                return v
            }
            val start = pos
            while (peek().isDigit() || peek() == '.') pos++
            if (start == pos) throw IllegalArgumentException("Number expected")
            return s.substring(start, pos).toDouble()
        }
    }
}