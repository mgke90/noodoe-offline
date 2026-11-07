package com.noodoe.offline.creation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SpeedometerCreationBuilderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun analogBundleHasBackgroundAndNeedle() {
        val bundle = SpeedometerCreationBuilder.build(
            context,
            SpeedometerCreationBuilder.Options(
                background = null,
                unit = "kmh",
                maxSpeed = 200,
                analog = true,
                markStyle = 2,
                markColor = "#ffffff",
                needleStyle = 3,
                needleColor = "#ff0000",
                centerColor = "#00ff00"
            )
        )
        assertEquals(SpeedometerCreationBuilder.LOCATION_SPEEDOMETER, bundle.location)
        assertEquals(2, bundle.files.size)
        assertEquals(16, bundle.contentId.size)

        val cfg = bundle.files.first { it.name.endsWith(".cfg") }
        val root = JSONObject(String(cfg.data))
        val files = root.getJSONArray("files")
        assertEquals(1, files.length())
        val widgets = root.getJSONObject("configuration").getJSONArray("widgets")
        assertEquals(2, widgets.length())

        val background = widgets.getJSONObject(0)
        assertEquals("BackgroundWidget", background.getString("name"))
        assertEquals("analogSpeed", background.getString("imageType"))
        val backgroundName = background.getJSONArray("images").getString(0)
        assertEquals(backgroundName, files.getString(0))
        assertTrue(backgroundName.endsWith(".jpg"))

        val needle = widgets.getJSONObject(1)
        assertEquals("SpeedNeedleWidget", needle.getString("name"))
        assertEquals(3, needle.getInt("needleType"))
        assertEquals("#FFFF0000", needle.getString("needleColor"))
        assertEquals("#FF00FF00", needle.getString("centerColor"))

        for (file in bundle.files) {
            val identity = file.name.substringBeforeLast('.')
            assertEquals(md5Hex(file.data), identity)
        }
    }

    @Test
    fun digitalBundleHasTenGlyphsAndOptionalBar() {
        val bundle = SpeedometerCreationBuilder.build(
            context,
            SpeedometerCreationBuilder.Options(
                background = null,
                unit = "mph",
                maxSpeed = 125,
                analog = false,
                digitalColor = "#ffff00",
                barStyle = 5,
                barColor = "#00ffff",
                showBar = true
            )
        )
        assertEquals(12, bundle.files.size)
        assertTrue(bundle.files.last().name.endsWith(".cfg"))
        val cfg = bundle.files.first { it.name.endsWith(".cfg") }
        val root = JSONObject(String(cfg.data))
        assertEquals(11, root.getJSONArray("files").length())
        val widgets = root.getJSONObject("configuration").getJSONArray("widgets")
        assertEquals(3, widgets.length())

        val background = widgets.getJSONObject(0)
        assertEquals("BackgroundWidget", background.getString("name"))
        assertEquals("single", background.getString("imageType"))
        val digit = widgets.getJSONObject(1)
        assertEquals("SpeedDigitWidget", digit.getString("name"))
        assertEquals(10, digit.getJSONArray("images").length())
        val bar = widgets.getJSONObject(2)
        assertEquals("SpeedBarWidget", bar.getString("name"))
        assertEquals(5, bar.getInt("speedBarType"))
        assertEquals("#FF00FFFF", bar.getString("speedBarColor"))

        val glyphs = (0 until digit.getJSONArray("images").length()).map {
            digit.getJSONArray("images").getString(it)
        }
        assertEquals(10, glyphs.toSet().size)
        for (name in glyphs) {
            val file = bundle.files.first { it.name == name }
            assertEquals(0x89, file.data[0].toInt() and 0xFF)
            assertEquals(0x50, file.data[1].toInt() and 0xFF)
        }
    }

    @Test
    fun assetsAreOfficialAndComposeTo480() {
        assertNotNull(CreationAssets.clockHand(context, "hour", 1))
        assertNotNull(CreationAssets.speedNeedle(context, 3))
        assertNotNull(CreationAssets.speedCenter(context, 3))
        assertNotNull(CreationAssets.speedMark(context, "kmh", 1, 200))
        assertNotNull(CreationAssets.digitalMark(context))
        assertNotNull(CreationAssets.speedBar(context, 3))
        assertNotNull(CreationAssets.background(context, "backgrounds/kmh_7_200.jpg"))

        val composed = CreationAssets.composeSpeedBackground(
            null,
            CreationAssets.speedMark(context, "kmh", 1, 200),
            android.graphics.Color.WHITE
        )
        assertEquals(480, composed.width)
        assertEquals(480, composed.height)
        assertNotNull(CreationAssets.jpeg(composed))
        composed.recycle()

        val glyphs = CreationAssets.digitBitmaps(context, android.graphics.Color.WHITE)
        assertEquals(10, glyphs.size)
        // Stock 64x90 masks at native size, not the 21x28 Noto preset.
        assertEquals(64, glyphs.first().width)
        glyphs.forEach { assertTrue(it.width > 0 && it.height == 90) ; it.recycle() }
    }
    @Test
    fun hiddenDigitalBarStillMarksTheCreationAsSpeedometer() {
        val bundle = SpeedometerCreationBuilder.build(
            context,
            SpeedometerCreationBuilder.Options(
                background = null,
                unit = "kmh",
                maxSpeed = 200,
                analog = false,
                showBar = false
            )
        )
        val cfg = bundle.files.first { it.name.endsWith(".cfg") }
        val root = JSONObject(String(cfg.data))
        val widgets = root.getJSONObject("configuration").getJSONArray("widgets")
        assertEquals(3, widgets.length())
        val bar = widgets.getJSONObject(2)
        assertEquals("SpeedBarWidget", bar.getString("name"))
        assertEquals("#00000000", bar.getString("speedBarColor"))
    }

    @Test
    fun officialFaceDoesNotDuplicateMarks() {
        val background = CreationAssets.background(context, "backgrounds/kmh_7_200.jpg")
        val bundle = SpeedometerCreationBuilder.build(
            context,
            SpeedometerCreationBuilder.Options(
                background = background,
                unit = "kmh",
                maxSpeed = 200,
                analog = true,
                composeMark = false
            )
        )
        background?.recycle()
        assertEquals(2, bundle.files.size)
        val cfg = bundle.files.first { it.name.endsWith(".cfg") }
        val root = JSONObject(String(cfg.data))
        val widget = root.getJSONObject("configuration").getJSONArray("widgets").getJSONObject(0)
        assertEquals("BackgroundWidget", widget.getString("name"))
        assertEquals("analogSpeed", widget.getString("imageType"))
    }

    @Test
    fun previewDrawsDigitalSpeedsAtAllDigitCounts() {
        val view = DashboardPreviewView(context)
        view.setSpeedometer(
            background = null,
            unit = "kmh",
            maxSpeed = 200,
            analog = false,
            markStyle = 1,
            markColor = android.graphics.Color.WHITE,
            needleStyle = 1,
            needleColor = android.graphics.Color.RED,
            centerColor = android.graphics.Color.WHITE,
            barStyle = 3,
            barColor = android.graphics.Color.RED,
            digitalColor = android.graphics.Color.CYAN,
            showBar = false,
            composeMark = true,
            speed = 9
        )
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 480, 480)
        val output = android.graphics.Bitmap.createBitmap(
            480,
            480,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        for (speed in listOf(0, 9, 10, 99, 100, 200)) {
            view.setSpeedValue(speed)
            view.draw(android.graphics.Canvas(output))
        }
        assertEquals(480, output.width)
        output.recycle()
    }

    @Test
    fun previewDrawsOfficialFaceWithoutCrashing() {
        val background = CreationAssets.background(context, "backgrounds/kmh_7_200.jpg")
        val view = DashboardPreviewView(context)
        view.setSpeedometer(
            background = background,
            unit = "kmh",
            maxSpeed = 200,
            analog = true,
            markStyle = 1,
            markColor = android.graphics.Color.WHITE,
            needleStyle = 1,
            needleColor = android.graphics.Color.RED,
            centerColor = android.graphics.Color.WHITE,
            barStyle = 3,
            barColor = android.graphics.Color.RED,
            digitalColor = android.graphics.Color.WHITE,
            showBar = true,
            composeMark = false,
            speed = 100
        )
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 320, 320)
        val output = android.graphics.Bitmap.createBitmap(
            320,
            320,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        view.draw(android.graphics.Canvas(output))
        assertEquals(320, output.width)
        output.recycle()
        background?.recycle()
    }

}
