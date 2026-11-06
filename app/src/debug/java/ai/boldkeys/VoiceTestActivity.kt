package ai.boldkeys

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only emulator editor fixture. No recording, transcript logging, or release entry point. */
class VoiceTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "Voice test editor" })
            addView(EditText(context).apply {
                id = android.view.View.generateViewId(); hint = "Normal text"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            })
            addView(EditText(context).apply {
                id = android.view.View.generateViewId(); hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            })
        })
    }
}
