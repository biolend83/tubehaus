package app.tubehaus.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeUIViewController
import app.tubehaus.data.Store
import app.tubehaus.engine.Engine
import app.tubehaus.patch.TubePatch
import app.tubehaus.engine.Video
import kotlinx.coroutines.launch
import app.tubehaus.ui.App
import app.tubehaus.ui.AppState
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIViewController

/** The app, for the Swift side (ios/app/App.swift): one Compose screen in a view controller. */
fun MainViewController(): UIViewController {
    val platform = IosPlatform()
    val state = AppState(platform, Store(platform.dataDir))
    val center = NSNotificationCenter.defaultCenter
    // written to disk before iOS may end the app; synced when it comes back
    center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ -> state.store.flush() }
    center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
        state.sync.refreshSoon()
        state.scope.launch { Engine.update(); TubePatch.update() }
    }
    // the YouTube engine (engine.js): a note in its folder, and a newer one from GitHub if there is one
    writeFolderNote()
    TubePatch.load()
    state.scope.launch { Engine.update(); TubePatch.update() }
    // for the simulator check in CI: `-open <video id>` starts on that video
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    args.indexOf("-open").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let { id -> state.watch(Video(id, "", "", "", 0)) }
    // `-stress`: what a person does over and over (another video, full screen on and off), for the CI's screenshots
    if ("-stress" in args) state.scope.launch {
        kotlinx.coroutines.delay(5_000)
        repeat(2) {
            for (id in listOf("jNQXAC9IVRw", "aqz-KE-bpKQ", "dQw4w9WgXcQ")) {
                state.watch(Video(id, "", "", "", 0)); kotlinx.coroutines.delay(12_000)
                state.setFull(true); kotlinx.coroutines.delay(4_000)
                state.setFull(false); kotlinx.coroutines.delay(3_000)
            }
        }
    }
    return ComposeUIViewController {
        val settings by state.store.settings.state.collectAsState()
        // outside full screen the app stays clear of the status bar and the home indicator;
        // the strip behind them takes the theme's background
        val dark = when (settings.dark) { 1 -> false; 2, 3 -> true; else -> isSystemInDarkTheme() }
        val edge = when { settings.dark == 3 -> Color.Black; dark -> Color(0xFF0F0F0F); else -> Color.White }
        Box(Modifier.fillMaxSize().background(edge).let { if (state.fullscreen) it else it.windowInsetsPadding(WindowInsets.safeDrawing) }) {
            App(state)
        }
    }.also { platform.root = it }
}

/** what the TubeHaus folder in Files is for */
private fun writeFolderNote() {
    val path = with(okio.Path.Companion) { (Engine.folder + "/README.txt").toPath() }
    if (okio.FileSystem.SYSTEM.exists(path)) return
    val note = "TubeHaus keeps its YouTube engine here (engine.js).\n\n" +
        "The app checks GitHub every few hours and puts a newer engine.js here by itself, so a fix for YouTube\n" +
        "reaches the iPad without reinstalling the app. You can also put an engine.js here yourself: the one\n" +
        "with the higher ENGINE_VERSION (this one or the app's built-in one) is used. Delete it to go back to\n" +
        "the built-in engine.\n\n" +
        "patch.js works the same way: replacements for some of the app's own functions (TubePatch).\n"
    runCatching { okio.FileSystem.SYSTEM.write(path) { writeUtf8(note) } }
}
