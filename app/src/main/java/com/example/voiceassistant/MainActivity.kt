package com.example.voiceassistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Sunita: an offline-first voice assistant.
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
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var switchContinuous: SwitchCompat
    private lateinit var seekVolume: SeekBar

    // ---- Engines ----
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var audio: AudioManager
    private var enhancer: LoudnessEnhancer? = null // extra loudness beyond max volume
    private var ttsSessionId = 0

    // ---- State ----
    private var isListening = false
    private var continuous = false          // "Keep listening" switch
    private var silentFailures = 0          // consecutive timeouts in continuous mode
    private var busyRetries = 0
    private var liveBubble: TextView? = null // chat bubble updated by partial results
    private var pendingAction: (() -> Unit)? = null
    private var pulseAnimator: ObjectAnimator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var processor: CommandProcessor

    /** Voice boost in millibels (100 mB = 1 dB). Lower it if the voice sounds distorted; 0 disables it. */
    private val voiceBoostMb = 800

    // ---- Permission launchers ----
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else onPermissionDenied()
        }

    private val contactsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            addBubble(
                if (granted) "Contacts allowed. Now say your call command again."
                else "Without contacts access I can only dial numbers that you say.",
                false
            )
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
        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)
        switchContinuous = findViewById(R.id.switchContinuous)
        seekVolume = findViewById(R.id.seekVolume)

        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volumeControlStream = AudioManager.STREAM_MUSIC // hardware volume keys control Sunita's voice

        processor = CommandProcessor(this).apply {
            onNeedContactsPermission = { contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS) }
        }
        tts = TextToSpeech(this, this)

        setupVolumeSlider()

        btnMic.setOnClickListener {
            if (isListening) {
                if (continuous) {
                    switchContinuous.isChecked = false // tapping the mic ends continuous mode
                    recognizer?.cancel()
                    clearLiveBubble()
                    setListeningUi(false)
                } else {
                    stopListening()
                }
            } else {
                tts?.stop()
                ensurePermissionThenListen()
            }
        }

        switchContinuous.setOnCheckedChangeListener { _, checked ->
            continuous = checked
            silentFailures = 0
            if (checked && !isListening) ensurePermissionThenListen()
        }

        addBubble("Hi, I'm Sunita. Tap the mic and try “set a timer for 5 minutes” or “what time is it?”", false)
    }

    override fun onResume() {
        super.onResume()
        syncVolume()
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        clearLiveBubble()
        setListeningUi(false)
        tts?.stop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        pulseAnimator?.cancel()
        recognizer?.destroy()
        recognizer = null
        enhancer?.release()
        enhancer = null
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
        switchContinuous.isChecked = false
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle("Microphone permission needed")
                .setMessage("Sunita needs microphone access to hear you. You can enable it in the app settings.")
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
        tts?.stop() // don't let Sunita hear herself
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
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        try {
            recognizer?.startListening(intent)
            setListeningUi(true)
        } catch (e: Exception) {
            resetRecognizer()
            report("Couldn't start the microphone. Please try again.")
        }
    }

    private fun stopListening() {
        recognizer?.stopListening()
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
            val level = rmsdB.coerceIn(0f, 10f) / 10f
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
            if (!partial.isNullOrBlank()) {
                val live = liveBubble ?: addBubble("", true).also { liveBubble = it }
                live.text = partial
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            setListeningUi(false)
            busyRetries = 0
            silentFailures = 0
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text.isNullOrBlank()) handleSilence("I didn't catch that. Please try again.") else handleCommand(text)
        }

        override fun onError(error: Int) {
            setListeningUi(false)
            clearLiveBubble()
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
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
                    handleSilence("I didn't hear anything. Tap the mic and try again.")
                SpeechRecognizer.ERROR_NO_MATCH ->
                    handleSilence("Sorry, I couldn't understand that.")
                SpeechRecognizer.ERROR_AUDIO ->
                    report("There was a problem with the microphone. Is another app using it?")
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    onPermissionDenied()
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER ->
                    report(
                        "Speech recognition needs the offline language pack or a connection. " +
                                "Download it in the Google app's settings under Voice > Offline speech recognition."
                    )
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

    /**
     * Silence or unintelligible speech. In "Keep listening" mode we quietly listen again,
     * but give up after 3 misses in a row so the mic never stays open forever.
     */
    private fun handleSilence(message: String) {
        if (continuous && ++silentFailures < 3) {
            tvStatus.text = "Listening…"
            mainHandler.postDelayed({ if (continuous && !isListening) startListening() }, 300)
        } else {
            silentFailures = 0
            if (continuous) {
                switchContinuous.isChecked = false
                report("I'll stop listening for now. Tap the mic when you need me.")
            } else {
                report(message)
            }
        }
    }

    // =====================================================================
    // Command handling + speaking
    // =====================================================================

    private fun handleCommand(text: String) {
        val bubble = liveBubble ?: addBubble("", true)
        bubble.text = text
        liveBubble = null

        val reply = processor.process(text)
        addBubble(reply.text, false)
        tvStatus.text = if (continuous) "Say something, I'm listening after I answer" else "Tap the mic to speak"
        pendingAction = reply.action
        if (reply.endConversation && switchContinuous.isChecked) switchContinuous.isChecked = false
        speak(reply.text)
        syncVolume() // a volume command may have changed it
    }

    /** Shows an error/notice in the chat and says it. Also turns off "Keep listening" so errors can't loop. */
    private fun report(message: String) {
        if (switchContinuous.isChecked) switchContinuous.isChecked = false
        addBubble(message, false)
        tvStatus.text = "Tap the mic to try again"
        pendingAction = null
        speak(message)
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            afterSpeaking()
            return
        }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            if (ttsSessionId > 0) putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, ttsSessionId)
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "assistant_utterance")
    }

    /** Runs after Sunita has finished talking: do the pending action, then maybe listen again. */
    private fun afterSpeaking() {
        runOnUiThread {
            val action = pendingAction
            pendingAction = null
            action?.invoke()
            scheduleResumeListening()
        }
    }

    private fun scheduleResumeListening() {
        if (!continuous) return
        // The delay lets the speaker go quiet, and lets us notice if an action took us to another app.
        mainHandler.postDelayed({
            if (continuous && !isListening &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) startListening()
        }, 900)
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            ttsReady = false
            tvStatus.text = "Text-to-speech is unavailable; answers will be shown as text only."
            return
        }
        var result = engine.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = engine.setLanguage(Locale.US)
        }
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED

        // Media usage = the music volume stream, which the slider and hardware keys control.
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        // Loudness boost: a +8 dB gain on the TTS audio session (louder than max system volume).
        if (voiceBoostMb > 0) {
            try {
                ttsSessionId = audio.generateAudioSessionId()
                if (ttsSessionId > 0) {
                    enhancer = LoudnessEnhancer(ttsSessionId).apply {
                        setTargetGain(voiceBoostMb)
                        enabled = true
                    }
                }
            } catch (e: Exception) {
                enhancer = null
                ttsSessionId = 0 // some devices/engines don't support it; the voice just stays normal
            }
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = afterSpeaking()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = afterSpeaking()
        })
    }

    // =====================================================================
    // Volume slider
    // =====================================================================

    private fun setupVolumeSlider() {
        seekVolume.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        syncVolume()
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun syncVolume() {
        seekVolume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    // =====================================================================
    // Chat bubbles + UI state
    // =====================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addBubble(text: String, fromUser: Boolean): TextView {
        if (chatContainer.childCount > 60) chatContainer.removeViewAt(0) // keep the history light
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.va_text))
            setBackgroundResource(if (fromUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_assistant)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(8)
        }
        chatContainer.addView(bubble, lp)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        return bubble
    }

    private fun clearLiveBubble() {
        liveBubble?.let { chatContainer.removeView(it) }
        liveBubble = null
    }

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

