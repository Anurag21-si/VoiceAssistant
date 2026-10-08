package com.example.voiceassistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow
import androidx.core.net.toUri
import android.annotation.SuppressLint

/**
 * Offline-first voice assistant:
 *   Mic -> SpeechRecognizer -> CommandProcessor (local rules) -> TextToSpeech
 * No third-party cloud APIs, no keys, no paid services.
 */
@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ---- Views ----
    private lateinit var btnMic: ImageButton
    private lateinit var pulse: View
    private lateinit var levelRing: View
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvResponse: TextView

    // ---- Speech engines ----
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // ---- State ----
    private var isListening = false
    private var busyRetries = 0
    private var pendingAction: (() -> Unit)? = null // e.g. open an app AFTER the reply is spoken
    private var pulseAnimator: ObjectAnimator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var processor: CommandProcessor

    // ---- Runtime permission launcher ----
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else onPermissionDenied()
        }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnMic = findViewById(R.id.btnMic)
        pulse = findViewById(R.id.pulse)
        levelRing = findViewById(R.id.levelRing)
        tvStatus = findViewById(R.id.tvStatus)
        tvTranscript = findViewById(R.id.tvTranscript)
        tvResponse = findViewById(R.id.tvResponse)

        processor = CommandProcessor(this)
        tts = TextToSpeech(this, this) // onInit() is called when the engine is ready

        btnMic.setOnClickListener {
            if (isListening) stopListening() else ensurePermissionThenListen()
        }
    }

    override fun onStop() {
        super.onStop()
        // Release the mic whenever we leave the screen.
        recognizer?.cancel()
        setListeningUi(false)
        tts?.stop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        pulseAnimator?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // =====================================================================
    // Permissions
    // =====================================================================

    private fun ensurePermissionThenListen() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) startListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun onPermissionDenied() {
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // "Don't ask again" was chosen (or policy blocks it): only Settings can fix it.
            AlertDialog.Builder(this)
                .setTitle("Microphone permission needed")
                .setMessage("Voice commands need microphone access. You can enable it in the app settings.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", packageName, null))
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            tvStatus.text = "Microphone permission is required to listen."
        }
    }

    // =====================================================================
    // SpeechRecognizer
    // =====================================================================

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            report("Speech recognition isn't available on this device. Install or enable the Google app.")
            return
        }
        tts?.stop() // don't let the assistant hear itself
        pendingAction = null

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(listener)
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Use the on-device model when the language pack is installed (falls back otherwise).
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        try {
            recognizer?.startListening(intent)
            setListeningUi(true)
        } catch (_: Exception) {
            resetRecognizer()
            report("Couldn't start the microphone. Please try again.")
        }
    }

    private fun stopListening() {
        recognizer?.stopListening() // finishes and delivers results
    }

    private fun resetRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            tvStatus.text = "Listening…"
        }

        override fun onBeginningOfSpeech() {
            tvStatus.text = "Hearing you…"
        }

        override fun onRmsChanged(rmsdB: Float) {
            // rmsdB is roughly -2..10; map it to a ring scale so it reacts to your voice.
            val level = (rmsdB.coerceIn(0f, 10f)) / 10f
            val scale = 1f + level * 0.7f
            levelRing.animate().scaleX(scale).scaleY(scale).setDuration(80).start()
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            tvStatus.text = "Thinking…"
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!partial.isNullOrBlank()) tvTranscript.text = "“$partial…”"
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            setListeningUi(false)
            busyRetries = 0
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text.isNullOrBlank()) report("I didn't catch that. Please try again.") else handleCommand(text)
        }

        override fun onError(error: Int) {
            setListeningUi(false)
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Audio hardware / service still held by a previous session: reset and retry once.
                    resetRecognizer()
                    if (busyRetries++ < 1) {
                        tvStatus.text = "Speech service busy, retrying…"
                        mainHandler.postDelayed({ startListening() }, 500)
                    } else {
                        busyRetries = 0
                        report("The speech service is busy. Please wait a moment and try again.")
                    }
                }
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    report("I didn't hear anything. Tap the mic and try again.")
                SpeechRecognizer.ERROR_NO_MATCH ->
                    report("Sorry, I couldn't understand that.")
                SpeechRecognizer.ERROR_AUDIO ->
                    report("There was a problem with the microphone. Is another app using it?")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    onPermissionDenied()
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER ->
                    report("Speech recognition needs the offline language pack or a connection. " +
                            "Download it in Settings > System > Languages > Voice typing / Google app > Offline speech recognition.")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    report("Your language isn't available for speech recognition on this device.")
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                    resetRecognizer()
                    tvStatus.text = "Tap the mic to try again"
                }
                else -> {
                    resetRecognizer()
                    report("Something went wrong (code $error). Please try again.")
                }
            }
        }
    }

    // =====================================================================
    // Command handling + speaking
    // =====================================================================

    private fun handleCommand(text: String) {
        tvTranscript.text = "“$text”"
        val reply = processor.process(text)
        tvResponse.text = reply.text
        tvStatus.text = "Tap the mic to speak"
        pendingAction = reply.action
        speak(reply.text)
    }

    /** Shows a message on screen and says it out loud. */
    private fun report(message: String) {
        tvResponse.text = message
        tvStatus.text = "Tap the mic to try again"
        pendingAction = null
        speak(message)
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            runPendingAction() // no voice available: still perform the action
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_utterance")
    }

    private fun runPendingAction() {
        runOnUiThread {
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        }
    }

    // ---- TextToSpeech init ----
    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            ttsReady = false
            tvStatus.text = "Text-to-speech is unavailable; answers will be shown as text only."
            return
        }
        var result = engine.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = engine.setLanguage(Locale.US) // fall back to English
        }
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED

        val usage = if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes.USAGE_ASSISTANT
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = runPendingAction()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = runPendingAction()
        })
    }

    // =====================================================================
    // UI state
    // =====================================================================

    private fun setListeningUi(listening: Boolean) {
        isListening = listening
        btnMic.isSelected = listening
        if (listening) {
            tvStatus.text = "Listening…"
            pulse.visibility = View.VISIBLE
            levelRing.visibility = View.VISIBLE
            if (pulseAnimator == null) {
                pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                    pulse,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1.2f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1.2f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.45f, 0.1f)
                ).apply {
                    duration = 900
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                }
            }
            pulseAnimator?.start()
        } else {
            pulseAnimator?.cancel()
            pulse.visibility = View.INVISIBLE
            levelRing.animate().cancel()
            levelRing.scaleX = 1f
            levelRing.scaleY = 1f
            levelRing.visibility = View.INVISIBLE
        }
    }
}

