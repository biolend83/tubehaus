package app.tubehaus.patch

/**
 * A function the iPad app's patch file (patch.js) may replace without a new build. The TubePatch
 * compiler plugin (ios/patch/plugin, used only by the iPad build) puts a check at its start;
 * on Android and the desktop this annotation does nothing. Only for functions that take and
 * give plain values (text, numbers, true/false, lists of those): JavaScript has to produce them.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Patchable

/** what the patch runtime answers when there is no replacement: the function runs as built */
object NotPatched
