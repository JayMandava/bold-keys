package ai.boldkeys

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The click: a short buzz and a short sound on every key press. This phone's motor can only switch on and off (no strength control),
 * so the feel comes down to how long and how many pulses; the lab in the setup screen lets you compare them by thumb.
 */
class Feedback(context: Context) {
    private val prefs = context.getSharedPreferences("bk", Context.MODE_PRIVATE)
    val vibrator: Vibrator = (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()
    private val click = pool.load(context, R.raw.key_click, 1)
    private val space = pool.load(context, R.raw.key_space, 1)

    var pattern: String
        get() = prefs.getString("pattern", "ms8")!!
        set(v) { prefs.edit().putString("pattern", v).apply() }
    var soundOn: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) { prefs.edit().putBoolean("sound", v).apply() }
    var volume: Float
        get() = prefs.getFloat("volume", 0.6f)
        set(v) { prefs.edit().putFloat("volume", v).apply() }

    private fun effect(name: String): VibrationEffect? = when (name) {
        "ms3" -> VibrationEffect.createOneShot(3, VibrationEffect.DEFAULT_AMPLITUDE)
        "ms5" -> VibrationEffect.createOneShot(5, VibrationEffect.DEFAULT_AMPLITUDE)
        "ms8" -> VibrationEffect.createOneShot(8, VibrationEffect.DEFAULT_AMPLITUDE)
        "ms12" -> VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
        "ms20" -> VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
        "double" -> VibrationEffect.createWaveform(longArrayOf(0, 4, 14, 4), -1)
        else -> null // "off"
    }

    fun buzz(name: String = pattern) { effect(name)?.let { vibrator.vibrate(it) } }

    fun sound(isSpace: Boolean = false) {
        if (soundOn) pool.play(if (isSpace) space else click, volume, volume, 1, 0, 1f)
    }

    fun key(isSpace: Boolean = false) { buzz(); sound(isSpace) }

    companion object {
        val PATTERNS = listOf("off" to "Off", "ms3" to "3 ms", "ms5" to "5 ms", "ms8" to "8 ms", "ms12" to "12 ms", "ms20" to "20 ms", "double" to "Double tap")
    }
}
