package com.noodoe.offline.creation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ClockCreationBuilderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun stockBundleContainsOfficialTemplate() {
        val bundle = ClockCreationBuilder.buildStock(context)
        assertEquals(ClockCreationBuilder.LOCATION_CLOCK, bundle.location)
        assertEquals(9, bundle.files.size)
        assertEquals(16, bundle.contentId.size)
        assertTrue(bundle.files.last().name.endsWith(".cfg"))

        val cfg = bundle.files.first { it.name.endsWith(".cfg") }
        assertArrayEquals(cfg.md5, bundle.contentId)
        val json = JSONObject(String(cfg.data))
        assertEquals(8, json.getJSONArray("files").length())
        assertEquals(
            listOf("BackgroundWidget", "WatchHandWidget", "DateWidget"),
            widgetNames(json)
        )
        val date = json.getJSONObject("configuration").getJSONArray("widgets").getJSONObject(2)
        assertEquals("EEEE", date.getString("format"))
        assertEquals(7, date.getJSONArray("weekdayImages").length())
        val weekdays = (0 until 7).map { date.getJSONArray("weekdayImages").getString(it) }
        assertEquals(7, weekdays.toSet().size)
        for (weekday in weekdays) {
            assertNotNull(bundle.files.firstOrNull { it.name == weekday })
        }
        for (file in bundle.files) {
            val identity = file.name.substringBeforeLast('.')
            assertTrue(file.name, identity.matches(Regex("[0-9a-f]{32}")))
            assertArrayEquals(file.md5, md5Bytes(file.data))
        }
        assertNotNull(ClockCreationBuilder.templateBackground(context))
    }

    @Test
    fun minimalStockBundleHasOnlyCfgAndBackground() {
        val bundle = ClockCreationBuilder.buildMinimalStock(context)
        assertEquals(ClockCreationBuilder.LOCATION_CLOCK, bundle.location)
        assertEquals(2, bundle.files.size)
        val cfg = bundle.files.first { it.name.endsWith(".cfg") }
        val json = JSONObject(String(cfg.data))
        assertEquals(1, json.getJSONArray("files").length())
        assertEquals(
            listOf("BackgroundWidget", "WatchHandWidget"),
            widgetNames(json)
        )
        val background = json.getJSONArray("files").getString(0)
        val backgroundFile = bundle.files.first { it.name == background }
        assertNotNull(backgroundFile)
        assertArrayEquals(cfg.md5, bundle.contentId)
    }

    @Test
    fun customBackgroundBecomesMd5NamedJpeg() {
        val bitmap = android.graphics.Bitmap.createBitmap(
            120,
            80,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        bitmap.eraseColor(android.graphics.Color.rgb(30, 60, 90))
        val bundle = ClockCreationBuilder.buildCustom(
            context,
            ClockCreationBuilder.Options(
                background = bitmap,
                handType = 2,
                showDate = true
            )
        )
        bitmap.recycle()
        val cfg = bundle.files.first { it.name.endsWith(".cfg") }
        val json = JSONObject(String(cfg.data))
        val backgroundName = json.getJSONObject("configuration")
            .getJSONArray("widgets")
            .getJSONObject(0)
            .getJSONArray("images")
            .getString(0)
        assertTrue(backgroundName.endsWith(".jpg"))
        assertTrue(backgroundName.substringBeforeLast('.').matches(Regex("[0-9a-f]{32}")))
        val background = bundle.files.first { it.name == backgroundName }
        assertEquals(0xFF.toByte(), background.data[0])
        assertEquals(0xD8.toByte(), background.data[1])
        assertEquals(9, bundle.files.size)
    }

    @Test
    fun customBundleRewritesColorsAndDropsDate() {
        val bundle = ClockCreationBuilder.buildCustom(
            context,
            ClockCreationBuilder.Options(
                background = null,
                handType = 4,
                hourColor = "#FF0000",
                minuteColor = "#00FF00",
                secondColor = "#0000FF",
                showSecond = false,
                showDate = false
            )
        )
        val cfg = bundle.files.first { it.name.endsWith(".cfg") }
        assertArrayEquals(cfg.md5, bundle.contentId)
        val json = JSONObject(String(cfg.data))
        val widgets = json.getJSONObject("configuration").getJSONArray("widgets")
        assertEquals(listOf("BackgroundWidget", "WatchHandWidget"), widgetNames(json))

        val hands = widgets.getJSONObject(1)
        assertEquals("#ff0000", hands.getString("hourColor"))
        assertEquals("#00ff00", hands.getString("minuteColor"))
        assertEquals("#000000ff", hands.getString("secondColor"))
        assertEquals(4, hands.getInt("watchHandType"))
        assertFalse(hands.has("showSecondHand"))
        assertEquals(2, bundle.files.size)
        assertEquals(1, json.getJSONArray("files").length())
    }

    private fun widgetNames(json: JSONObject): List<String> {
        val widgets = json.getJSONObject("configuration").getJSONArray("widgets")
        return (0 until widgets.length()).map { widgets.getJSONObject(it).getString("name") }
    }
}
