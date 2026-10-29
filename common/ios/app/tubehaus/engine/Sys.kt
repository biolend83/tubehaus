package app.tubehaus.engine

import coil3.ComponentRegistry
import coil3.network.ktor3.KtorNetworkFetcherFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.preferredLanguages
import platform.Foundation.timeIntervalSince1970
import kotlin.concurrent.AtomicReference

/** The few things the shared code needs from the system, Apple's way. The JVM twin is in common/jvm. */
object Sys {
    fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    val IO: CoroutineDispatcher = Dispatchers.IO

    private val iso = NSISO8601DateFormatter()
    private val isoFraction = NSISO8601DateFormatter().apply {
        formatOptions = NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds
    }

    fun isoSeconds(s: String): Long? =
        (iso.dateFromString(s) ?: isoFraction.dateFromString(s))?.timeIntervalSince1970?.toLong()

    fun today(): String = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd" }.stringFromDate(NSDate())

    fun shortTime(ms: Long): String = NSDateFormatter().apply {
        dateStyle = NSDateFormatterNoStyle
        timeStyle = NSDateFormatterShortStyle
    }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(ms / 1000.0))

    fun countryName(code: String, lang: String): String =
        NSLocale(localeIdentifier = lang).displayNameForKey(NSLocaleCountryCode, code) ?: code

    fun deviceLanguage(): String = (NSLocale.preferredLanguages.firstOrNull() as? String)?.substringBefore('-') ?: "en"
    fun deviceRegion(): String = (NSLocale.currentLocale.objectForKey(NSLocaleCountryCode) as? String) ?: ""

    fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = SnapshotMap()

    fun osName(): String = "iOS"
    fun osArch(): String = "arm64"

    fun imageNetwork(b: ComponentRegistry.Builder) {
        b.add(KtorNetworkFetcherFactory(httpClient = { Net.client }))
    }
}

/**
 * A map several threads may use at once: every change swaps in a new copy, readers see a whole
 * one. Fine for the small caches it holds (a few hundred entries, written rarely).
 */
private class SnapshotMap<K : Any, V : Any> : AbstractMutableMap<K, V>() {
    private val ref = AtomicReference<Map<K, V>>(emptyMap())

    override val size: Int get() = ref.value.size
    override fun get(key: K): V? = ref.value[key]
    override fun containsKey(key: K): Boolean = key in ref.value

    override fun put(key: K, value: V): V? {
        while (true) {
            val old = ref.value
            if (ref.compareAndSet(old, old + (key to value))) return old[key]
        }
    }

    override fun remove(key: K): V? {
        while (true) {
            val old = ref.value
            if (key !in old) return null
            if (ref.compareAndSet(old, old - key)) return old[key]
        }
    }

    override fun clear() { ref.value = emptyMap() }

    /** a copy: reading it is safe, changing it changes nothing */
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = ref.value.toMutableMap().entries
}

/** a lock for a few lines of blocking code (a file write) */
class SysLock {
    private val lock = platform.Foundation.NSRecursiveLock()
    fun <T> run(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
