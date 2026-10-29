package ai.boldkeys

import android.app.Activity
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class CaptureSentenceFieldTest {
    @Test fun actualEditorCapsContractNeedsSentenceFlag() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val editor=EditText(controller.get());controller.get().setContentView(editor)
            val base=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            for(flag in listOf(0,InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)) {
                editor.inputType=base or flag;editor.setText("hello. ");editor.setSelection(editor.length())
                val info=EditorInfo();val connection=editor.onCreateInputConnection(info)!!
                val caps=connection.getCursorCapsMode(info.inputType)
                if(flag==0) assertEquals(0,caps) else assertTrue(caps and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0)
            }
        } finally { controller.pause().stop().destroy() }
    }
}
