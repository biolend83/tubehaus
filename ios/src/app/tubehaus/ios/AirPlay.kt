package app.tubehaus.ios

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import app.tubehaus.ui.Sym
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.AVKit.AVRoutePickerView
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIButton
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIViewController

/**
 * The AirPlay button in the player's controls, where Android and the desktop have their cast
 * button: it opens iOS's own list of Apple TVs and AirPlay TVs, and the video (AVPlayer, which
 * allows external playback) goes there.
 *
 * The button is drawn by Compose like the other controls, so it has their colour and fades with
 * them (#55). The system's button (AVRoutePickerView) drawn in its place was a native view seen
 * through a hole in Compose's layer: brighter than the rest and still there after the controls had
 * faded. The system's button is still what opens the list (there is no other way to open it): one
 * sits out of sight in the window and a tap here presses it.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
fun AirPlayButton(modifier: Modifier, player: IosPlayer) {
    val vc = LocalUIViewController.current
    // blue while the video plays on a TV, as the system's button was
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(player) { while (true) { on = player.av.isExternalPlaybackActive(); delay(1000) } }
    IconButton(onClick = { openRoutes(vc) }, modifier = modifier) {
        Icon(if (on) Sym.AirPlay.fill else Sym.AirPlay.outline, "AirPlay",
            tint = if (on) Color(0xFF007AFF) else Color.White, modifier = Modifier.size(24.dp))
    }
}

private var picker: AVRoutePickerView? = null

@OptIn(ExperimentalForeignApi::class)
private fun openRoutes(vc: UIViewController) {
    val p = picker?.takeIf { it.window != null } ?: AVRoutePickerView(frame = CGRectMake(-100.0, -100.0, 1.0, 1.0)).also {
        it.prioritizesVideoDevices = true
        vc.view.addSubview(it)
        picker = it
    }
    (p.subviews.firstOrNull { it is UIButton } as? UIButton)?.sendActionsForControlEvents(UIControlEventTouchUpInside)
}
