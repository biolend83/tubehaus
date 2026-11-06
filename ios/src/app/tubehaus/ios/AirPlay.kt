package app.tubehaus.ios

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import platform.AVKit.AVRoutePickerView
import platform.UIKit.UIColor

/**
 * The system's AirPlay button, in the player's controls where Android and the desktop have
 * their cast button: it opens iOS's own list of Apple TVs and AirPlay TVs, and the video
 * (AVPlayer, which allows external playback) goes there. Nothing else is needed on our side.
 */
@Composable
fun AirPlayButton(modifier: Modifier) {
    UIKitView(
        factory = {
            AVRoutePickerView().apply {
                backgroundColor = UIColor.clearColor
                tintColor = UIColor.whiteColor
                activeTintColor = UIColor.colorWithRed(0.0, 0.48, 1.0, 1.0)   // the system blue
                prioritizesVideoDevices = true
            }
        },
        modifier = modifier,
    )
}