/**
 * A spoken answer, whether it ends "Keep listening" mode, and an optional
 * action to run after the answer has been spoken.
 */
data class Reply(
    val text: String,
    val endConversation: Boolean = false,
    val action: (() -> Unit)? = null
)

class CommandProcessor(private val context: Context) {

    /** Optional on-device LLM hook (see SETUP_GUIDE.md, MediaPipe section). */
    var llmHook: ((String) -> String?)? = null

    /** Set by the activity: asks Android for the READ_CONTACTS permission. */
    var onNeedContactsPermission: (() -> Unit)? = null

    private var torchOn = false

    fun process(raw: String): Reply {
        var q = raw.lowercase(Locale.getDefault()).trim().trimEnd('.', '?', '!', ',')

        // "Hey Sunita, ..." -> strip the name (speech engines spell it a few ways)
        val nameRegex = Regex("^(?:(?:hey|hi|hello|ok|okay)\\s+)?(?:sunita|sunitha|sonita|suneeta)\\b,?\\s*")
        val addressed = nameRegex.containsMatchIn(q)
        q = q.replace(nameRegex, "").trim()
        if (q.isEmpty()) {
            return if (addressed) Reply("Hello! I'm Sunita. How can I help?") else Reply("I didn't catch that.")
        }

        // 1) Ending the conversation
        if (Regex("^(bye|goodbye|good bye|stop listening|that's all|that is all|stop|thanks bye)$").matches(q) ||
            Regex("\\b(goodbye|bye bye)\\b").containsMatchIn(q)
        ) {
            return Reply("Goodbye! Say my name whenever you need me.", endConversation = true)
        }

        // 2) Greetings
        if (Regex("^(hello|hi|hey|howdy|yo)( there)?$").matches(q)) {
            return Reply(listOf("Hello! I'm Sunita. How can I help?", "Hi there! What can I do for you?").random())
        }
        Regex("^good (morning|afternoon|evening)$").find(q)?.let {
            return Reply("Good ${it.groupValues[1]}! How can I help?")
        }

        // 3) Time & date
        if (Regex("\\b(what time is it|what's the time|what is the time|current time|tell me the time|time is it)\\b").containsMatchIn(q)) {
            return Reply("It's ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}.")
        }
        if (Regex("\\b(today's date|the date|what day is it|what day is today|what's the date|date today)\\b").containsMatchIn(q)) {
            return Reply("Today is ${SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())}.")
        }

        // 4) Timers & alarms
        if ("timer" in q) return timerReply(q)
        if ((Regex("\\balarm\\b").containsMatchIn(q) || "wake me" in q) &&
            !Regex("^(open|launch)\\b").containsMatchIn(q)
        ) return alarmReply(q)

        // 5) Math
        tryMath(q)?.let { return Reply(it) }

        // 6) Volume
        volumeCommand(q)?.let { return it }

        // 7) Flashlight
        if (Regex("\\b(flashlight|flash light|torch)\\b").containsMatchIn(q)) {
            val off = Regex("\\b(off|stop|disable)\\b").containsMatchIn(q)
            val on = Regex("\\b(on|enable|start)\\b").containsMatchIn(q)
            return setTorch(if (off) false else if (on) true else !torchOn)
        }

        // 8) Battery
        if ("battery" in q) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return Reply("Your battery is at $pct percent.")
        }

