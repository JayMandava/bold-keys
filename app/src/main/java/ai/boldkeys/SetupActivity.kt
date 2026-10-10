package ai.boldkeys

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Turn the keyboard on, try it in a text box, and compare click feels by thumb. */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fb = Feedback(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)) }
        fun label(t: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = sp; if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(10), 0, dp(4))
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }

        col.addView(label("Bold Keys", 28f, true))
        col.addView(label("A first look: the layout, the key look, and the click.", 14f))
        col.addView(button("1. Turn the keyboard on") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        col.addView(button("2. Switch to Bold Keys") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() })
        col.addView(EditText(this).apply {
            hint = "Tap here and type"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = android.view.Gravity.TOP
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        col.addView(label("Feel lab", 20f, true))
        val motor = TextView(this).apply {
            text = "This phone's motor: " + if (fb.vibrator.hasAmplitudeControl()) "can change strength" else "on or off only (no strength control), so only the length and the number of pulses can change the feel."
            textSize = 13f
        }
        col.addView(motor)
        val current = label("", 14f)
        fun refresh() { current.text = "Used on every key: " + (Feedback.PATTERNS.firstOrNull { it.first == fb.pattern }?.second ?: fb.pattern) }
        refresh()
        col.addView(current)
        for ((key, name) in Feedback.PATTERNS) {
            col.addView(button(name) { fb.pattern = key; fb.buzz(key); refresh() })
        }
        col.addView(Switch(this).apply {
            text = "Key sound"; isChecked = fb.soundOn; setPadding(0, dp(12), 0, dp(4))
            setOnCheckedChangeListener { _, on -> fb.soundOn = on; if (on) fb.sound() }
        })
        col.addView(SeekBar(this).apply {
            max = 100; progress = (fb.volume * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) { fb.volume = p / 100f; fb.sound() } }
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
        })
        col.addView(button("Try the click") { fb.key() })

        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            v.setPadding(0, i.top, 0, i.bottom); insets
        }
        setContentView(scroll)
    }
}
