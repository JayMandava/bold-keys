package ai.boldkeys

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import java.util.IdentityHashMap

/** Test-only client-window observation. Never replaces Compose's inset/animation callbacks. */
internal class ImeWindowObserver(
    private val application: Application,
    private val sample: (Activity, Int) -> ImeSettleState.Frame?,
) : Application.ActivityLifecycleCallbacks {
    private val probes = IdentityHashMap<Activity, Probe>()
    private var awaiting = false

    fun register() = application.registerActivityLifecycleCallbacks(this)

    fun beginAwait() {
        awaiting = true
        probes.values.forEach { it.state.resetFrames(); it.scheduleFrame() }
    }

    fun finishAwait() {
        awaiting = false
        probes.values.forEach { it.cancelFrame() }
    }

    fun ready(): Boolean = probes.entries.singleOrNull { it.key.window.decorView.hasWindowFocus() }
        ?.value?.let { it.isAttachedToWindow && it.state.ready } == true

    fun diagnostic(): String = probes.entries.joinToString("; ") {
        "${it.key.javaClass.simpleName}: ${it.value.state.diagnostic()} sampleError=${it.value.sampleError}"
    }

    fun close() {
        finishAwait()
        application.unregisterActivityLifecycleCallbacks(this)
        probes.values.toList().forEach { it.dispose() }
        probes.clear()
    }

    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
        val decor = activity.window.decorView as? ViewGroup
            ?: error("QA requires a real Activity decor ViewGroup")
        check(!probes.containsKey(activity))
        val probe = Probe(activity)
        probes[activity] = probe
        // First sibling observes original dispatch even if a later content child consumes it.
        // Zero layout area, no paint, focus, touch or accessibility node; product UI is unchanged.
        decor.addView(probe, 0, ViewGroup.LayoutParams(0, 0))
        probe.requestApplyInsets()
    }

    override fun onActivityDestroyed(activity: Activity) { probes.remove(activity)?.dispose() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private inner class Probe(private val activity: Activity) : View(activity), Choreographer.FrameCallback {
        val state = ImeSettleState()
        var sampleError: String? = null
            private set
        private var imeBottom = 0
        private var scheduled = false
        private var disposed = false

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnApplyWindowInsetsListener { _, insets ->
                imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                state.insets(insets.isVisible(WindowInsets.Type.ime()), imeBottom)
                scheduleFrame()
                insets
            }
            setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                        state.prepare(animation)
                        Log.d("ColourUiIme", "${activity.javaClass.simpleName}: IME animation prepared")
                    }
                    scheduleFrame()
                }

                override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets {
                    imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                    state.progress(insets.isVisible(WindowInsets.Type.ime()), imeBottom,
                        runningAnimations.filter { it.typeMask and WindowInsets.Type.ime() != 0 }.toSet())
                    scheduleFrame()
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                        state.end(animation)
                        Log.d("ColourUiIme", "${activity.javaClass.simpleName}: IME animation ended")
                    }
                    requestApplyInsets()
                    scheduleFrame()
                }
            })
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            requestApplyInsets()
            scheduleFrame()
        }

        override fun onDetachedFromWindow() {
            cancelFrame(); state.detach()
            super.onDetachedFromWindow()
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            state.resetFrames()
            scheduleFrame()
        }

        fun scheduleFrame() {
            if (!awaiting || disposed || scheduled || !isAttachedToWindow) return
            scheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }

        fun cancelFrame() {
            Choreographer.getInstance().removeFrameCallback(this)
            scheduled = false
        }

        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            if (!awaiting || disposed) return
            val frame = if (isAttachedToWindow && hasWindowFocus() && windowVisibility == VISIBLE) {
                runCatching { sample(activity, imeBottom) }.fold(
                    onSuccess = { sampleError = null; it },
                    onFailure = { sampleError = it.toString(); null },
                )
            } else null
            state.frame(frameTimeNanos, frame)
            scheduleFrame()
        }

        fun dispose() {
            disposed = true
            cancelFrame(); state.detach()
            setOnApplyWindowInsetsListener(null)
            setWindowInsetsAnimationCallback(null)
            (parent as? ViewGroup)?.removeView(this)
        }
    }
}
