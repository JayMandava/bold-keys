package ai.boldkeys

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/** Debug-only synthetic editor for real IME integration tests. Not present in release APKs. */
class KeyboardQaActivity : Activity() {
    lateinit var editor: EditText
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        val dark = resources.configuration.uiMode and 48 == 32
        val ink = if (dark) 0xFFF3F1EC.toInt() else 0xFF202124.toInt()
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val mode = intent.getStringExtra("mode") ?: "text"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (dark) 0xFF17181A.toInt() else 0xFFFAF9F6.toInt())
            setPadding(padding, padding, padding, padding)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
                insets
            }
        }
        root.addView(TextView(this).apply {
            text = "Keyboard QA · $mode\nSynthetic input only"
            textSize = 16f
            setTextColor(ink)
        })
        editor = EditText(this).apply {
            id = View.generateViewId()
            setTextColor(ink)
            setHintTextColor(if (dark) 0xFFAAA8A3.toInt() else 0xFF66635E.toInt())
            hint = "Type here"
            textSize = 20f
            inputType = when (mode) {
                "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                "number" -> InputType.TYPE_CLASS_NUMBER
                "search" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            if (mode == "search") imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        root.addView(editor, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        if (savedInstanceState != null) {
            editor.setText(savedInstanceState.getString("text", ""))
            editor.setSelection(savedInstanceState.getInt("cursor", 0).coerceIn(0, editor.length()))
        }
        editor.requestFocus()
        editor.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::editor.isInitialized) editor.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("text", editor.text.toString())
        outState.putInt("cursor", editor.selectionStart)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (current.get() === this) current.clear()
        super.onDestroy()
    }

    companion object {
        @Volatile var current = WeakReference<KeyboardQaActivity>(null)
            private set
    }
}
