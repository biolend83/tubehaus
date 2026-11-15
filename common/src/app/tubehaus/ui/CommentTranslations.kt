package app.tubehaus.ui

import androidx.compose.runtime.mutableStateMapOf

/** at least this share of a text's letters in the language's own script counts as that language */
private const val SAME_SCRIPT_SHARE = 0.5

/**
 * True when [text] needs no translation into [lang]: no letters at all (emoji, numbers), or mostly
 * letters of a script only that language uses (Hangul for Korean). A single pass over the
 * characters, so it can run for every comment shown; languages sharing a script (English,
 * Spanish...) can't be told apart this way and keep the link (the translator says so on a click).
 */
fun needsNoTranslation(text: String, lang: String): Boolean {
    val own: ((Char) -> Boolean) = when (lang) {
        "ko" -> ::isHangul
        else -> return text.none { it.isLetter() }
    }
    var letters = 0
    var ownLetters = 0
    for (ch in text) {
        if (!ch.isLetter()) continue
        letters++
        if (own(ch)) ownLetters++
    }
    return letters == 0 || ownLetters >= letters * SAME_SCRIPT_SHARE
}

// syllables, jamo and compatibility jamo (ㅋㅋ, ㅠㅠ)
private fun isHangul(ch: Char) = ch in '가'..'힣' || ch in 'ᄀ'..'ᇿ' || ch in '㄰'..'㆏'

/**
 * The comments translated so far, by comment id, and which of them show the translation now.
 * Kept by [AppState] (not by each comment's row) so a comment scrolled away and back keeps it.
 */
class CommentTranslations {
    private val texts = mutableStateMapOf<String, String>()
    private val shown = mutableStateMapOf<String, Boolean>()
    private val working = mutableStateMapOf<String, Boolean>()

    /** the translation of comment [id] while it is shown, else null (the original is shown) */
    fun shownText(id: String): String? = texts[id]?.takeIf { shown[id] == true }

    fun busy(id: String): Boolean = working[id] == true

    /**
     * "Translate" / "Show original": the first click asks [platform] (the answer is kept), later
     * ones only switch between the two; what went wrong goes to [say].
     */
    suspend fun toggle(platform: Platform, id: String, text: String, target: String, say: (String) -> Unit) {
        if (busy(id)) return
        if (texts.containsKey(id)) {
            shown[id] = shown[id] != true
            return
        }
        working[id] = true
        val result = try {
            platform.translate(text, target)
        } finally {
            working.remove(id)
        }
        when (result) {
            is Translated.Text -> { texts[id] = result.text; shown[id] = true }
            Translated.SameLanguage -> say(tr("This comment is in your language already"))
            Translated.NeedsDownload -> say(tr("Add this language in System Settings → General → Language & Region → Translation Languages"))
            Translated.Failed -> say(tr("Couldn't translate this comment"))
        }
    }
}
