// SPDX-License-Identifier: GPL-3.0-only
package app.fortem

import android.app.Activity
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WindowOrientationPolicyTest {
    @Test fun enteringSplitScreenClearsLandscapeAndReturningRestoresIt() {
        // No native audio is needed to exercise the same Activity orientation calls.
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            activity.applyPerformanceOrientation(false)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
            activity.applyPerformanceOrientation(true)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
            activity.applyPerformanceOrientation(true)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
            activity.applyPerformanceOrientation(false)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun manifestOptsIntoSizeChangesWithoutDeclaringAFixedOrientation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(
            ComponentName(context.packageName, "app.fortem.MainActivity"),
            PackageManager.GET_META_DATA,
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, info.screenOrientation)
        assertTrue(info.metaData.getBoolean("android.supports_size_changes"))
    }
}
