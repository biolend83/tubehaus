package app.tubehaus.engine

import okio.FileSystem
import okio.Path

// Small files through okio, which works the same on Android, the desktop and iOS.

/** the file's text, or null when there is no such file */
fun Path.readTextOrNull(): String? = if (FileSystem.SYSTEM.exists(this)) FileSystem.SYSTEM.read(this) { readUtf8() } else null

/** writes [text], making the folder first */
fun Path.writeText(text: String) {
    parent?.let { FileSystem.SYSTEM.createDirectories(it) }
    FileSystem.SYSTEM.write(this) { writeUtf8(text) }
}
