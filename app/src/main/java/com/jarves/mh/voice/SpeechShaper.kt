package com.jarves.mh.voice

/** Turns model output into something short and natural to hear. Mirrors vhbrain.conductor.speakable(). */
object SpeechShaper {
    private val codeBlock = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
    private val mdLink = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val mdChars = Regex("[*_`#>]+")
    private val bullet = Regex("(?m)^\\s*[-•]\\s*")
    private val sentenceSplit = Regex("(?<=[.!?])\\s+")

    fun speakable(text: String, maxSentences: Int = 3, maxChars: Int = 320): String {
        var t = codeBlock.replace(text, " (code shown on screen) ")
        t = mdLink.replace(t, "$1")
        t = mdChars.replace(t, "")
        t = bullet.replace(t, "")
        t = t.trim().split(Regex("\\s+")).joinToString(" ")
        val out = t.split(sentenceSplit).take(maxSentences).joinToString(" ")
        return out.take(maxChars).trimEnd()
    }

    /** Split into chunks the TTS engine can queue (engines cap utterance length, ~4000 chars). */
    fun chunks(text: String, max: Int = 400): List<String> {
        val result = mutableListOf<String>()
        var cur = StringBuilder()
        for (s in text.split(sentenceSplit)) {
            if (cur.isNotEmpty() && cur.length + s.length > max) { result += cur.toString().trim(); cur = StringBuilder() }
            cur.append(s).append(' ')
        }
        if (cur.isNotBlank()) result += cur.toString().trim()
        return result
    }
}

/** Spoken yes/no for approvals. Anything unclear is treated as NO (safe default). */
object YesNo {
    private val yes = Regex("^(yes|yeah|yep|yup|sure|ok|okay|go ahead|do it|approve|approved|confirm|please do)\\b", RegexOption.IGNORE_CASE)
    private val no = Regex("^(no|nope|nah|stop|cancel|don'?t|do not|deny|reject|never mind|negative)\\b", RegexOption.IGNORE_CASE)
    enum class Answer { YES, NO, UNCLEAR }
    fun parse(text: String): Answer {
        val t = text.trim()
        return when {
            no.containsMatchIn(t) -> Answer.NO       // check NO first: "no, do it later" must not read as yes
            yes.containsMatchIn(t) -> Answer.YES
            else -> Answer.UNCLEAR
        }
    }
}

/** Mirrors vhbrain.conductor.SENSITIVE / CORRECTION so the phone can decide instantly, without a Python round trip. */
object Sensitive {
    val REQUEST = Regex("\\b(pay|payment|buy|purchase|transfer|send money|delete|remove all|uninstall|send (a )?(message|email|text)|post|publish|install)\\b", RegexOption.IGNORE_CASE)
    val CORRECTION = Regex("^(no[,.! ]|not like that|that'?s wrong|wrong|don'?t |stop |next time|always |never |instead[, ]|actually[, ])", RegexOption.IGNORE_CASE)
}
