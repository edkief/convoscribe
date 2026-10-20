package com.convoscribe

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.convoscribe.models.ModelCatalog
import com.convoscribe.recording.PendingImport
import com.convoscribe.settings.DisplayMode
import com.convoscribe.ui.AppNavHost
import com.convoscribe.ui.theme.AppMotionScale
import com.convoscribe.ui.theme.ConvoScribeTheme

class MainActivity : ComponentActivity() {
    private val motionScale = AppMotionScale {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    // createLifecycleAwareWindowRecomposer is what setContent uses by
    // default; it is still marked experimental.
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (applicationContext as ConvoApp).container
        // A share / Open with intent, unless this is a re-creation that
        // already took it.
        if (savedInstanceState == null) {
            takeImport(intent)
            takeOpenRecord(intent)
            // When due, in the background, never while recording (issues #15, #41).
            container.updateManager.checkIfDue()
        }
        // The active language and ASR engine are settings; if every
        // required bundle for that combination is already on disk we
        // can skip the first-run download screen and go straight to
        // the home screen.
        val settings = container.settingsRepository.settings.value
        val startDestination = if (
            container.modelStore.missing(
                ModelCatalog.requiredFor(settings.asrLanguage, settings.asrEngine)
            ).isEmpty()
        ) {
            "home"
        } else {
            "setup"
        }
        val einkDevice = DisplayMode.isEinkDevice(Build.MANUFACTURER, Build.BRAND)
        // A window recomposer whose animations follow [motionScale]: none
        // in e-ink mode.
        val recomposer = window.decorView.createLifecycleAwareWindowRecomposer(motionScale, lifecycle)
        setContent(parent = recomposer) {
            // The display mode and device colours apply as soon as they
            // change in Settings.
            val appSettings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            val mode = DisplayMode.fromKey(appSettings.displayMode) ?: DisplayMode.AUTO
            val eink = DisplayMode.resolvesToEink(mode, einkDevice)
            SideEffect { motionScale.eink = eink }
            val dark = isSystemInDarkTheme() && !eink
            // E-ink is always light, so the system bar icons must stay dark
            // even when the system is in dark mode.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ConvoScribeTheme(darkTheme = dark, eink = eink, dynamicColor = appSettings.dynamicColor) {
                AppNavHost(startDestination = startDestination)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeImport(intent)
        takeOpenRecord(intent)
    }

    /** Ask the navigation graph for the Record screen if [intent] came from [openRecordIntent]. */
    private fun takeOpenRecord(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_RECORD, false) != true) return
        (applicationContext as ConvoApp).container.pendingOpenRecord.value = true
    }

    /**
     * Queue the audio file of a `SEND` (share sheet) or `VIEW` (Open
     * with) [intent] for import; the home screen starts it. The read
     * grant that came with the intent lasts while this activity lives,
     * and the service gets its own when the import starts.
     */
    private fun takeImport(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        (applicationContext as ConvoApp).container.pendingImport.value = PendingImport(uri)
    }

    companion object {
        private const val EXTRA_OPEN_RECORD = "com.convoscribe.EXTRA_OPEN_RECORD"

        /**
         * Bring ConvoScribe forward on the Record screen, which shows the
         * open session (the floating control and the Quick Settings tile,
         * issue #7). Usable from a non-activity context.
         */
        fun openRecordIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_RECORD, true)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
    }
}
