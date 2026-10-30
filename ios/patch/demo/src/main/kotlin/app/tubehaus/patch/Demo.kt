package app.tubehaus.patch

/** A function the patch file may replace. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Patchable

/** returned by [TubePatch.call] when there is no replacement: the function runs as compiled */
object NotPatched

/**
 * The patch runtime. On the iPad this runs JavaScript from Files → TubeHaus → patch.js; in this
 * JVM demo the replacements are plain Kotlin lambdas, enough to show the compiled-in check works.
 */
object TubePatch {
    val replacements = HashMap<String, (Array<Any?>) -> Any?>()

    fun call(id: String, returns: String, args: Array<Any?>): Any? {
        val f = replacements[id] ?: return NotPatched
        println("  [patch] $id(${args.joinToString()}) → $returns")
        return f(args)
    }
}

// ---------------------------------------------------------------- functions of the "app"
@Patchable
fun greet(name: String): String = "Hello, $name"

@Patchable
fun score(views: Long, likes: Int): Double {
    val s = likes * 100.0 / (views + 1)
    return s
}

class Feed {
    @Patchable
    fun sort(titles: List<String>): List<String> = titles.sorted()
}

@Patchable
fun log(msg: String) {
    println("  log: $msg")
}

fun main() {
    println("— as compiled")
    println(greet("iPad"))
    println(score(1000, 50))
    println(Feed().sort(listOf("b", "a", "c")))
    log("hi")

    println("— with patches")
    TubePatch.replacements["app.tubehaus.patch.greet"] = { a -> "안녕, ${a[0]}!" }
    TubePatch.replacements["app.tubehaus.patch.score"] = { a -> (a[1] as Int) * 2.0 }
    TubePatch.replacements["app.tubehaus.patch.Feed.sort"] = { a -> (a[0] as List<*>).map { it.toString() }.sortedDescending() }
    TubePatch.replacements["app.tubehaus.patch.log"] = { a -> println("  patched log: ${a[0]}"); Unit }
    println(greet("iPad"))
    println(score(1000, 50))
    println(Feed().sort(listOf("b", "a", "c")))
    log("hi")
}
