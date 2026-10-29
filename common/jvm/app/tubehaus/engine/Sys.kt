package app.tubehaus.engine

import coil3.ComponentRegistry
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The few things the shared code needs from the system, Java's way (Android and the desktop).
 * iOS has its own Sys.kt with the same names (common/ios).
 */
object Sys {
    fun nowMs(): Long = System.currentTimeMillis()

    /** for blocking work: files, and the network on the desktop/Android */
    val IO: CoroutineDispatcher = Dispatchers.IO

    /** "2026-10-28T12:00:00+00:00" → epoch seconds */
    fun isoSeconds(s: String): Long? = runCatching { java.time.OffsetDateTime.parse(s).toEpochSecond() }.getOrNull()

    /** "2026-10-28", for file names */
    fun today(): String = java.time.LocalDate.now().toString()

    /** "13:05" (or "1:05 PM"), the device's way */
    fun shortTime(ms: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ms))

    /** "KR" → "South Korea" (or "대한민국" when [lang] is "ko") */
    fun countryName(code: String, lang: String): String = Locale("", code).getDisplayCountry(Locale(lang)).ifEmpty { code }

    fun deviceLanguage(): String = Locale.getDefault().language
    fun deviceRegion(): String = Locale.getDefault().country

    fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = ConcurrentHashMap()

    /** the operating system, for the update check: "Windows 11", "Mac OS X", "Linux" */
    fun osName(): String = System.getProperty("os.name") ?: ""
    fun osArch(): String = System.getProperty("os.arch") ?: ""

    /** images come through the app's one HTTP client */
    fun imageNetwork(b: ComponentRegistry.Builder) {
        b.add(OkHttpNetworkFetcherFactory(callFactory = { Net.client }))
    }
}
