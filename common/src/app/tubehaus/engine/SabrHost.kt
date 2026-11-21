package app.tubehaus.engine

/**
 * What runs SABR for [Sabr]: a browser page nobody sees whose address is on www.youtube.com (a WebView on
 * Android and the iPad, Edge or Chrome on a computer). There YouTube's own web player code paths work as they do on
 * YouTube: BotGuard makes the proof-of-origin token, and the page's fetch speaks SABR to the video servers.
 * The page is common/sabr/sabr-page.js; its pieces come back through [Sabr.piece] and [Sabr.end].
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
