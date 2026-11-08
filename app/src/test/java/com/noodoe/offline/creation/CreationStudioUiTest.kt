package com.noodoe.offline.creation

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noodoe.offline.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CreationStudioUiTest {

    @Test
    fun clockAndSpeedometerButtonsAreEqualHeightAndNotBaselineAligned() {
        val activity = Robolectric.buildActivity(CreationStudioActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val expected = (64 * density).toInt()

        val clockRow = activity.findViewById<View>(R.id.pickClockBackgroundButton)
            .parent as LinearLayout
        assertFalse(clockRow.isBaselineAligned)
        assertEquals(expected, activity.findViewById<View>(R.id.pickClockBackgroundButton).layoutParams.height)
        assertEquals(expected, activity.findViewById<View>(R.id.useClockTemplateButton).layoutParams.height)

        val speedRow = activity.findViewById<View>(R.id.pickSpeedBackgroundButton)
            .parent as LinearLayout
        assertFalse(speedRow.isBaselineAligned)
        assertEquals(expected, activity.findViewById<View>(R.id.pickSpeedBackgroundButton).layoutParams.height)
        assertEquals(expected, activity.findViewById<View>(R.id.useSpeedBackgroundButton).layoutParams.height)
    }

    @Test
    fun spinnerHasSpacingPopupSurfaceAndFullWidthList() {
        val activity = Robolectric.buildActivity(CreationStudioActivity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density
        val spinner = activity.findViewById<Spinner>(R.id.clockBackgroundSpinner)
        val params = spinner.layoutParams as ViewGroup.MarginLayoutParams

        assertEquals((8 * density).toInt(), params.topMargin)
        assertNotNull(spinner.popupBackground)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, spinner.dropDownWidth)
    }
}
