package com.noodoe.offline.creation

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CreationStudioLaunchTest {

    @Test
    fun activityLaunches() {
        val controller = Robolectric.buildActivity(CreationStudioActivity::class.java).setup()
        assertNotNull(controller.get())
    }
}
