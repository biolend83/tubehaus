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
import app.tubehaus.engine.Video
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
    center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ -> state.sync.refreshSoon() }
    // for the simulator check in CI: `-open <video id>` starts on that video
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    args.indexOf("-open").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let { id -> state.watch(Video(id, "", "", "", 0)) }
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