        // 9) Calls
        Regex("^(?:call|dial|phone|ring)\\s+(.+)$").find(q)?.let { return callReply(it.groupValues[1]) }

        // 10) Open an installed app
        Regex("^(?:open|launch|start|run)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$").find(q)?.let { m ->
            val appName = m.groupValues[1].trim()
            val launch = findLaunchIntent(appName)
            return if (launch != null) {
                Reply("Opening $appName.") { safeStart(launch) }
            } else {
                Reply("I couldn't find an app called $appName.")
            }
        }

        // 11) Wikipedia / web search
        Regex("^(?:search )?wikipedia(?: for| about)?\\s+(.+)$").find(q)?.let {
            return wikipedia(it.groupValues[1])
        }
        Regex("^(?:search for |look up )?(.+?) on wikipedia$").find(q)?.let {
            return wikipedia(it.groupValues[1])
        }
        Regex("^(?:search the web for|search for|search|google|look up|find)\\s+(.+)$").find(q)?.let {
            return webSearch(it.groupValues[1])
        }

        // 12) Small talk
        presetAnswer(q)?.let { return Reply(it) }

        // 13) Optional on-device LLM
        llmHook?.invoke(raw)?.takeIf { it.isNotBlank() }?.let { return Reply(it) }

        // 14) Knowledge-style questions -> Wikipedia
        Regex("^(?:who is|who was|who are|what is|what are|what was|tell me about|define|explain)\\s+(.+)$")
            .find(q)?.let { return wikipedia(it.groupValues[1].removePrefix("a ").removePrefix("an ").removePrefix("the ")) }

