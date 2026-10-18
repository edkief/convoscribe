package com.convoscribe.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.getSystemService
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RecordingState
import com.convoscribe.settings.AppSettings
import com.convoscribe.settings.DisplayMode
import com.convoscribe.ui.theme.ConvoScribeTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The floating recording control (issue #7): a small window drawn over
 * other apps while a session is open, hosting [FloatingControlContent].
 * Owned by the recording service, which calls [setWanted] with
 * "a session is open, the setting is on and ConvoScribe is not in the
 * foreground".
 *
 * The window needs the "Display over other apps" permission
 * ([OverlayPermission]). While it is wanted but the permission is
 * missing (the user is on the system page granting it, say), the
 * control checks again once a second and appears as soon as it may.
 *
 * Dragging is handled by [DragFrame] with raw screen coordinates:
 * Compose gestures report positions inside the window, which moves
 * with the finger, so they cannot drive the window's own position. On
 * release the control snaps to the nearest side edge and the position
 * is saved ([FloatingPosition]).
 */
class FloatingControl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: StateFlow<RecordingState>,
    private val settings: StateFlow<AppSettings>,
    private val onOpen: () -> Unit,
    private val onPauseResume: () -> Unit,
    private val onStop: () -> Unit
) {
    private val windowManager: WindowManager = requireNotNull(context.getSystemService())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val margin = (context.resources.displayMetrics.density * MARGIN_DP).roundToInt()

    private var frame: DragFrame? = null
    private var owner: OverlayOwner? = null
    private var waitForPermission: Job? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Show the control when [wanted] (and permitted), remove it otherwise. Main thread only. */
    fun setWanted(wanted: Boolean) {
        waitForPermission?.cancel()
        waitForPermission = null
        if (!wanted) {
            hide()
            return
        }
        if (frame != null) return
        waitForPermission = scope.launch {
            while (isActive && frame == null) {
                if (OverlayPermission.granted(context)) show() else delay(PERMISSION_POLL_MS)
            }
        }
    }

    private fun show() {
        val lifecycleOwner = OverlayOwner()
        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val current by state.collectAsStateWithLifecycle()
                val appSettings by settings.collectAsStateWithLifecycle()
                val mode = DisplayMode.fromKey(appSettings.displayMode) ?: DisplayMode.AUTO
                val eink = DisplayMode.resolvesToEink(mode, DisplayMode.isEinkDevice(Build.MANUFACTURER, Build.BRAND))
                ConvoScribeTheme(
                    darkTheme = isSystemInDarkTheme() && !eink,
                    eink = eink,
                    dynamicColor = appSettings.dynamicColor
                ) {
                    FloatingControlContent(
                        paused = current.phase == RecordingPhase.PAUSED,
                        elapsedMs = current.elapsedMs,
                        onOpen = onOpen,
                        onPauseResume = onPauseResume,
                        onStop = onStop
                    )
                }
            }
        }
        val dragFrame = DragFrame(context, ::moveBy, ::snap).apply {
            addView(compose)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }
        lifecycleOwner.start()
        val added = runCatching { windowManager.addView(dragFrame, params) }.isSuccess
        if (!added) {
            // Permission revoked between the check and here.
            lifecycleOwner.destroy()
            return
        }
        frame = dragFrame
        owner = lifecycleOwner
        dragFrame.doOnLayout { place(loadPosition()) }
    }

    private fun hide() {
        val current = frame ?: return
        frame = null
        runCatching { windowManager.removeView(current) }
        owner?.destroy()
        owner = null
    }

    private fun moveBy(dx: Float, dy: Float) {
        val current = frame ?: return
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        runCatching { windowManager.updateViewLayout(current, params) }
    }

    private fun snap() {
        val current = frame ?: return
        val (screenWidth, screenHeight) = screenSize()
        val position = FloatingPosition.snap(
            params.x,
            params.y,
            current.width,
            current.height,
            screenWidth,
            screenHeight
        )
        savePosition(position)
        place(position)
    }

    private fun place(position: FloatingPosition) {
        val current = frame ?: return
        val (screenWidth, screenHeight) = screenSize()
        params.x = position.x(current.width, screenWidth, margin)
        params.y = position.y(current.height, screenHeight)
        runCatching { windowManager.updateViewLayout(current, params) }
    }

    /** The area an overlay window is laid out in (the app area, without the system bars). */
    private fun screenSize(): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun loadPosition(): FloatingPosition {
        val edge = prefs.getString(KEY_EDGE, null)?.let { name -> Edge.entries.firstOrNull { it.name == name } }
            ?: return FloatingPosition.DEFAULT
        return FloatingPosition(edge, prefs.getFloat(KEY_Y_FRACTION, FloatingPosition.DEFAULT.yFraction))
    }

    private fun savePosition(position: FloatingPosition) {
        prefs.edit()
            .putString(KEY_EDGE, position.edge.name)
            .putFloat(KEY_Y_FRACTION, position.yFraction)
            .apply()
    }

    /**
     * Lets taps through to the Compose content and takes over a gesture
     * once it has moved past the touch slop, reporting raw screen
     * deltas to [onMove] and the release to [onRelease].
     */
    @SuppressLint("ViewConstructor")
    private class DragFrame(
        context: Context,
        private val onMove: (dx: Float, dy: Float) -> Unit,
        private val onRelease: () -> Unit
    ) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    lastX = downX
                    lastY = downY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) {
                        dragging = true
                    }
                }
            }
            return dragging
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    onMove(event.rawX - lastX, event.rawY - lastY)
                    lastX = event.rawX
                    lastY = event.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onRelease()
                    dragging = false
                }
            }
            return true
        }
    }

    /** Lifecycle and saved state for the Compose content of a window that has no activity. */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val PREFS = "floating_control"
        const val KEY_EDGE = "edge"
        const val KEY_Y_FRACTION = "yFraction"
        const val MARGIN_DP = 8f
        const val PERMISSION_POLL_MS = 1_000L
    }
}
