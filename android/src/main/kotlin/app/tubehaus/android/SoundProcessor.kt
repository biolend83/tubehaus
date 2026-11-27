package app.tubehaus.android

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.tubehaus.ui.SOUND_BANDS
import app.tubehaus.ui.SoundFx
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sound in ExoPlayer: the level (loudness matching), a 10-band equalizer (the same filters as the web's
 * Web Audio ones), stereo width, balance and a night-mode compressor, on 16-bit PCM between the decoder and
 * the speaker. [fx] can change while playing; the level glides so a change doesn't click.
 */
@OptIn(UnstableApi::class)
class SoundProcessor : BaseAudioProcessor() {
    @Volatile var fx: SoundFx = SoundFx()

    private var rate = 48000
    private var ch = 2
    private var built: SoundFx? = null
    // the biquads: per band 5 coefficients, per band and channel 4 state values
    private var coef = DoubleArray(0)
    private var state = DoubleArray(0)
    private var bands = 0
    private var level = 1.0
    private var target = 1.0
    private var a = 1.0
    private var b = 0.0
    private var gl = 1.0
    private var gr = 1.0
    private var night = false
    private var env = 0.0
    private var pcm = ShortArray(0)

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT || input.channelCount < 1) return AudioProcessor.AudioFormat.NOT_SET
        rate = input.sampleRate
        ch = input.channelCount
        built = null
        return input
    }

    override fun onFlush() { state.fill(0.0); env = 0.0 }
    override fun onReset() { built = null; state = DoubleArray(0) }

    private fun build(f: SoundFx) {
        built = f
        val gains = f.eq
        bands = if (gains.size == SOUND_BANDS.size && gains.any { it != 0f }) SOUND_BANDS.size else 0
        coef = DoubleArray(bands * 5)
        if (state.size != bands * ch * 4) state = DoubleArray(bands * ch * 4)
        for (i in 0 until bands) {
            val hz = SOUND_BANDS[i].toDouble().coerceAtMost(rate * 0.45)
            val kind = if (i == 0) 1 else if (i == bands - 1) 2 else 0
            biquad(kind, hz, gains[i].toDouble(), 1.1, i * 5)
        }
        target = 10.0.pow((f.gainDb + if (bands > 0) f.headroomDb else 0f) / 20.0)
        val w = f.width / 100.0
        a = (1 + w) / 2; b = (1 - w) / 2
        val bal = f.balance / 100.0
        gl = if (bal > 0) 1 - bal else 1.0
        gr = if (bal < 0) 1 + bal else 1.0
        night = f.night
    }

    /** RBJ's cookbook filters, as Web Audio makes them: 0 peaking (Q), 1 low shelf, 2 high shelf (slope 1) */
    private fun biquad(kind: Int, hz: Double, db: Double, q: Double, at: Int) {
        val A = 10.0.pow(db / 40)
        val w0 = 2 * PI * hz / rate
        val cs = cos(w0); val sn = sin(w0)
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        when (kind) {
            0 -> {
                val alpha = sn / (2 * q)
                b0 = 1 + alpha * A; b1 = -2 * cs; b2 = 1 - alpha * A
                a0 = 1 + alpha / A; a1 = -2 * cs; a2 = 1 - alpha / A
            }
            else -> {
                val alpha = sn / 2 * sqrt(2.0)
                val sa = 2 * sqrt(A) * alpha
                if (kind == 1) {
                    b0 = A * ((A + 1) - (A - 1) * cs + sa); b1 = 2 * A * ((A - 1) - (A + 1) * cs); b2 = A * ((A + 1) - (A - 1) * cs - sa)
                    a0 = (A + 1) + (A - 1) * cs + sa; a1 = -2 * ((A - 1) + (A + 1) * cs); a2 = (A + 1) + (A - 1) * cs - sa
                } else {
                    b0 = A * ((A + 1) + (A - 1) * cs + sa); b1 = -2 * A * ((A - 1) + (A + 1) * cs); b2 = A * ((A + 1) + (A - 1) * cs - sa)
                    a0 = (A + 1) - (A - 1) * cs + sa; a1 = 2 * ((A - 1) - (A + 1) * cs); a2 = (A + 1) - (A - 1) * cs - sa
                }
            }
        }
        coef[at] = b0 / a0; coef[at + 1] = b1 / a0; coef[at + 2] = b2 / a0; coef[at + 3] = a1 / a0; coef[at + 4] = a2 / a0
    }

    override fun queueInput(input: ByteBuffer) {
        val f = fx
        if (f !== built) build(f)
        // the samples are read out first: the buffer handed in can be the one this processor writes to
        val n = input.remaining() / 2
        if (pcm.size < n) pcm = ShortArray(n)
        input.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm, 0, n)
        input.position(input.limit())
        val out = replaceOutputBuffer(n * 2)
        if (f.neutral && abs(level - 1.0) < 1e-4) {
            level = 1.0
            out.asShortBuffer().put(pcm, 0, n)
            out.position(n * 2); out.flip()
            return
        }
        var at = 0
        val frames = n / ch
        val x = DoubleArray(ch)
        val stereo = ch == 2
        // the compressor: attack 5 ms, release 250 ms, -28 dB threshold, 4:1, +7 dB after
        val att = exp(-1.0 / (0.005 * rate)); val rel = exp(-1.0 / (0.25 * rate))
        val thr = -28.0; val makeup = 10.0.pow(7.0 / 20)
        for (fr in 0 until frames) {
            level += (target - level) * 0.002
            for (c in 0 until ch) x[c] = pcm[at++].toDouble() / 32768.0 * level
            // the equalizer, band after band
            for (i in 0 until bands) {
                val k = i * 5
                for (c in 0 until ch) {
                    val s = (i * ch + c) * 4
                    val y = coef[k] * x[c] + coef[k + 1] * state[s] + coef[k + 2] * state[s + 1] - coef[k + 3] * state[s + 2] - coef[k + 4] * state[s + 3]
                    state[s + 1] = state[s]; state[s] = x[c]; state[s + 3] = state[s + 2]; state[s + 2] = y
                    x[c] = y
                }
            }
            if (stereo) {
                val l = x[0]; val r = x[1]
                x[0] = (a * l + b * r) * gl
                x[1] = (b * l + a * r) * gr
            }
            if (night) {
                var peak = 0.0
                for (c in 0 until ch) peak = max(peak, abs(x[c]))
                env = if (peak > env) att * env + (1 - att) * peak else rel * env + (1 - rel) * peak
                val db = 20 * log10(max(env, 1e-6))
                val g = if (db > thr) 10.0.pow((thr + (db - thr) / 4 - db) / 20) else 1.0
                for (c in 0 until ch) x[c] *= g * makeup
            }
            for (c in 0 until ch) {
                val v = (x[c] * 32767.0).coerceIn(-32768.0, 32767.0)
                out.putShort(v.toInt().toShort())
            }
        }
        out.flip()
    }
}
