package ai.boldkeys

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The click: a short buzz on every key press. This phone's motor can only switch on and off (no strength control),
 * so the feel comes down to how long and how many pulses; the lab in the setup screen lets you compare them by thumb.
 */
class Feedback(context: Context) {
    private val prefs = context.getSharedPreferences("bk", Context.MODE_PRIVATE)
    val vibrator: Vibrator = (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    var pattern: String
        get() = prefs.getString("pattern", "ms8")!!
        set(v) { prefs.edit().putString("pattern", v).apply() }

    /** The buzz is handed to its own thread: asking the vibrator is a call into the system that can take several milliseconds, and it must not hold up the touch it answers. */
    private val haptic = Handler(HandlerThread("bk-haptic").apply { start() }.looper)
    private val effects = HashMap<String, VibrationEffect?>()
    private fun effect(name: String): VibrationEffect? = effects.getOrPut(name) { build(name) }

    private fun build(name: String): VibrationEffect? = when (name) {
        "ms3" -> VibrationEffect.createOneShot(3, VibrationEffect.DEFAULT_AMPLITUDE)
        "ms5" -> VibrationEffect.createOneShot(5, VibrationEffect.DEFAULT_AMPLITUDE)
        "ms8" -> VibrationEffect.createOneShot(8, VibrationEffect.DEFAULT_AMPLITUDE)
        "ms12" -> VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
        "ms20" -> VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
        "double" -> VibrationEffect.createWaveform(longArrayOf(0, 4, 14, 4), -1)
        else -> null // "off"
    }

    fun buzz(name: String = pattern) { effect(name)?.let { e -> haptic.post { vibrator.vibrate(e) } } }

    /** A key press: the buzz, and nothing else. The real keys click under the thumb; there is no sound to switch on or off. */
    fun key() { buzz() }

    companion object {
        val PATTERNS = listOf("off" to "Off", "ms3" to "3 ms", "ms5" to "5 ms", "ms8" to "8 ms", "ms12" to "12 ms", "ms20" to "20 ms", "double" to "Double tap")
    }
}
