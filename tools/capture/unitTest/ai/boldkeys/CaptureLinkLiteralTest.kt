package ai.boldkeys

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Reproduce the observed exact value using the product's unmodified editing path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@LooperMode(LooperMode.Mode.PAUSED)
class CaptureLinkLiteralTest {
    private fun engine()=SpellEngine.fromTsv(File("src/main/assets/words.tsv").readText())
    private fun service(correct:Boolean):Pair<BoldKeysService,BaseInputConnection> {
        val connection=BaseInputConnection(View(RuntimeEnvironment.getApplication()),true)
        val service=BoldKeysService()
        ReflectionHelpers.setField(service,"mStartedInputConnection",connection)
        ReflectionHelpers.setField(service,"engine",engine())
        ReflectionHelpers.setField(service,"smart",true)
        ReflectionHelpers.setField(service,"urlLike",false)
        ReflectionHelpers.setField(service,"autocorrectOn",correct)
        ReflectionHelpers.setField(service,"emoticonsOn",false)
        return service to connection
    }
    @Test fun composeFalseAutocorrectHintDoesNotMarkFieldAsUriOrNoSuggestions() {
        val info=EditorInfo()
        val options=ImeOptions(singleLine=true,autoCorrect=false,capitalization=KeyboardCapitalization.None,keyboardType=KeyboardType.Text)
        Class.forName("androidx.compose.ui.text.input.TextInputServiceAndroid_androidKt")
            .getMethod("update",EditorInfo::class.java,ImeOptions::class.java,TextFieldValue::class.java)
            .invoke(null,info,options,TextFieldValue(""))
        assertEquals(InputType.TYPE_CLASS_TEXT,info.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(0,info.inputType and InputType.TYPE_MASK_VARIATION)
        assertEquals(0,info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(0,info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    }
    @Test fun firstPeriodRewritesWwwToWowWithGlobalAutocorrect() {
        val (service,connection)=service(true)
        "www".forEach { service.onText(it.toString()) }
        assertEquals("www",connection.editable.toString())
        service.onText(".")
        assertEquals("wow.",connection.editable.toString())
        "android.com".forEach { service.onText(it.toString()) }
        assertEquals("wow.android.com",connection.editable.toString())
        assertEquals("wow.android.",connection.editable.toString().take(12))
    }
    @Test fun literalUriFieldWithGlobalAutocorrectOnPreservesUrls() {
        for(value in listOf("www.android.com","i.example.com","https://www.android.com")) {
            val (service,connection)=service(true)
            ReflectionHelpers.setField(service,"urlLike",true)
            value.forEach { service.onText(it.toString()) }
            assertEquals(value,connection.editable.toString())
        }
    }
    @Test fun uriSingleIRemainsLiteral() {
        val (service,connection)=service(true)
        ReflectionHelpers.setField(service,"urlLike",true)
        "i.".forEach { service.onText(it.toString()) }
        assertEquals("i.",connection.editable.toString())
    }
    @Test fun composeUriOptionsMapToRealUriEditorInfo() {
        val info=EditorInfo()
        val options=ImeOptions(singleLine=true,autoCorrect=false,capitalization=KeyboardCapitalization.None,keyboardType=KeyboardType.Uri)
        Class.forName("androidx.compose.ui.text.input.TextInputServiceAndroid_androidKt")
            .getMethod("update",EditorInfo::class.java,ImeOptions::class.java,TextFieldValue::class.java)
            .invoke(null,info,options,TextFieldValue(""))
        assertEquals(InputType.TYPE_CLASS_TEXT,info.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_URI,info.inputType and InputType.TYPE_MASK_VARIATION)
        assertEquals(0,info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
    }
    @Test fun ordinaryTextCorrectionAndEnglishIStillWork() {
        val (service,connection)=service(true)
        "teh ".forEach { service.onText(it.toString()) }
        assertEquals("the ",connection.editable.toString())
        val (ordinary,ordinaryConnection)=service(true)
        "i ".forEach { ordinary.onText(it.toString()) }
        assertEquals("I ",ordinaryConnection.editable.toString())
    }
    @Test fun allThreeWRequestsMapToSameCorrectQwertyCell() {
        val rows=Layouts.rows("qwerty")
        val mapped="www".map { char -> rows.withIndex().firstNotNullOf { (r,row) ->
            row.indexOfFirst { it.label.equals(char.toString(),true) && it.kind==Kind.CHAR }.takeIf { it>=0 }?.let { r to it }
        } }
        assertEquals(listOf(0 to 1,0 to 1,0 to 1),mapped)
        assertEquals(".",secondaryFor(2,7))
    }
}