        return Reply("Sorry, I don't know that one yet. Try “search for” followed by a topic, or say help.")
    }

    // ---------------- Small talk ----------------

    private fun presetAnswer(q: String): String? = when {
        Regex("\\b(how are you|how's it going)\\b").containsMatchIn(q) ->
            "I'm running smoothly, thanks for asking!"
        Regex("\\b(your name|who are you|what are you)\\b").containsMatchIn(q) ->
            "My name is Sunita, your offline voice assistant."
        Regex("\\b(thank you|thanks)\\b").containsMatchIn(q) -> "You're welcome!"
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
            "I can set timers and alarms, call your contacts, control the flashlight and volume, " +
                    "tell the time and date, do math, open apps, search Wikipedia or the web, " +
                    "check your battery, and tell jokes."
        else -> null
    }

    // ---------------- Timers & alarms ----------------

    private val numberWords = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60
    )
    private val tensMap = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)
    private val unitMap = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9
    )

    /** "twenty five" -> "25", "seven" -> "7". Used only where numbers are expected. */
    private fun numbers(text: String): String {
        var e = Regex("\\b(twenty|thirty|forty|fifty)[ -](one|two|three|four|five|six|seven|eight|nine)\\b")
            .replace(text) { (tensMap[it.groupValues[1]]!! + unitMap[it.groupValues[2]]!!).toString() }
        numberWords.forEach { (w, n) -> e = e.replace(Regex("\\b$w\\b"), n.toString()) }
        return e
    }

    private fun timerReply(q: String): Reply {
        val s = numbers(q)
            .replace("half an hour", "30 minutes")
            .replace(Regex("\\ban?\\s+(?=hour|minute|second)"), "1 ")
        var total = 0.0
        Regex("(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)").findAll(s).forEach { m ->
            val n = m.groupValues[1].toDouble()
            val unit = m.groupValues[2]
            total += when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("m") -> n * 60
                else -> n
            }
        }
        val seconds = total.roundToInt()
        if (seconds <= 0) return Reply("How long should the timer be? For example, say set a timer for 10 minutes.")

        return Reply("Timer set for ${durationText(seconds)}.") {
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
        if (h > 0) parts += "$h ${if (h == 1) "hour" else "hours"}"
        if (m > 0) parts += "$m ${if (m == 1) "minute" else "minutes"}"
        if (s > 0) parts += "$s ${if (s == 1) "second" else "seconds"}"
        return parts.joinToString(" ")
    }

    private fun alarmReply(q: String): Reply {
        // "7:30 a.m." -> "7:30am", number words -> digits
        val s = numbers(q).replace(Regex("\\b([ap])\\.\\s?m\\.?"), "\$1m")
        val m = Regex("(\\d{1,2})(?:[:.\\s](\\d{2}))?\\s*(am|pm)?").find(s)
            ?: return Reply("What time should I set the alarm for? For example, set an alarm for 7 30 am.")

        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        if (h > 23 || min > 59) return Reply("I didn't understand that alarm time.")

        val ap = m.groupValues[3].ifEmpty {
            when {
                "morning" in s -> "am"
                Regex("evening|afternoon|night").containsMatchIn(s) -> "pm"
                else -> ""
            }
        }
        val hour24 = when {
            ap == "am" -> if (h == 12) 0 else h
            ap == "pm" -> if (h < 12) h + 12 else h
            h in 1..12 -> nextOccurrenceHour(h, min) // no am/pm said: pick the next upcoming one
            else -> h
        }

        val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
        val spoken = String.format(Locale.US, "%d:%02d %s", hour12, min, if (hour24 < 12) "AM" else "PM")

        return Reply("Alarm set for $spoken.") {
            safeStart(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour24)
                    .putExtra(AlarmClock.EXTRA_MINUTES, min)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Sunita")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            )
        }
    }

    /** For "alarm at 7" with no am/pm: whichever of 7 AM / 7 PM comes next. */
    private fun nextOccurrenceHour(h: Int, min: Int): Int {
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val morning = h % 12
        val evening = h % 12 + 12
        return listOf(morning, evening).firstOrNull { it * 60 + min > nowMinutes } ?: morning
    }

    // ---------------- Volume ----------------

    private fun volumeCommand(q: String): Reply? {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)

        Regex("(?:set|change|put) (?:the )?volume (?:to |at )?(\\d{1,3})").find(numbers(q))?.let {
            val pct = it.groupValues[1].toInt().coerceIn(0, 100)
            audio.setStreamVolume(stream, (max * pct / 100.0).roundToInt(), 0)
            return Reply("Volume set to $pct percent.")
        }
        return when {
            Regex("(max(imum)? volume|volume (to )?max(imum)?|full volume)").containsMatchIn(q) -> {
                audio.setStreamVolume(stream, max, 0)
                Reply("Volume is at maximum.")
            }
            Regex("\\b(volume up|increase (the )?volume|raise (the )?volume|louder|turn it up|speak up)\\b").containsMatchIn(q) -> {
                repeat(2) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0) }
                Reply("Louder.")
            }
            Regex("\\b(volume down|decrease (the )?volume|lower (the )?volume|quieter|softer|turn it down)\\b").containsMatchIn(q) -> {
                repeat(2) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0) }
                Reply("Quieter.")
            }
            else -> null
        }
    }

    // ---------------- Flashlight ----------------

    private fun setTorch(on: Boolean): Reply {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Reply("This phone doesn't have a flashlight.")
            cm.setTorchMode(id, on)
            torchOn = on
            Reply(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            Reply("I couldn't control the flashlight.")
        }
    }

    // ---------------- Calls ----------------

    private fun callReply(target: String): Reply {
        val t = target.trim()

        // A spoken number such as "98765 43210"
        if (Regex("^[\\d\\s\\-+()]{5,}$").matches(t)) {
            val number = t.replace(Regex("[^\\d+]"), "")
            return Reply("Dialing ${number.toList().joinToString(" ")}.") { dial(number) }
        }

        // A contact name: needs READ_CONTACTS, requested the first time
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Reply("I need permission to read your contacts. Please allow it, then ask me again.") {
                onNeedContactsPermission?.invoke()
            }
        }
        val found = findContact(t) ?: return Reply("I couldn't find $t in your contacts.")
        return Reply("Calling ${found.first}.") { dial(found.second) }
    }

    /** Opens the dialer with the number filled in (you press the green call button). */
    private fun dial(number: String) {
        safeStart(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    }

    private fun findContact(name: String): Pair<String, String>? = try {
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        context.contentResolver.query(
            phone,
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

    // ---------------- Intents ----------------

    private fun wikipedia(topic: String): Reply {
        val url = "https://en.wikipedia.org/wiki/Special:Search?go=Go&search=" + Uri.encode(topic.trim())
        return Reply("Looking up $topic on Wikipedia.") {
            safeStart(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }

    private fun webSearch(query: String): Reply = Reply("Searching the web for $query.") {
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (!safeStart(web, toastOnFail = false)) {
            safeStart(Intent(Intent.ACTION_VIEW, ("https://duckduckgo.com/?q=" + Uri.encode(query)).toUri()))
        }
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

    private fun tryMath(q: String): String? {
        var e = q.replace(Regex("^(what's|what is|calculate|compute|how much is|solve|tell me)\\s+"), "")

        Regex("^(\\d+(?:\\.\\d+)?)\\s*(?:percent|%)\\s+of\\s+(\\d+(?:\\.\\d+)?)$").find(e)?.let {
            val r = it.groupValues[1].toDouble() / 100.0 * it.groupValues[2].toDouble()
            return "${it.groupValues[1]} percent of ${it.groupValues[2]} is ${format(r)}."
        }

        e = numbers(e)
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
            .replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), "*")
            .replace("÷", "/").replace("×", "*")
            .replace(" ", "")

        if (!Regex("^[0-9+\\-*/^().]+$").matches(e)) return null
        if (!e.any { it.isDigit() } || !e.any { it in "+-*/^" }) return null

        return try {
            val result = ExprParser(e).parse()
            if (result.isNaN() || result.isInfinite()) "That result is too large to say."
            else "The answer is ${format(result)}."
        } catch (ex: ArithmeticException) {
            "I can't divide by zero."
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