package app.tubehaus.ios

import app.tubehaus.ui.Translated
import kotlinx.coroutines.CompletableDeferred

/**
 * Apple's Translation framework is Swift only, so the Swift side (ios/app/App.swift) does the
 * work: it implements this and sets [CommentTranslation.translator] before the app's screen is made.
 */
interface CommentTranslator {
    /** translates [text] into [target] (e.g. "ko") and answers through [reply], once, on any thread */
    fun translate(text: String, target: String, reply: TranslationReply)
}

/** how the Swift side answers a [CommentTranslator.translate]; only the first answer counts */
class TranslationReply internal constructor() {
    internal val answer = CompletableDeferred<Translated>()

    fun text(translated: String) { answer.complete(Translated.Text(translated)) }
    fun sameLanguage() { answer.complete(Translated.SameLanguage) }
    fun failed(reason: String) {
        println("translate: $reason")
        answer.complete(Translated.Failed)
    }
}

/** where Swift hands its translator over; none (iOS before 18) hides the "Translate" links */
object CommentTranslation {
    var translator: CommentTranslator? = null

    internal suspend fun translate(text: String, target: String): Translated {
        val t = translator ?: return Translated.Failed
        val reply = TranslationReply()
        t.translate(text, target, reply)
        return reply.answer.await()
    }
}
