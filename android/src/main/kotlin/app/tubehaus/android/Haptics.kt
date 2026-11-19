package app.tubehaus.android

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * The app's taps and thumps played on the vibration motor itself. Compose's own path goes through
 * the view's haptic constants, which many phones leave silent (the newer ones such as SEGMENT_TICK
 * or GESTURE_END have no effect on a lot of them), and which the system's "touch vibration" switch
 * mutes entirely. Here each kind is a predefined effect (a tick, a click, a heavy click), played as
 * touch feedback when the system's switch is on and as media vibration when it is off, so it still
 * follows the main vibration switch and battery saver.
 */
class Haptics(private val context: Context) {
    private val vibrator: Vibrator? by lazy {
        val v = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        v?.takeIf { it.hasVibrator() }
    }

    private enum class Kind { LightTick, Tick, Click, Heavy, Double }

    private fun kind(t: HapticFeedbackType): Kind? = when (t) {
        HapticFeedbackType.SegmentFrequentTick, HapticFeedbackType.TextHandleMove -> Kind.LightTick
        HapticFeedbackType.SegmentTick, HapticFeedbackType.GestureThresholdActivate, HapticFeedbackType.ContextClick,
        HapticFeedbackType.ToggleOff, HapticFeedbackType.VirtualKey -> Kind.Tick
        HapticFeedbackType.ToggleOn, HapticFeedbackType.GestureEnd -> Kind.Click
        HapticFeedbackType.LongPress -> Kind.Heavy
        HapticFeedbackType.Confirm, HapticFeedbackType.Reject -> Kind.Double
        else -> null
    }

    /** true when it played; false leaves it to the system's own path */
    fun play(t: HapticFeedbackType): Boolean {
        val v = vibrator ?: return false
        if (Build.VERSION.SDK_INT < 26) return false
        val k = kind(t) ?: return false
        return runCatching {
            val effect = if (Build.VERSION.SDK_INT >= 29) {
                val prim = if (Build.VERSION.SDK_INT >= 30) when (k) {
                    Kind.LightTick -> VibrationEffect.Composition.PRIMITIVE_TICK to 0.35f
                    Kind.Tick -> VibrationEffect.Composition.PRIMITIVE_TICK to 0.7f
                    Kind.Click -> VibrationEffect.Composition.PRIMITIVE_CLICK to 0.7f
                    Kind.Heavy -> VibrationEffect.Composition.PRIMITIVE_CLICK to 1f
                    Kind.Double -> null
                } else null
                if (prim != null && v.areAllPrimitivesSupported(prim.first))
                    VibrationEffect.startComposition().addPrimitive(prim.first, prim.second).compose()
                else VibrationEffect.createPredefined(when (k) {
                    Kind.LightTick, Kind.Tick -> VibrationEffect.EFFECT_TICK
                    Kind.Click -> VibrationEffect.EFFECT_CLICK
                    Kind.Heavy -> VibrationEffect.EFFECT_HEAVY_CLICK
                    Kind.Double -> VibrationEffect.EFFECT_DOUBLE_CLICK
                })
            } else {
                // Android 8 and 9: no predefined effects, a short pulse of the right strength
                val (ms, amp) = when (k) { Kind.LightTick -> 8L to 60; Kind.Tick -> 10L to 110; Kind.Click -> 16L to 180; Kind.Heavy -> 28L to 255; Kind.Double -> 18L to 200 }
                if (k == Kind.Double) VibrationEffect.createWaveform(longArrayOf(0, ms, 70, ms), intArrayOf(0, amp, 0, amp), -1)
                else VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) amp else VibrationEffect.DEFAULT_AMPLITUDE)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val touchOn = Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
                v.vibrate(effect, VibrationAttributes.createForUsage(if (touchOn) VibrationAttributes.USAGE_TOUCH else VibrationAttributes.USAGE_MEDIA))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            }
            true
        }.getOrDefault(false)
    }
}
