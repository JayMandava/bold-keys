package ai.boldkeys

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36], qualifiers = "mdpi")
class VirtualAccessibilityTest {
    private lateinit var activity: Activity
    private lateinit var keyboard: KeyboardView
    private val typed = mutableListOf<String>()
    private val picks = mutableListOf<Int>()
    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences("bk", 0).edit().clear().putString("pattern", "off").apply()
        keyboard = KeyboardView(activity, Feedback(activity), object: KeyboardView.Listener {
            override fun onText(text: String) { typed += text }
            override fun onDelete() { typed += "DELETE" }
            override fun onEnter() { typed += "ENTER" }
        })
        attach(keyboard)
    }
    private fun attach(view: View) { activity.setContentView(view);view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));view.layout(0,0,480,view.measuredHeight) }
    private fun provider(view: View) = view.accessibilityNodeProvider!!
    private fun nodes(view: View):List<Pair<Int, AccessibilityNodeInfo>> {
        val h=provider(view).createAccessibilityNodeInfo(-1)!!
        // Child ids are obtained from the delegate's own semantic controls, not assumed key coordinates.
        val f=view.javaClass.getDeclaredField("accessibility"+"\$delegate").apply{isAccessible=true}
        val helper=(f.get(view) as Lazy<*>).value!!
        val a=helper.javaClass.getDeclaredField("active").apply{isAccessible=true}.get(helper) as Map<*,*>
        assertEquals(h.childCount,a.size)
        return a.keys.map { val id=it as Int;id to provider(view).createAccessibilityNodeInfo(id)!! }
    }
    private fun node(view: View,label:String)=nodes(view).first{it.second.contentDescription.toString()==label}
    private fun click(view:View,label:String,action:Int=AccessibilityNodeInfo.ACTION_CLICK):Boolean=provider(view).performAction(node(view,label).first,action,Bundle())
    @Test fun keysAreButtonsWithBoundsAndActivateExactlyOnce() {
        assertEquals(35,nodes(keyboard).size)
        nodes(keyboard).forEach { (_,n)->assertEquals("android.widget.Button",n.className);assertTrue(n.isClickable);val b=Rect();n.getBoundsInParent(b);assertFalse(b.isEmpty) }
        assertTrue(click(keyboard,"q"));assertEquals(listOf("q"),typed)
        assertTrue(click(keyboard,"Delete"));assertTrue(click(keyboard,"Enter"));assertEquals(listOf("q","DELETE","ENTER"),typed)
    }
    @Test fun shiftStateAndNamedLockActionFollowTyping() {
        click(keyboard,"Shift");assertTrue(node(keyboard,"Shift").second.isChecked)
        assertTrue(click(keyboard,"Capital Q"));assertEquals(listOf("Q"),typed);assertFalse(node(keyboard,"Shift").second.isChecked)
        click(keyboard,"Shift",AccessibilityNodeInfo.ACTION_LONG_CLICK);assertEquals("Caps lock",node(keyboard,"Shift").second.stateDescription)
        click(keyboard,"Capital Q");assertTrue(node(keyboard,"Shift").second.isChecked)
    }
    @Test fun altAndAccentsUseTheSameOutputAsTheirKeys() {
        click(keyboard,"Alt");click(keyboard,"Number sign");assertEquals(listOf("#"),typed)
        click(keyboard,"e",AccessibilityNodeInfo.ACTION_LONG_CLICK);assertTrue(click(keyboard,"é"));assertEquals(listOf("#","é"),typed)
    }
    @Test fun pickerReplacesKeysAndRejectsStaleActions() {
        val q=node(keyboard,"q").first;click(keyboard,"Symbols")
        assertFalse(provider(keyboard).performAction(q,AccessibilityNodeInfo.ACTION_CLICK,null))
        click(keyboard,"Exclamation mark");assertEquals(listOf("!"),typed)
        click(keyboard,"Close picker");assertTrue(click(keyboard,"q"))
    }
    @Test fun candidateNodesAreBoundedSensitiveAndStaleSafe() {
        val bar=SuggestionBar(activity,Feedback(activity),object:SuggestionBar.Listener {override fun onPick(index:Int){picks+=index};override fun onMove(dx:Int,dy:Int,select:Boolean){}},{false});attach(bar)
        bar.setSuggestions(listOf("secretCandidate","other"));val old=node(bar,"Suggestion 1: secretCandidate").first
        if(android.os.Build.VERSION.SDK_INT>=34)assertTrue(provider(bar).createAccessibilityNodeInfo(old)!!.isAccessibilityDataSensitive)
        repeat(30){bar.setSuggestions(listOf("replacement$it"))}
        assertFalse(provider(bar).performAction(old,AccessibilityNodeInfo.ACTION_CLICK,null));assertTrue(click(bar,"Suggestion 1: replacement29"));assertEquals(listOf(0),picks)
        bar.setSuggestions(emptyList());assertEquals(listOf("Trackpad"),nodes(bar).map{it.second.contentDescription.toString()});assertFalse(node(bar,"Trackpad").second.isClickable)
    }
    @Test fun trackpadHasExplicitMovementAndSelectionActions() {
        val moves=mutableListOf<Triple<Int,Int,Boolean>>()
        val bar=SuggestionBar(activity,Feedback(activity),object:SuggestionBar.Listener {override fun onPick(index:Int){};override fun onMove(dx:Int,dy:Int,select:Boolean){moves+=Triple(dx,dy,select)}},{false});attach(bar)
        assertTrue(click(bar,"Trackpad",R.id.a11y_move_left));assertTrue(click(bar,"Trackpad",R.id.a11y_select_down));assertEquals(listOf(Triple(-1,0,false),Triple(0,1,true)),moves)
    }
    @Test fun focusDoesNotTypeUntilExplicitActivation() {
        val id=node(keyboard,"q").first
        assertTrue(provider(keyboard).performAction(id,AccessibilityNodeInfo.ACTION_FOCUS,null))
        assertTrue(provider(keyboard).createAccessibilityNodeInfo(id)!!.isFocused);assertTrue(typed.isEmpty())
        assertTrue(provider(keyboard).performAction(id,AccessibilityNodeInfo.ACTION_CLICK,null));assertEquals(listOf("q"),typed)
    }
    @Test fun changingCandidatesNeverBroadcastsTheirLabels() {
        val manager=activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        org.robolectric.Shadows.shadowOf(manager).setEnabled(true)
        org.robolectric.Shadows.shadowOf(manager).setTouchExplorationEnabled(true)
        val events=mutableListOf<android.view.accessibility.AccessibilityEvent>()
        val parent=object:android.widget.FrameLayout(activity){override fun requestSendAccessibilityEvent(child:View,event:android.view.accessibility.AccessibilityEvent):Boolean{events+=android.view.accessibility.AccessibilityEvent.obtain(event);return super.requestSendAccessibilityEvent(child,event)}}
        val bar=SuggestionBar(activity,Feedback(activity),object:SuggestionBar.Listener {override fun onPick(index:Int){};override fun onMove(dx:Int,dy:Int,select:Boolean){}},{false})
        activity.setContentView(parent);parent.addView(bar);bar.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));bar.layout(0,0,480,bar.measuredHeight)
        bar.setSuggestions(listOf("privateLearnedCandidate"));val id=node(bar,"Suggestion 1: privateLearnedCandidate").first
        assertTrue(provider(bar).performAction(id,AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null));events.clear()
        repeat(10){bar.setSuggestions(listOf("newPrivateCandidate$it"))}
        assertTrue(events.any{it.eventType==android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED})
        assertFalse(events.any{it.eventType==android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT||it.eventType==android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED})
        assertFalse(events.any{(it.text.toString()+it.contentDescription).contains("Candidate")})
        assertFalse(provider(bar).performAction(id,AccessibilityNodeInfo.ACTION_CLICK,null))
    }
    @Test fun accessibleEmojiToneRespectsPersonalizedLearningPolicy() {
        val item=Emoji.Item("👍",listOf("👍🏻"))
        Emoji::class.java.getDeclaredField("cache").apply{isAccessible=true}.set(null,listOf(Emoji.Group("People",listOf(item))))
        val before=Emoji.toneState(activity)
        keyboard.personalizedLearningAllowed=false
        click(keyboard,"Emoji and Speed shortcuts");click(keyboard,"Emoji 👍",AccessibilityNodeInfo.ACTION_LONG_CLICK);click(keyboard,"Emoji 👍🏻")
        assertEquals(listOf("👍🏻"),typed);assertEquals(before,Emoji.toneState(activity));assertTrue(Emoji.recents(activity,7).isEmpty())
        keyboard.personalizedLearningAllowed=true
        click(keyboard,"Emoji 👍🏻",AccessibilityNodeInfo.ACTION_LONG_CLICK);click(keyboard,"Emoji 👍🏻")
        assertNotEquals(before,Emoji.toneState(activity));assertTrue(Emoji.recents(activity,7).contains("👍🏻"))
    }

    @Test fun enterDescribesTheStandardEditorActionWithoutUsingEditorText() {
        keyboard.setEnterAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
        assertTrue(click(keyboard,"Search"));assertEquals(listOf("ENTER"),typed)
        keyboard.setEnterAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        assertTrue(node(keyboard,"Enter").second.isClickable)
    }

    @Test fun newEditorCancelsTransientShortcutAndAccentActions() {
        click(keyboard,"Emoji and Speed shortcuts",R.id.a11y_shortcut)
        val shortcut=node(keyboard,"Speed shortcut Q").first
        keyboard.resetAccessibilityInput()
        assertFalse(provider(keyboard).performAction(shortcut,AccessibilityNodeInfo.ACTION_CLICK,null))
        assertTrue(click(keyboard,"q"));assertEquals(listOf("q"),typed)
        click(keyboard,"e",AccessibilityNodeInfo.ACTION_LONG_CLICK)
        val accent=node(keyboard,"é").first
        keyboard.resetAccessibilityInput()
        assertFalse(provider(keyboard).performAction(accent,AccessibilityNodeInfo.ACTION_CLICK,null))
        assertTrue(click(keyboard,"q"));assertEquals(listOf("q","q"),typed)
    }

}