// =========================================================================
// The "brain": a rule-based local command processor. No network, no cloud.
// =========================================================================

/** A spoken answer plus an optional action to run after it has been spoken. */
data class Reply(val text: String, val action: (() -> Unit)? = null)

class CommandProcessor(private val context: Context) {

    /**
     * OPTIONAL HOOK for an on-device LLM (see SETUP_GUIDE.md, MediaPipe section).
     * Set this to a lambda that returns the model's answer, e.g.
     *   processor.llmHook = { prompt -> llm.generateResponse(prompt) }
     * It is only called when no rule matches.
     */
    var llmHook: ((String) -> String?)? = null

    fun process(raw: String): Reply {
        val q = raw.lowercase(Locale.getDefault()).trim().trimEnd('.', '?', '!', ',')
        if (q.isEmpty()) return Reply("I didn't catch that.")

        // 1) Greetings (whole utterance only, so "hey open camera" isn't swallowed)
        if (Regex("^(hello|hi|hey|howdy|yo)( there| assistant)?$").matches(q)) {
            return Reply(listOf("Hello! How can I help?", "Hi there! What can I do for you?").random())
        }
        Regex("^good (morning|afternoon|evening)$").find(q)?.let {
            return Reply("Good ${it.groupValues[1]}! How can I help?")
        }

        // 2) Time & date
        if (Regex("\\b(what time is it|what's the time|what is the time|current time|tell me the time|time is it)\\b").containsMatchIn(q)) {
            return Reply("It's ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}.")
        }
        if (Regex("\\b(today's date|the date|what day is it|what day is today|what's the date|date today)\\b").containsMatchIn(q)) {
            return Reply("Today is ${SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())}.")
        }

        // 3) Math
        tryMath(q)?.let { return Reply(it) }

        // 4) Device info
        if ("battery" in q) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return Reply("Your battery is at $pct percent.")
        }

        // 5) Open an installed app
        Regex("^(?:open|launch|start|run)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$").find(q)?.let { m ->
            val appName = m.groupValues[1].trim()
            val launch = findLaunchIntent(appName)
            return if (launch != null) {
                Reply("Opening $appName.") { safeStart(launch) }
            } else {
                Reply("I couldn't find an app called $appName.")
            }
        }

        // 6) Explicit Wikipedia / web search (handled by the user's browser, not by us)
        Regex("^(?:search )?wikipedia(?: for| about)?\\s+(.+)$").find(q)?.let {
            return wikipedia(it.groupValues[1])
        }
        Regex("^(?:search for |look up )?(.+?) on wikipedia$").find(q)?.let {
            return wikipedia(it.groupValues[1])
        }
        Regex("^(?:search the web for|search for|search|google|look up|find)\\s+(.+)$").find(q)?.let {
            return webSearch(it.groupValues[1])
        }

        // 7) Preset small talk
        presetAnswer(q)?.let { return Reply(it) }

        // 8) Optional on-device LLM
        llmHook?.invoke(raw)?.takeIf { it.isNotBlank() }?.let { return Reply(it) }

