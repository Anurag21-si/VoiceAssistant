package com.example.voiceassistant

import java.util.Locale

/** The two languages Sunita listens and speaks. */
enum class AppLang(val tag: String, val locale: Locale) {
    EN("en-US", Locale.US),
    HI("hi-IN", Locale.forLanguageTag("hi-IN"))
}

/**
 * What the assistant answers.
 * [switchTo]: change the app language (voice command "speak in Hindi").
 * [action]: runs after the answer has been spoken (open an app, set an alarm...).
 */
data class Reply(
    val text: String,
    val endConversation: Boolean = false,
    val switchTo: AppLang? = null,
    val action: (() -> Unit)? = null
)

/** A word from the user's sentence: [o] as spoken, [n] normalised for comparing. */
data class Word(val o: String, val n: String)

/**
 * Text helpers and keyword lists shared by the English and Hindi parsers.
 *
 * Speech engines spell Hindi in several ways (व्हाट्सएप / वॉट्सऐप, पाँच / पांच ...).
 * [norm] flattens those differences. Every keyword below is passed through [norm]
 * too, so both sides always compare in the same form.
 */
object Lex {

    // ------------------------------------------------------------------
    // Normalisation
    // ------------------------------------------------------------------

    fun norm(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase(Locale.ROOT)) {
            when (ch) {
                '\u093C' -> Unit                      // nukta (ज़ -> ज)
                '\u0901' -> sb.append('\u0902')       // chandrabindu -> anusvara (पाँच -> पांच)
                '\u0949' -> sb.append('\u093E')       // ॉ -> ा (वॉट्स -> वाट्स)
                '\u0958' -> sb.append('\u0915')       // precomposed nukta letters
                '\u0959' -> sb.append('\u0916')
                '\u095A' -> sb.append('\u0917')
                '\u095B' -> sb.append('\u091C')
                '\u095C' -> sb.append('\u0921')
                '\u095D' -> sb.append('\u0922')
                '\u095E' -> sb.append('\u092B')
                '\u095F' -> sb.append('\u092F')
                in '\u0966'..'\u096F' -> sb.append('0' + (ch - '\u0966')) // Devanagari digits -> 0-9
                else -> sb.append(ch)
            }
        }
        return sb.toString()
            .replace("ऐप", "एप")
            .replace("न्द", "ंद")
            .replace("न्त", "ंत")
            .replace("ण्ड", "ंड")
            .replace("ण्ट", "ंट")
            .replace("म्प", "ंप")
    }

    /** Regex whose pattern is normalised the same way as the text it will search. */
    fun rx(pattern: String): Regex = Regex(norm(pattern))

    /** Normalised keyword list. */
    fun nl(vararg words: String): List<String> = words.map { norm(it) }

    fun tokens(text: String): List<String> =
        text.split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotEmpty() }

    fun squash(text: String): String = text.replace(" ", "")

    fun hasAny(q: String, list: List<String>): Boolean = list.any { q.contains(it) }

    fun tokenAny(tokens: Set<String>, list: List<String>): Boolean = list.any { it in tokens }

    /** Split the original sentence into words (keeping the spoken form for messages). */
    fun words(raw: String): List<Word> =
        raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map {
            Word(it, norm(it).trim(',', '.', ':', ';', '?', '!', '।', '"', '“', '”'))
        }

    /** Drop [lead] words from the start and [trail] words from the end. */
    fun strip(list: List<Word>, lead: Set<String>, trail: Set<String>): List<Word> {
        var a = 0
        var b = list.size
        while (a < b && list[a].n in lead) a++
        while (b > a && list[b - 1].n in trail) b--
        return list.subList(a, b)
    }

    // ------------------------------------------------------------------
    // Number words (English + common Hindi) -> digits
    // ------------------------------------------------------------------

    private val tensMap = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)
    private val unitMap = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9
    )

    val numberWords: Map<String, Int> = HashMap<String, Int>().apply {
        listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty"
        ).forEachIndexed { i, w -> put(w, i) }
        put("thirty", 30); put("forty", 40); put("fifty", 50); put("sixty", 60)
        listOf(
            "शून्य", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ", "दस",
            "ग्यारह", "बारह", "तेरह", "चौदह", "पंद्रह", "सोलह", "सत्रह", "अठारह", "उन्नीस", "बीस"
        ).forEachIndexed { i, w -> put(norm(w), i) }
        mapOf(
            "छः" to 6, "पन्द्रह" to 15, "पच्चीस" to 25, "तीस" to 30,
            "चालीस" to 40, "पैंतालीस" to 45, "पचास" to 50, "साठ" to 60
        ).forEach { (k, v) -> put(norm(k), v) }
    }

    /** Hindi "दो" and "एक" also mean "give" and "a/one" in ordinary speech, so only treat them as numbers before a unit. */
    private val ambiguous = setOf(norm("दो"), norm("एक"))
    private val unitStarts = nl(
        "बजे", "बजकर", "मिनट", "घंटे", "घंटा", "सेकंड", "सेकेंड",
        "minute", "hour", "second", "min", "sec", "hr", "am", "pm", "percent", "प्रतिशत"
    )

    /** "twenty five" -> "25", "सात बजे" -> "7 बजे". */
    fun numbers(text: String, convertAmbiguous: Boolean = false): String {
        val tu = Regex("\\b(twenty|thirty|forty|fifty)[ -](one|two|three|four|five|six|seven|eight|nine)\\b")
            .replace(text) { (tensMap[it.groupValues[1]]!! + unitMap[it.groupValues[2]]!!).toString() }
        return Regex("[\\p{L}\\p{M}]+").replace(tu) { m ->
            val n = numberWords[m.value]
            when {
                n == null -> m.value
                !convertAmbiguous && m.value in ambiguous &&
                        !unitStarts.any { u -> tu.substring(m.range.last + 1).trimStart().startsWith(u) } -> m.value
                else -> n.toString()
            }
        }
    }

    // ------------------------------------------------------------------
    // App names in both languages
    // ------------------------------------------------------------------

    private val appAliases: Map<String, List<String>> = mapOf(
        "whatsapp" to listOf(
            "whatsapp", "whats app", "व्हाट्सएप", "व्हाट्सऐप", "वाट्सएप", "वाट्सऐप", "वट्सएप",
            "वॉट्सएप", "वॉट्सऐप", "व्हॉट्सएप", "व्हाटसएप", "वाटसएप", "व्हाट्स एप"
        ),
        "youtube" to listOf("youtube", "you tube", "यूट्यूब", "यू ट्यूब", "यूटयूब", "यूट्यब"),
        "camera" to listOf("camera", "कैमरा", "कैमेरा", "कैमरे"),
        "calculator" to listOf("calculator", "कैलकुलेटर", "कैल्कुलेटर", "कैलक्युलेटर", "कैलकुलेटर"),
        "settings" to listOf("settings", "setting", "सेटिंग", "सेटिंग्स", "सेटिंग्ज"),
        "chrome" to listOf("chrome", "क्रोम")
    ).mapValues { (_, list) -> list.map { squash(norm(it)) } }

    fun canonicalApp(name: String): String? {
        val s = squash(norm(name.trim()))
        return appAliases.entries.firstOrNull { s in it.value }?.key
    }

    /** True if the sentence mentions the app (also catches two-word spellings like "व्हाट्स एप"). */
    fun mentionsApp(q: String, key: String): Boolean {
        val s = squash(q)
        return appAliases[key].orEmpty().any { s.contains(it) }
    }

    // ------------------------------------------------------------------
    // Keyword lists (English + Hindi). Matching is done on normalised text.
    // ------------------------------------------------------------------

    val END = nl(
        "bye", "goodbye", "good bye", "stop listening", "that's all", "that is all", "stop",
        "अलविदा", "बाय", "गुड बाय", "फिर मिलते हैं", "सुनना बंद करो", "बस इतना ही", "बस"
    )
    val GREETINGS = nl(
        "hello", "hi", "hey", "howdy", "yo", "hello there", "hi there",
        "namaste", "नमस्ते", "नमस्कार", "हैलो", "हेलो", "हलो", "हाय", "प्रणाम"
    )
    val GOOD_TIME = nl(
        "good morning", "good afternoon", "good evening",
        "सुप्रभात", "शुभ प्रभात", "शुभ संध्या", "शुभ रात्रि"
    )

    val TIME_Q = nl(
        "what time is it", "what's the time", "what is the time", "current time",
        "tell me the time", "time is it", "time now",
        "समय क्या", "टाइम क्या", "कितने बजे", "कितना बजा", "क्या बजा", "समय बताओ",
        "टाइम बताओ", "अभी समय", "अभी टाइम", "कितने बज"
    )
    val DATE_Q = nl(
        "today's date", "the date", "what day is it", "what day is today", "what's the date",
        "date today", "today date",
        "तारीख", "आज कौन सा दिन", "आज क्या दिन", "कौन सा दिन", "आज की डेट", "डेट क्या", "दिनांक"
    )

    val TIMER_W = nl("timer", "countdown", "टाइमर", "काउंटडाउन")
    val ALARM_W = nl("alarm", "wake me", "अलार्म", "अलारम", "एलार्म", "जगा")
    val MORNING = nl("morning", "सुबह", "सवेरे", "भोर")
    val EVENING = nl("evening", "afternoon", "दोपहर", "शाम")
    val NIGHT = nl("night", "रात")

    val TORCH_W = nl(
        "flashlight", "flash light", "torch", "टॉर्च", "टार्च", "फ्लैशलाइट", "फ्लैश लाइट", "फ्लैश"
    )
    val ON_W = nl("on", "enable", "start", "चालू", "ऑन", "ओन", "जलाओ", "जला", "शुरू")
    val OFF_W = nl("off", "stop", "disable", "बंद", "ऑफ", "ओफ", "बुझाओ", "बुझा")

    val BATTERY_W = nl("battery", "बैटरी", "चार्जिंग", "charging")

    val VOL_UP = nl(
        "volume up", "increase volume", "increase the volume", "raise the volume", "louder",
        "turn it up", "speak up", "आवाज बढ़ाओ", "वॉल्यूम बढ़ाओ", "आवाज तेज", "आवाज ज्यादा",
        "तेज बोलो", "आवाज बढ़ा"
    )
    val VOL_DOWN = nl(
        "volume down", "decrease volume", "decrease the volume", "lower the volume", "quieter",
        "softer", "turn it down", "आवाज कम", "वॉल्यूम कम", "आवाज घटाओ", "वॉल्यूम घटाओ", "धीरे बोलो"
    )
    val VOL_MAX = nl(
        "max volume", "maximum volume", "full volume", "volume max", "volume to max",
        "फुल वॉल्यूम", "मैक्स वॉल्यूम", "पूरी आवाज"
    )

    val WA_VERBS = nl(
        "send", "message", "text", "msg", "write", "sms",
        "मैसेज", "मेसेज", "संदेश", "भेजो", "भेजें", "भेजिए", "भेज", "लिखो"
    )
    val WA_LEAD_STOP = nl(
        "पर", "on", "मैसेज", "मेसेज", "संदेश", "message", "msg", "text", "भेजो", "भेजें", "भेजिए",
        "भेजना", "भेज", "दो", "करो", "send", "a", "an", "the", "कि", "that", "saying", "says",
        "please", "प्लीज", "कृपया", "में", "write", "लिखो"
    ).toSet()
    val WA_TRAIL_STOP = nl(
        "भेजो", "भेजें", "भेजिए", "भेज", "दो", "करो", "please", "प्लीज", "कृपया", "पर", "on",
        "send", "भेजना", "कर", "दीजिए", "it", "this"
    ).toSet()
    val WA_DELIM = nl("saying", "says", "that", "कि").toSet()

    val SEARCH_TRIG = nl(
        "search", "find", "lookup", "look", "google", "गूगल", "play", "बजाओ",
        "खोजो", "खोजिए", "खोज", "सर्च", "ढूंढो", "ढूंढ", "ढूंढिए"
    )
    val PLAY_W = nl("play", "बजाओ").toSet()
    val SEARCH_EDGE = nl(
        "search", "for", "google", "youtube", "you", "tube", "on", "in", "the", "web", "find", "look",
        "up", "lookup", "play", "open", "launch", "start", "run", "please", "about", "tell", "me",
        "a", "an", "videos", "video", "wikipedia", "of",
        "गूगल", "यूट्यूब", "यू", "ट्यूब", "पर", "खोजो", "खोजिए", "खोज", "सर्च", "करो", "कीजिए",
        "ढूंढो", "ढूंढ", "ढूंढिए", "में", "के", "बारे", "बताओ", "चलाओ", "बजाओ", "वीडियो", "दो", "को",
        "का", "की", "मुझे", "कृपया", "प्लीज", "खोलो", "चालू", "ओपन", "विकिपीडिया", "पे", "सुनाओ", "दिखाओ"
    ).toSet()
    val WIKI_W = nl("wikipedia", "विकिपीडिया")

    val YES = nl(
        "yes", "yeah", "yep", "sure", "ok", "okay", "google", "गूगल", "हां", "हाँ", "जी", "ठीक", "हाँजी"
    )
    val NO = nl("no", "nope", "cancel", "नहीं", "रहने", "छोड़ो", "मत")

    val HINDI_NAMES = nl("hindi", "हिंदी", "हिन्दी")
    val ENGLISH_NAMES = nl("english", "अंग्रेजी", "अंग्रेज़ी", "इंग्लिश", "अंग्रेज़ी")
    val LANG_SWITCH_W = nl(
        "switch", "change", "speak", "talk", "reply", "language", "in", "to", "use",
        "में", "बोलो", "बदलो", "बात", "भाषा", "चुनो", "बोलिए"
    )

    val NAME_Q = nl(
        "your name", "who are you", "what are you", "तुम्हारा नाम", "आपका नाम", "तेरा नाम",
        "तुम कौन", "आप कौन"
    )
    val HOW_ARE_YOU = nl(
        "how are you", "how's it going", "कैसे हो", "कैसी हो", "कैसे हैं", "आप कैसे", "तुम कैसे", "क्या हाल"
    )
    val THANKS = nl("thank you", "thanks", "धन्यवाद", "शुक्रिया", "थैंक यू", "थैंक्स")
    val JOKE = nl("joke", "जोक", "चुटकुला", "चुटकुले")
    val COIN = nl("flip a coin", "toss a coin", "coin flip", "सिक्का उछालो", "सिक्का", "टॉस")
    val DICE = nl("roll a die", "roll a dice", "roll the dice", "पासा", "डाइस")
    val HELP = nl("help", "what can you do", "मदद", "हेल्प", "क्या कर सकती", "क्या कर सकते", "तुम क्या कर")

    val MATH_FILLER = nl(
        "what's", "what is", "calculate", "compute", "how much is", "solve", "tell me",
        "कितना होता है", "कितना होगा", "कितना हुआ", "कितने होते हैं", "कितने होंगे", "कितना", "कितने",
        "क्या है", "क्या होगा", "बताओ", "निकालो", "का जवाब", "जवाब"
    )
}