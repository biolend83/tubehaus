package app.tubehaus.engine

/**
 * What runs SABR for [Sabr]: the page code common/sabr/page-src.mjs, where BotGuard makes the proof-of-origin
 * token and the page's fetch speaks SABR to the video servers as YouTube's web player does. Either in QuickJS
 * inside the app with jsdom as its document ([JsSabr], common/sabr/sabr-lite.js: no browser needed), or in a
 * browser page nobody sees whose address is on www.youtube.com (a WebView on Android and the iPad, Edge or
 * Chrome on a computer: common/sabr/sabr-page.js). Its pieces come back through [Sabr.piece] and [Sabr.end].
 */
interface SabrHost {
    /** the watch page's player answer as JSON text, or null when the page could not get one */
    suspend fun prepare(id: String, hl: String, gl: String): String?
    /** a caption file whose address wants a token, or null */
    suspend fun caption(id: String, url: String): String?
    /** starts fetch [run]: format [index] of the video's adaptiveFormats, from [startMs] */
    fun start(run: Int, id: String, index: Int, startMs: Long)
    /** fetch [run] may go on up to byte [upTo] of its format for now */
    fun allow(run: Int, upTo: Long)
    fun stop(run: Int)
    /** gets ready ahead of a video that may need it (a browser to start); nothing by default */
    suspend fun warm() {}
}
