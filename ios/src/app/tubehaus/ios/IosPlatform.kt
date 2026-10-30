package app.tubehaus.ios

import app.tubehaus.ui.Platform
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.CoreGraphics.CGRectMake
import kotlinx.cinterop.useContents

/** What iOS supplies: folders, the share sheet, Files for import/export, the screen brightness. */
class IosPlatform : Platform {
    override val name = "ios"
    override val dataDir: Path = folder(NSApplicationSupportDirectory) / "tubehaus"
    override val cacheDir: Path = folder(NSCachesDirectory)
    override val touch = true
    override val player = IosPlayer()

    /** the window's view controller, to present sheets from; set by MainViewController */
    var root: UIViewController? = null

    // the document picker holds its delegate weakly: keep it alive while the picker is open
    private var picker: PickerDelegate? = null

    private fun folder(kind: ULong): Path =
        ((NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String) ?: NSTemporaryDirectory()).toPath()

    /** the controller on top (a sheet may already be showing) */
    private fun top(): UIViewController? {
        var vc = root ?: UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (vc.presentedViewController != null) vc = vc.presentedViewController!!
        return vc
    }

    /** iPad sheets are popovers and need a place to point at: the middle of the screen */
    private fun present(vc: UIViewController) {
        val host = top() ?: return
        vc.popoverPresentationController?.let { p ->
            p.sourceView = host.view
            host.view.bounds.useContents { p.sourceRect = CGRectMake(size.width / 2, size.height / 2, 1.0, 1.0) }
            p.permittedArrowDirections = 0uL
        }
        host.presentViewController(vc, animated = true, completion = null)
    }

    override fun share(title: String, url: String): Boolean {
        present(UIActivityViewController(activityItems = listOf(url), applicationActivities = null))
        return true
    }

    override fun openExternal(url: String) {
        val u = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(u, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    /** writes the file to a temporary place, then Files asks where it goes */
    override fun saveFile(name: String, mime: String, bytes: ByteArray, done: (Boolean) -> Unit) {
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + name.substringAfterLast('/'))
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        if (!data.writeToURL(url, atomically = true)) { done(false); return }
        val vc = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
        picker = PickerDelegate { picked -> picker = null; done(picked != null) }
        vc.delegate = picker
        present(vc)
    }

    override fun openTextFile(done: (String?) -> Unit) {
        val vc = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
        picker = PickerDelegate { url ->
            picker = null
            done(url?.let { NSString.stringWithContentsOfURL(it, encoding = NSUTF8StringEncoding, error = null) })
        }
        vc.delegate = picker
        present(vc)
    }

    // the app draws full screen itself; the status bar is already hidden there by the system on iPad
    override fun setFullscreen(on: Boolean) {}

    override fun brightness(): Float = UIScreen.mainScreen.brightness.toFloat()
    override fun setBrightness(v: Float) { UIScreen.mainScreen.brightness = v.toDouble().coerceIn(0.0, 1.0) }
}

private class PickerDelegate(val picked: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        picked(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        picked(null)
    }
}
