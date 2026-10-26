package com.convoscribe

import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The tile's long-press (issue #32) resolves to the preset picker. */
@RunWith(RobolectricTestRunner::class)
class PresetPickerActivityTest {

    @Test
    fun tilePreferencesIntent_opensThePresetPicker() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val intent = Intent(TileService.ACTION_QS_TILE_PREFERENCES).setPackage(context.packageName)
        val activities = context.packageManager.queryIntentActivities(intent, 0)
        assertEquals(listOf(PresetPickerActivity::class.java.name), activities.map { it.activityInfo.name })
    }
}
