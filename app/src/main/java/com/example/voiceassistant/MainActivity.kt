package com.example.voiceassistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import java.util.Locale

/**
 * Sunita: a bilingual (English + Hindi) offline-first voice assistant.
 *
 *   Mic -> SpeechRecognizer -> CommandProcessor (local rules) -> TextToSpeech
 *
 * No third-party cloud APIs, no keys, no paid services. Min SDK 26.
 */
@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        /**
         * If the offline speech pack for a language is missing, retry once with the phone's own
         * recognizer in normal mode (which may use Google's servers; no API key, no billing).
         * Set to false to stay strictly offline.
         */
        private const val ALLOW_ONLINE_FALLBACK = true

        /** Extra loudness in millibels (100 mB = 1 dB). Lower it if the voice distorts; 0 turns it off. */
        private const val VOICE_BOOST_MB = 800

        private const val PREFS = "sunita_prefs"

        /** Slider stops: 0 = low/slow, 1 = normal, 2 = high/fast. */
        private val STEP_VALUES = floatArrayOf(0.7f, 1.0f, 1.4f)
    }

    // ---- Views ----
    private lateinit var btnMic: ImageButton
    private lateinit var pulse: View
    private lateinit var levelRing: View
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvReply: TextView
    private lateinit var tvPitchLabel: TextView
    private lateinit var tvRateLabel: TextView
    private lateinit var radioLang: RadioGroup
    private lateinit var switchContinuous: SwitchCompat
    private lateinit var seekPitch: SeekBar
    private lateinit var seekRate: SeekBar
    private lateinit var spinnerVoice: Spinner

    // ---- Engines ----
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false   // engine initialised
    private var canSpeak = false   // engine has a voice for the selected language
    private lateinit var audio: AudioManager
    private var enhancer: LoudnessEnhancer? = null
    private var ttsSessionId = 0
    private lateinit var prefs: SharedPreferences
    private lateinit var processor: CommandProcessor

    // ---- State ----
    private var lang = AppLang.EN
    private var pitchStep = 1
    private var rateStep = 1
    private var voices: List<Voice> = emptyList()
    private var suppressLangCallback = false
    private var isListening = false
    private var continuous = false
    private var silentFailures = 0
    private var busyRetries = 0
    private val offlineFailed = mutableSetOf<AppLang>()
    private var installVoiceAsked = false
    private var pendingAction: (() -> Unit)? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var extraPermission: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Permission launchers ----
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else onMicPermissionDenied()
        }

    private val extraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val msg = if (extraPermission == Manifest.permission.CAMERA) {
                if (granted) t("Camera allowed. Say the flashlight command again.", "कैमरा अनुमति मिल गई। टॉर्च का आदेश फिर से कहिए।")
                else t("Without camera permission the flashlight may not work.", "कैमरा अनुमति के बिना टॉर्च शायद न चले।")
            } else {
                if (granted) t("Contacts allowed. Say your command again.", "कॉन्टैक्ट्स की अनुमति मिल गई। अपना आदेश फिर से कहिए।")
                else t("Without contacts access I can only use numbers you say.", "कॉन्टैक्ट्स की अनुमति के बिना मैं सिर्फ बोले गए नंबर इस्तेमाल कर सकती हूँ।")
            }
            tvReply.text = "${assistantLabel()}: $msg"
        }

    private fun t(en: String, hi: String): String = if (lang == AppLang.HI) hi else en
    private fun assistantLabel() = t("Assistant", "सुनीता")
    private fun youSaidLabel() = t("You said", "आपने कहा")

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
        tvReply = findViewById(R.id.tvReply)
        tvPitchLabel = findViewById(R.id.tvPitchLabel)
        tvRateLabel = findViewById(R.id.tvRateLabel)
        radioLang = findViewById(R.id.radioLang)
        switchContinuous = findViewById(R.id.switchContinuous)
        seekPitch = findViewById(R.id.seekPitch)
        seekRate = findViewById(R.id.seekRate)
        spinnerVoice = findViewById(R.id.spinnerVoice)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        lang = runCatching { AppLang.valueOf(prefs.getString("lang", "EN") ?: "EN") }.getOrDefault(AppLang.EN)
        pitchStep = prefs.getInt("pitch", 1).coerceIn(0, 2)
        rateStep = prefs.getInt("rate", 1).coerceIn(0, 2)

        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volumeControlStream = AudioManager.STREAM_MUSIC // hardware volume keys control Sunita's voice

        processor = CommandProcessor(this).apply {
            onNeedPermission = { permission ->
                extraPermission = permission
                extraPermissionLauncher.launch(permission)
            }
        }

        suppressLangCallback = true
        radioLang.check(if (lang == AppLang.HI) R.id.rbHindi else R.id.rbEnglish)
        suppressLangCallback = false
        seekPitch.progress = pitchStep
        seekRate.progress = rateStep
        refreshLabels()
        setupListeners()

        tts = TextToSpeech(this, this) // onInit() runs when the engine is ready
    }

    private fun setupListeners() {
        btnMic.setOnClickListener {
            if (isListening) {
                if (continuous) {
                    switchContinuous.isChecked = false // tapping the mic ends continuous mode
                    recognizer?.cancel()
                    setListeningUi(false)
                } else {
                    recognizer?.stopListening() // finish and deliver what was heard
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

        radioLang.setOnCheckedChangeListener { _, checkedId ->
            if (!suppressLangCallback) {
                changeLanguage(if (checkedId == R.id.rbHindi) AppLang.HI else AppLang.EN, announce = true)
            }
        }

        seekPitch.setOnSeekBarChangeListener(StepListener(
            onStep = { step ->
                pitchStep = step
                prefs.edit().putInt("pitch", step).apply()
                tts?.setPitch(STEP_VALUES[step])
                refreshLabels()
            },
            onRelease = { speakSample() }
        ))

        seekRate.setOnSeekBarChangeListener(StepListener(
            onStep = { step ->
                rateStep = step
                prefs.edit().putInt("rate", step).apply()
                tts?.setSpeechRate(STEP_VALUES[step])
                refreshLabels()
            },
            onRelease = { speakSample() }
        ))
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
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
    // Language
    // =====================================================================

    /** Switches listening + speaking language (from the toggle or by voice). */
    private fun changeLanguage(newLang: AppLang, announce: Boolean) {
        lang = newLang
        prefs.edit().putString("lang", lang.name).apply()

        suppressLangCallback = true
        radioLang.check(if (lang == AppLang.HI) R.id.rbHindi else R.id.rbEnglish)
        suppressLangCallback = false

        recognizer?.cancel()
        setListeningUi(false)
        pendingAction = null
        silentFailures = 0
        busyRetries = 0

        applyTtsLanguage()
        populateVoices()
        refreshLabels()
        tvStatus.text = t("Tap the mic to speak", "बोलने के लिए माइक दबाइए")

        if (announce) {
            val msg = t(
                "English selected. I'm listening in English.",
                "हिंदी चुनी गई। मैं अब हिंदी में सुनूँगी।"
            )
            tvReply.text = "${assistantLabel()}: $msg"
            speak(msg)
        }
    }

    private fun refreshLabels() {
        val pitchNames = if (lang == AppLang.HI) arrayOf("कम", "सामान्य", "ज़्यादा") else arrayOf("Low", "Normal", "High")
        val rateNames = if (lang == AppLang.HI) arrayOf("धीमी", "सामान्य", "तेज़") else arrayOf("Slow", "Normal", "Fast")
        tvPitchLabel.text = "${t("Pitch", "आवाज़ की पिच")}: ${pitchNames[pitchStep]}"
        tvRateLabel.text = "${t("Speed", "बोलने की गति")}: ${rateNames[rateStep]}"
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

    private fun onMicPermissionDenied() {
        switchContinuous.isChecked = false
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle(t("Microphone permission needed", "माइक्रोफ़ोन की अनुमति चाहिए"))
                .setMessage(
                    t(
                        "Sunita needs the microphone to hear you. You can enable it in the app settings.",
                        "सुनीता को आपकी बात सुनने के लिए माइक्रोफ़ोन चाहिए। आप इसे ऐप सेटिंग्स में चालू कर सकते हैं।"
                    )
                )
                .setPositiveButton(t("Open settings", "सेटिंग्स खोलें")) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", packageName, null))
                    )
                }
                .setNegativeButton(t("Cancel", "रद्द करें"), null)
                .show()
        } else {
            tvStatus.text = t("Microphone permission is required to listen.", "सुनने के लिए माइक्रोफ़ोन की अनुमति ज़रूरी है।")
        }
    }

    // =====================================================================
    // Speech recognition
    // =====================================================================

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            report(
                t(
                    "Speech recognition isn't available on this device. Install or enable the Google app.",
                    "इस फोन पर स्पीच रिकग्निशन उपलब्ध नहीं है। Google ऐप इंस्टॉल या चालू करें।"
                )
            )
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)            // "hi-IN" or "en-US"
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang.tag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, lang !in offlineFailed)
        }

        try {
            recognizer?.startListening(intent)
            setListeningUi(true)
        } catch (e: Exception) {
            resetRecognizer()
            report(t("Couldn't start the microphone. Please try again.", "माइक्रोफ़ोन चालू नहीं हो सका। फिर से कोशिश कीजिए।"))
        }
    }

    private fun resetRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            tvStatus.text = t("Listening…", "सुन रही हूँ…")
        }

        override fun onBeginningOfSpeech() {
            tvStatus.text = t("Hearing you…", "आपकी बात सुन रही हूँ…")
        }

        override fun onRmsChanged(rmsdB: Float) {
            val level = rmsdB.coerceIn(0f, 10f) / 10f
            val scale = 1f + level * 0.7f
            levelRing.animate().scaleX(scale).scaleY(scale).setDuration(80).start()
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            tvStatus.text = t("Thinking…", "सोच रही हूँ…")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!partial.isNullOrBlank()) tvTranscript.text = "${youSaidLabel()}: “$partial…”"
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            setListeningUi(false)
            busyRetries = 0
            silentFailures = 0
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text.isNullOrBlank()) {
                handleSilence(t("I didn't catch that. Please try again.", "मुझे समझ नहीं आया। फिर से कहिए।"))
            } else {
                handleCommand(text)
            }
        }

        override fun onError(error: Int) {
            setListeningUi(false)
            when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Mic/recognizer still held by an earlier session: reset and retry once.
                    resetRecognizer()
                    if (busyRetries++ < 1) {
                        tvStatus.text = t("Speech service busy, retrying…", "स्पीच सर्विस व्यस्त है, फिर कोशिश कर रही हूँ…")
                        mainHandler.postDelayed({ startListening() }, 500)
                    } else {
                        busyRetries = 0
                        report(t("The speech service is busy. Please wait a moment and try again.", "स्पीच सर्विस व्यस्त है। थोड़ा रुककर फिर कोशिश कीजिए।"))
                    }
                }
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    handleSilence(t("I didn't hear anything. Tap the mic and try again.", "मुझे कुछ सुनाई नहीं दिया। माइक दबाकर फिर कहिए।"))
                SpeechRecognizer.ERROR_NO_MATCH ->
                    handleSilence(t("Sorry, I couldn't understand that.", "माफ़ कीजिए, मैं समझ नहीं पाई।"))
                SpeechRecognizer.ERROR_AUDIO ->
                    report(t("There was a problem with the microphone. Is another app using it?", "माइक्रोफ़ोन में समस्या आई। क्या कोई दूसरा ऐप इसे इस्तेमाल कर रहा है?"))
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    onMicPermissionDenied()
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    onLanguagePackProblem()
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                    resetRecognizer()
                    tvStatus.text = t("Tap the mic to try again", "फिर कोशिश करने के लिए माइक दबाइए")
                }
                else -> {
                    resetRecognizer()
                    report(t("Something went wrong (code $error). Please try again.", "कुछ गड़बड़ हो गई (कोड $error)। फिर कोशिश कीजिए।"))
                }
            }
        }
    }

    /** The offline pack for this language is missing (or there's no connection). */
    private fun onLanguagePackProblem() {
        resetRecognizer()
        if (ALLOW_ONLINE_FALLBACK && lang !in offlineFailed) {
            offlineFailed.add(lang)
            tvStatus.text = t(
                "Offline pack not found, trying the phone's built-in recognizer…",
                "ऑफ़लाइन भाषा पैक नहीं मिला, फोन का बिल्ट-इन रिकग्नाइज़र आज़मा रही हूँ…"
            )
            mainHandler.postDelayed({ startListening() }, 300)
        } else {
            report(
                t(
                    "I can't recognise ${lang.locale.getDisplayLanguage(Locale.ENGLISH)} speech right now. Download the offline pack " +
                            "in the Google app (Settings, Voice, Offline speech recognition) or check your connection.",
                    "मैं अभी ${lang.locale.getDisplayLanguage(lang.locale)} नहीं पहचान पा रही। Google ऐप में " +
                            "(सेटिंग्स, वॉइस, ऑफ़लाइन स्पीच रिकग्निशन) भाषा पैक डाउनलोड कीजिए या इंटरनेट जाँचिए।"
                )
            )
        }
    }

    /** Silence / unintelligible speech. In "Keep listening" mode, quietly listen again (max 3 in a row). */
    private fun handleSilence(message: String) {
        if (continuous && ++silentFailures < 3) {
            tvStatus.text = t("Listening…", "सुन रही हूँ…")
            mainHandler.postDelayed({ if (continuous && !isListening) startListening() }, 300)
        } else {
            silentFailures = 0
            if (continuous) {
                switchContinuous.isChecked = false
                report(t("I'll stop listening for now. Tap the mic when you need me.", "मैं अभी सुनना बंद करती हूँ। ज़रूरत हो तो माइक दबाइए।"))
            } else {
                report(message)
            }
        }
    }

    // =====================================================================
    // Commands and speaking
    // =====================================================================

    private fun handleCommand(text: String) {
        val reply = processor.process(text, lang)
        reply.switchTo?.let { changeLanguage(it, announce = false) }

        tvTranscript.text = "${youSaidLabel()}: “$text”"
        tvReply.text = "${assistantLabel()}: ${reply.text}"
        tvStatus.text = t("Tap the mic to speak", "बोलने के लिए माइक दबाइए")
        pendingAction = reply.action
        if (reply.endConversation && switchContinuous.isChecked) switchContinuous.isChecked = false
        speak(reply.text)
    }

    /** Shows an error/notice and says it. Also switches "Keep listening" off so errors can't loop. */
    private fun report(message: String) {
        if (switchContinuous.isChecked) switchContinuous.isChecked = false
        tvReply.text = "${assistantLabel()}: $message"
        tvStatus.text = t("Tap the mic to try again", "फिर कोशिश करने के लिए माइक दबाइए")
        pendingAction = null
        speak(message)
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady || !canSpeak) {
            afterSpeaking() // no voice for this language: the text is on screen, still run the action
            return
        }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            if (ttsSessionId > 0) putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, ttsSessionId)
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "assistant_utterance")
    }

    private fun speakSample() {
        speak(t("Hello, I'm Sunita.", "नमस्ते, मैं सुनीता हूँ।"))
    }

    /** After Sunita finishes talking: run the pending action, then maybe listen again. */
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
            if (continuous && !isListening && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                startListening()
            }
        }, 900)
    }

    // =====================================================================
    // TextToSpeech: init, language, voices
    // =====================================================================

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            ttsReady = false
            tvStatus.text = t(
                "Text-to-speech is unavailable; answers will be shown as text only.",
                "टेक्स्ट-टू-स्पीच उपलब्ध नहीं है; जवाब सिर्फ़ लिखकर दिखेंगे।"
            )
            return
        }
        ttsReady = true

        // Media usage = the music volume stream, which the hardware keys control.
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        // Loudness boost beyond the system maximum. Some engines ignore it; then the voice stays normal.
        if (VOICE_BOOST_MB > 0) {
            try {
                ttsSessionId = audio.generateAudioSessionId()
                if (ttsSessionId > 0) {
                    enhancer = LoudnessEnhancer(ttsSessionId).apply {
                        setTargetGain(VOICE_BOOST_MB)
                        enabled = true
                    }
                }
            } catch (e: Exception) {
                enhancer = null
                ttsSessionId = 0
            }
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = afterSpeaking()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = afterSpeaking()
        })

        engine.setPitch(STEP_VALUES[pitchStep])
        engine.setSpeechRate(STEP_VALUES[rateStep])
        applyTtsLanguage()
        populateVoices()
    }

    private fun applyTtsLanguage() {
        val engine = tts ?: return
        if (!ttsReady) return
        val result = engine.setLanguage(lang.locale)
        canSpeak = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!canSpeak) {
            tvStatus.text = t(
                "No voice installed for this language. Answers will be shown as text.",
                "इस भाषा की आवाज़ इंस्टॉल नहीं है। जवाब लिखकर दिखेंगे।"
            )
            if (lang == AppLang.HI && !installVoiceAsked) {
                installVoiceAsked = true
                showInstallVoiceDialog()
            }
        }
    }

    private fun showInstallVoiceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Hindi voice not installed / हिंदी आवाज़ इंस्टॉल नहीं है")
            .setMessage(
                "To hear Sunita speak Hindi, install the Hindi voice data.\n\n" +
                        "सुनीता को हिंदी में बोलते सुनने के लिए हिंदी वॉइस डेटा इंस्टॉल कीजिए।"
            )
            .setPositiveButton("Install / इंस्टॉल") { _, _ ->
                try {
                    startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Later / बाद में", null)
            .show()
    }

    /** Fills the spinner with the installed (offline) voices for the selected language. */
    private fun populateVoices() {
        val engine = tts ?: return
        if (!ttsReady) return

        val all: List<Voice> = try {
            engine.voices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val sameLanguage = all.filter { it.locale.language == lang.locale.language }
        val usable = sameLanguage.filter {
            it.features?.contains("notInstalled") != true && !it.isNetworkConnectionRequired
        }
        voices = (if (usable.isNotEmpty()) usable else sameLanguage).sortedBy { it.name }

        val labels = voices.mapIndexed { i, v ->
            "${v.locale.getDisplayName(v.locale)} · ${t("voice", "आवाज़")} ${i + 1}" +
                    if (v.isNetworkConnectionRequired) " (online)" else ""
        }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerVoice.onItemSelectedListener = null
        spinnerVoice.adapter = adapter
        spinnerVoice.isEnabled = voices.isNotEmpty()
        if (voices.isEmpty()) return

        val savedName = prefs.getString("voice_${lang.name}", null)
        val currentName = engine.voice?.name
        val index = voices.indexOfFirst { it.name == savedName }.takeIf { it >= 0 }
            ?: voices.indexOfFirst { it.name == currentName }.takeIf { it >= 0 }
            ?: 0

        if (engine.voice?.name != voices[index].name) engine.setVoice(voices[index])
        spinnerVoice.setSelection(index, false)

        spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = voices.getOrNull(position) ?: return
                if (tts?.voice?.name == chosen.name) return // nothing changed
                tts?.setVoice(chosen)
                prefs.edit().putString("voice_${lang.name}", chosen.name).apply()
                speakSample()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    // =====================================================================
    // Animated mic state
    // =====================================================================

    private fun setListeningUi(listening: Boolean) {
        isListening = listening
        btnMic.isSelected = listening
        if (listening) {
            tvStatus.text = t("Listening…", "सुन रही हूँ…")
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

    /** SeekBar with 3 stops (low / normal / high). [onStep] while dragging, [onRelease] when let go. */
    private class StepListener(
        private val onStep: (Int) -> Unit,
        private val onRelease: () -> Unit
    ) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onStep(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = onRelease()
    }
}