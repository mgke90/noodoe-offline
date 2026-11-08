package com.noodoe.offline.creation

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DigitalDigitRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun stockDefaultKeepsOfficial64x90Size() {
        val glyphs = CreationAssets.digitBitmaps(context, Color.WHITE)
        assertEquals(10, glyphs.size)
        glyphs.forEach {
            assertEquals(64, it.width)
            assertEquals(90, it.height)
            assertEquals(0, Color.alpha(it.getPixel(0, 0)))
            it.recycle()
        }
    }

    @Test
    fun stockTuningChangesSizeAndWeight() {
        val full = DigitalDigitRenderer.render(
            context,
            '0',
            DigitalDigitStyle(
                mode = DigitMode.STOCK,
                sourceHeight = 90,
                scalePercent = 100,
                thickness = 0,
                hardEdges = true
            ),
            Color.WHITE
        )
        val tuned = DigitalDigitRenderer.render(
            context,
            '0',
            DigitalDigitStyle.TUNING_DEFAULT,
            Color.WHITE
        )

        assertTrue(opaquePixels(tuned) < opaquePixels(full))
        assertEquals(64, tuned.width)
        assertEquals(90, tuned.height)
        full.recycle()
        tuned.recycle()
    }

    @Test
    fun textModeUsesRequestedResolutionAndTint() {
        val style = DigitalDigitStyle(
            mode = DigitMode.TEXT,
            font = DigitFont.SANS_LIGHT,
            sourceHeight = 180,
            scalePercent = 80,
            thickness = 0,
            hardEdges = false
        )
        val glyph = DigitalDigitRenderer.render(context, '8', style, Color.RED)

        assertEquals(128, glyph.width)
        assertEquals(180, glyph.height)
        var redPixels = 0
        var smoothPixels = 0
        for (y in 0 until glyph.height step 2) {
            for (x in 0 until glyph.width step 2) {
                val pixel = glyph.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                if (alpha > 0 && Color.red(pixel) > 200) redPixels++
                if (alpha in 1..254) smoothPixels++
            }
        }
        assertTrue(redPixels > 0)
        assertTrue(smoothPixels > 0)
        glyph.recycle()
    }

    @Test
    fun builderBundleUsesTheTunedGlyphResolution() {
        val style = DigitalDigitStyle(
            mode = DigitMode.TEXT,
            font = DigitFont.CONDENSED_LIGHT,
            sourceHeight = 135,
            scalePercent = 70,
            thickness = 0,
            hardEdges = false
        )
        val bundle = SpeedometerCreationBuilder.build(
            context,
            SpeedometerCreationBuilder.Options(
                background = null,
                unit = "kmh",
                maxSpeed = 200,
                analog = false,
                digitStyle = style
            )
        )
        assertEquals(12, bundle.files.size)
        val cfg = bundle.files.first { it.name.endsWith(".cfg") }
        val root = org.json.JSONObject(String(cfg.data))
        val widget = root.getJSONObject("configuration")
            .getJSONArray("widgets")
            .getJSONObject(1)
        val names = widget.getJSONArray("images")
        assertEquals(10, names.length())
        for (i in 0 until names.length()) {
            val file = bundle.files.first { it.name == names.getString(i) }
            val bitmap = BitmapFactory.decodeByteArray(file.data, 0, file.data.size)
            assertEquals(96, bitmap.width)
            assertEquals(135, bitmap.height)
            bitmap.recycle()
        }
    }

    private fun opaquePixels(bitmap: android.graphics.Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) == 255) count++
            }
        }
        return count
    }
}
