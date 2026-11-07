package com.noodoe.offline.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class NoodoeServiceTest {

    @Test
    fun servicePostsForegroundNotification() {
        val controller = Robolectric.buildService(NoodoeService::class.java)
        val service = controller.create().get()
        val shadow = Shadows.shadowOf(service)
        assertNotNull(shadow.lastForegroundNotification)
        controller.destroy()
    }
}
