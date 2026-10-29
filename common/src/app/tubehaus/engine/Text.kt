package app.tubehaus.engine

import kotlin.math.abs
import kotlin.math.roundToLong

// Text helpers in plain Kotlin, so the shared code runs on iOS too (no String.format, no java.net).

/** 7 → "07" */
fun pad2(n: Long): String = n.toString().padStart(2, '0')
fun pad2(n: Int): String = pad2(n.toLong())

/** one decimal place: 1.25 → "1.3", 3.0 → "3.0" */
fun oneDecimal(x: Double): String {
    val t = (abs(x) * 10).roundToLong()
    return (if (x < 0 && t != 0L) "-" else "") + "${t / 10}.${t % 10}"
}

/** bytes as lowercase hex */
fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** application/x-www-form-urlencoded, as java.net.URLEncoder does it */
fun urlEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = (b.toInt() and 0xff)
        when {
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c in "-_.*".map { it.code } -> append(c.toChar())
            c == ' '.code -> append('+')
            else -> append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

/** the reverse: %xx and + decoded as UTF-8 */
fun urlDecode(s: String): String {
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '+' -> { out += ' '.code.toByte(); i++ }
            c == '%' && i + 2 < s.length -> {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out += v.toByte(); i += 3 } else { out += c.code.toByte(); i++ }
            }
            else -> { out.addAll(c.toString().encodeToByteArray().toList()); i++ }
        }
    }
    return out.toByteArray().decodeToString()
}