        // 9) Knowledge-style questions -> Wikipedia search
        Regex("^(?:who is|who was|who are|what is|what are|what was|tell me about|define|explain)\\s+(.+)$")
            .find(q)?.let { return wikipedia(it.groupValues[1].removePrefix("a ").removePrefix("an ").removePrefix("the ")) }

        // 10) Fallback
        return Reply("Sorry, I don't know that one yet. Try saying “search for” followed by a topic, or “help”.")
    }

    // ---------------- Presets ----------------

    private fun presetAnswer(q: String): String? = when {
        Regex("\\b(how are you|how's it going)\\b").containsMatchIn(q) ->
            "I'm running smoothly, thanks for asking!"
        Regex("\\b(your name|who are you|what are you)\\b").containsMatchIn(q) ->
            "I'm your offline voice assistant, built right into this app."
        Regex("\\b(thank you|thanks)\\b").containsMatchIn(q) -> "You're welcome!"
        Regex("\\b(goodbye|bye|see you)\\b").containsMatchIn(q) -> "Goodbye! Talk to you soon."
        "joke" in q -> listOf(
            "Why do programmers prefer dark mode? Because light attracts bugs.",
            "I told my phone a joke, but it didn't get the reference. It had no context.",
            "There are 10 kinds of people: those who understand binary and those who don't."
        ).random()
        Regex("\\b(flip a coin|toss a coin|coin flip)\\b").containsMatchIn(q) ->
            "It's ${if ((0..1).random() == 0) "heads" else "tails"}."
        Regex("\\b(roll a die|roll a dice|roll the dice)\\b").containsMatchIn(q) ->
            "You rolled a ${(1..6).random()}."
        Regex("\\b(help|what can you do)\\b").containsMatchIn(q) ->
            "I can tell the time and date, do math, open apps, search Wikipedia or the web, " +
                    "check your battery, flip a coin, and tell jokes."
        else -> null
    }

    // ---------------- Intents ----------------

    private fun wikipedia(topic: String): Reply {
        val url = "https://en.wikipedia.org/wiki/Special:Search?go=Go&search=" + Uri.encode(topic.trim())
        return Reply("Looking up $topic on Wikipedia.") {
            safeStart(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }

    private fun webSearch(query: String): Reply = Reply("Searching the web for $query.") {
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (!safeStart(web)) {
            // No search handler: open a search page in the browser instead.
            safeStart(Intent(Intent.ACTION_VIEW, ("https://duckduckgo.com/?q=" + Uri.encode(query)).toUri()))
        }
    }

    /** Starts an activity from outside an Activity context; returns false if nothing can handle it. */
    private fun safeStart(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Finds a launchable app whose label matches [name] (needs the <queries> LAUNCHER entry). */
    @Suppress("DEPRECATION")
    private fun findLaunchIntent(name: String): Intent? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(main, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(main, 0)
        }
        val target = name.lowercase(Locale.getDefault())
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.getDefault()) == target }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.getDefault()).contains(target) }
        return match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
    }

    // ---------------- Math ----------------

    private val numberWords = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20
    )

    /** Returns a spoken answer, or null if [q] isn't a math expression. */
    private fun tryMath(q: String): String? {
        var e = q.replace(Regex("^(what's|what is|calculate|compute|how much is|solve|tell me)\\s+"), "")

        // "20 percent of 150"
        Regex("^(\\d+(?:\\.\\d+)?)\\s*(?:percent|%)\\s+of\\s+(\\d+(?:\\.\\d+)?)$").find(e)?.let {
            val r = it.groupValues[1].toDouble() / 100.0 * it.groupValues[2].toDouble()
            return "${it.groupValues[1]} percent of ${it.groupValues[2]} is ${format(r)}."
        }

        numberWords.forEach { (w, n) -> e = e.replace(Regex("\\b$w\\b"), n.toString()) }
        e = e.replace(Regex("(?<=\\d),(?=\\d{3})"), "")           // 1,000 -> 1000
            .replace("to the power of", "^")
            .replace("multiplied by", "*")
            .replace("divided by", "/")
            .replace(Regex("\\btimes\\b"), "*")
            .replace(Regex("\\bplus\\b"), "+")
            .replace(Regex("\\bminus\\b"), "-")
            .replace(Regex("\\bover\\b"), "/")
            .replace(Regex("\\bsquared\\b"), "^2")
            .replace(Regex("\\bcubed\\b"), "^3")
            .replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), "*")      // "5 x 3"
            .replace("÷", "/").replace("×", "*")
            .replace(" ", "")

        // Must look like an expression: only math characters, with a digit and an operator.
        if (!Regex("^[0-9+\\-*/^().]+$").matches(e)) return null
        if (!e.any { it.isDigit() } || !e.any { it in "+-*/^" }) return null

        return try {
            val result = ExprParser(e).parse()
            if (result.isNaN() || result.isInfinite()) "That result is too large to say."
            else "The answer is ${format(result)}."
        } catch (_: ArithmeticException) {
            "I can't divide by zero."
        } catch (_: Exception) {
            null // not valid math; let other rules try
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