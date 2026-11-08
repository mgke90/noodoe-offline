package com.noodoe.offline.creation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class DigitMode(val label: String) {
    STOCK("Stock mask"),
    TEXT("Rendered text")
}

enum class DigitFont(
    val label: String,
    val family: String,
    val style: Int
) {
    CONDENSED_LIGHT("Condensed light", "sans-serif-condensed-light", Typeface.NORMAL),
    CONDENSED("Condensed", "sans-serif-condensed", Typeface.NORMAL),
    CONDENSED_BOLD("Condensed bold", "sans-serif-condensed", Typeface.BOLD),
    SANS_THIN("Sans thin", "sans-serif-thin", Typeface.NORMAL),
    SANS_LIGHT("Sans light", "sans-serif-light", Typeface.NORMAL),
    SANS("Sans", "sans-serif", Typeface.NORMAL),
    SANS_MEDIUM("Sans medium", "sans-serif-medium", Typeface.NORMAL),
    SANS_BOLD("Sans bold", "sans-serif", Typeface.BOLD),
    MONOSPACE("Monospace", "monospace", Typeface.NORMAL),
    SERIF("Serif", "serif", Typeface.NORMAL)
}

/**
 * Runtime tuning for the digital speedometer glyphs. The dashboard scales a
 * digit image to its fixed widget, so the visible size is controlled by the
 * transparent padding around the glyph and the source resolution.
 */
data class DigitalDigitStyle(
    val mode: DigitMode = DigitMode.STOCK,
    val font: DigitFont = DigitFont.CONDENSED_LIGHT,
    val sourceHeight: Int = 90,
    val scalePercent: Int = 100,
    val thickness: Int = 0,
    val offsetXPercent: Int = 0,
    val offsetYPercent: Int = 0,
    val hardEdges: Boolean = false
) {
    fun normalized(): DigitalDigitStyle = copy(
        sourceHeight = nearestHeight(sourceHeight),
        scalePercent = scalePercent.coerceIn(40, 100),
        thickness = thickness.coerceIn(-2, 6),
        offsetXPercent = offsetXPercent.coerceIn(-30, 30),
        offsetYPercent = offsetYPercent.coerceIn(-30, 30)
    )

    fun describe(): String {
        val shape = if (mode == DigitMode.STOCK) {
            "stock"
        } else {
            font.label.lowercase()
        }
        return "$shape · ${sourceHeight}px · ${scalePercent}% · " +
                "thickness ${if (thickness > 0) "+$thickness" else "$thickness"}" +
                if (hardEdges) " · hard" else " · smooth"
    }

    companion object {
        val HEIGHTS = listOf(90, 135, 180, 270, 360)

        /** Safe first tuning: same 64x90 image size, 80% glyph, one pixel thinner. */
        val TUNING_DEFAULT = DigitalDigitStyle(
            mode = DigitMode.STOCK,
            sourceHeight = 90,
            scalePercent = 80,
            thickness = -1,
            hardEdges = false
        )

        fun nearestHeight(value: Int): Int =
            HEIGHTS.minByOrNull { abs(it - value) } ?: 90
    }
}

/**
 * Draws the ten digit images that go into a SpeedDigitWidget. Stock mode keeps
 * the official 64x90 mask shape; text mode renders a chosen Android typeface
 * at a higher source resolution.
 */
object DigitalDigitRenderer {

    fun renderAll(
        context: Context,
        style: DigitalDigitStyle,
        color: Int
    ): List<Bitmap> = (0..9).map { render(context, it.digitToChar(), style, color) }

    fun render(
        context: Context,
        digit: Char,
        style: DigitalDigitStyle,
        color: Int
    ): Bitmap {
        val safe = style.normalized()
        val height = safe.sourceHeight
        val width = max(1, (height * 64.0 / 90.0).roundToInt())
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val targetHeight = height * safe.scalePercent / 100f
        val targetWidth = width * safe.scalePercent / 100f
        val centerX = width / 2f + width * safe.offsetXPercent / 100f
        val centerY = height / 2f + height * safe.offsetYPercent / 100f

        when (safe.mode) {
            DigitMode.STOCK -> drawStock(context, canvas, digit, targetHeight, centerX, centerY)
            DigitMode.TEXT -> drawText(canvas, digit, safe.font, targetWidth, targetHeight, centerX, centerY, height)
        }

        applyThickness(output, safe.thickness, height)
        if (safe.hardEdges) hardEdges(output)

        val tinted = CreationAssets.tint(output, color)
        output.recycle()
        return tinted
    }

    private fun drawStock(
        context: Context,
        canvas: Canvas,
        digit: Char,
        targetHeight: Float,
        centerX: Float,
        centerY: Float
    ) {
        val source = loadStock(context, digit)
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val scale = targetHeight / source.height
        val targetWidth = max(1, (source.width * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(
            source,
            targetWidth,
            max(1, targetHeight.roundToInt()),
            true
        )
        if (scaled !== source) source.recycle()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            scaled,
            centerX - scaled.width / 2f,
            centerY - scaled.height / 2f,
            paint
        )
        scaled.recycle()
    }

    private fun drawText(
        canvas: Canvas,
        digit: Char,
        font: DigitFont,
        targetWidth: Float,
        targetHeight: Float,
        centerX: Float,
        centerY: Float,
        sourceHeight: Int
    ) {
        val typeface = try {
            Typeface.create(font.family, font.style)
        } catch (_: Exception) {
            Typeface.create(font.family, Typeface.NORMAL)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = sourceHeight * 1.6f
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val path = Path()
        paint.getTextPath(digit.toString(), 0, 1, 0f, 0f, path)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.isEmpty) return
        val scale = min(targetHeight / bounds.height(), targetWidth / bounds.width())
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(scale, scale)
        canvas.translate(-bounds.centerX(), -bounds.centerY())
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun applyThickness(bitmap: Bitmap, thickness: Int, sourceHeight: Int) {
        if (thickness == 0) return
        val radius = max(1, (abs(thickness) * sourceHeight / 90.0).roundToInt())
            .coerceAtMost(32)
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val alpha = IntArray(pixels.size) { (pixels[it] ushr 24) and 0xFF }
        val dilate = thickness > 0
        val horizontal = IntArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var value = if (dilate) 0 else 255
                val from = max(0, x - radius)
                val to = min(width - 1, x + radius)
                for (xx in from..to) {
                    val a = alpha[row + xx]
                    value = if (dilate) max(value, a) else min(value, a)
                }
                horizontal[row + x] = value
            }
        }
        val output = IntArray(pixels.size)
        for (y in 0 until height) {
            val fromY = max(0, y - radius)
            val toY = min(height - 1, y + radius)
            for (x in 0 until width) {
                var value = if (dilate) 0 else 255
                for (yy in fromY..toY) {
                    val a = horizontal[yy * width + x]
                    value = if (dilate) max(value, a) else min(value, a)
                }
                output[y * width + x] = (value shl 24) or 0x00FFFFFF
            }
        }
        bitmap.setPixels(output, 0, width, 0, 0, width, height)
    }

    private fun hardEdges(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val alpha = (pixels[i] ushr 24) and 0xFF
            pixels[i] = (if (alpha >= 128) 0xFF shl 24 else 0) or 0x00FFFFFF
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun loadStock(context: Context, digit: Char): Bitmap? = try {
        context.assets.open("${CreationAssets.ROOT}/fonts/speed_digit_$digit.png")
            .use { BitmapFactory.decodeStream(it) }
            ?: context.assets.open("${CreationAssets.ROOT}/fonts/notosans_$digit.png")
                .use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }
}
