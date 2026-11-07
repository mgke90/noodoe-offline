package com.noodoe.offline.creation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Official Noodoe assets copied from the SR1.5 Pack B resource archive and
 * the official APK preset folder. Everything is loaded from app assets; no
 * network or storage permission is involved.
 */
object CreationAssets {

    const val ROOT = "creation_assets"
    const val SIZE = 480

    data class BackgroundOption(val asset: String, val label: String)

    val clockBackgrounds = listOf(
        BackgroundOption("backgrounds/clock_01.jpg", "Official clock dial"),
        BackgroundOption("backgrounds/clock_02.jpg", "Road photo"),
        BackgroundOption("backgrounds/clock_05.jpg", "Concentric rings")
    )

    fun speedBackgrounds(unit: String): List<BackgroundOption> {
        return if (unit == "mph") listOf(
            BackgroundOption("backgrounds/mph_1_25.jpg", "MPH 25"),
            BackgroundOption("backgrounds/mph_2_40.jpg", "MPH 40"),
            BackgroundOption("backgrounds/mph_8_55.jpg", "MPH 55"),
            BackgroundOption("backgrounds/mph_3_75.jpg", "MPH 75"),
            BackgroundOption("backgrounds/mph_4_90.jpg", "MPH 90"),
            BackgroundOption("backgrounds/mph_5_100.jpg", "MPH 100"),
            BackgroundOption("backgrounds/mph_6_110.jpg", "MPH 110"),
            BackgroundOption("backgrounds/mph_7_125.jpg", "MPH 125")
        ) else listOf(
            BackgroundOption("backgrounds/kmh_1_40.jpg", "KMH 40"),
            BackgroundOption("backgrounds/kmh_2_60.jpg", "KMH 60"),
            BackgroundOption("backgrounds/kmh_8_90.jpg", "KMH 90"),
            BackgroundOption("backgrounds/kmh_3_120.jpg", "KMH 120"),
            BackgroundOption("backgrounds/kmh_4_140.jpg", "KMH 140"),
            BackgroundOption("backgrounds/kmh_5_160.jpg", "KMH 160"),
            BackgroundOption("backgrounds/kmh_6_180.jpg", "KMH 180"),
            BackgroundOption("backgrounds/kmh_7_200.jpg", "KMH 200")
        )
    }

    fun maxSpeeds(unit: String): List<Int> =
        if (unit == "mph") listOf(25, 40, 55, 75, 90, 100, 110, 125, 150)
        else listOf(40, 60, 90, 120, 140, 160, 180, 200, 240)

    fun defaultBackground(unit: String): String =
        if (unit == "mph") "backgrounds/mph_7_125.jpg" else "backgrounds/kmh_7_200.jpg"

    fun background(context: Context, asset: String): Bitmap? = load(context, "$ROOT/$asset")

    /** Official dashboard JPEGs already contain their speedometer face. */
    fun hasBuiltInFace(asset: String?): Boolean =
        asset != null && (asset.startsWith("backgrounds/kmh_") || asset.startsWith("backgrounds/mph_"))

    fun clockHand(context: Context, kind: String, style: Int): Bitmap? =
        load(context, "$ROOT/clock/${kind}_${style.coerceIn(1, 6)}.png")

    fun speedNeedle(context: Context, style: Int): Bitmap? =
        load(context, "$ROOT/speed/needle_${style.coerceIn(1, 6)}.png")

    fun speedCenter(context: Context, style: Int): Bitmap? =
        load(context, "$ROOT/speed/center_${style.coerceIn(1, 6)}.png")

    fun speedMark(
        context: Context,
        unit: String,
        style: Int,
        maxSpeed: Int
    ): Bitmap? {
        val prefix = if (unit == "mph") "mph" else "kmh"
        val max = nearestMax(unit, maxSpeed)
        val safeStyle = style.coerceIn(1, 6)
        return load(context, "$ROOT/speed/mark_${prefix}_${safeStyle}_${max}.png")
    }

    fun digitalMark(context: Context): Bitmap? =
        load(context, "$ROOT/speed/mark_digital.png")

    fun speedBar(context: Context, style: Int): Bitmap? =
        load(context, "$ROOT/speed/bar_${style.coerceIn(1, 6)}.png")

    fun fuelBattery(context: Context): Bitmap? =
        load(context, "$ROOT/speed/fuel_battery_100.png")

    fun nearestMax(unit: String, value: Int): Int {
        val choices = maxSpeeds(unit)
        return choices.minByOrNull { kotlin.math.abs(it - value) } ?: value
    }

    fun tint(source: Bitmap, color: Int): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /** Background plus the official speed mark, flattened to one 480x480 frame. */
    fun composeSpeedBackground(
        background: Bitmap?,
        mark: Bitmap?,
        markColor: Int,
        composeMark: Boolean = true
    ): Bitmap {
        val output = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        background?.let { drawSquare(canvas, it, paint) }
        if (composeMark && mark != null) {
            val tinted = tint(mark, markColor)
            drawSquare(canvas, tinted, paint)
            tinted.recycle()
        }
        return output
    }

    fun jpeg(bitmap: Bitmap, quality: Int = 92): ByteArray =
        ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                throw IllegalStateException("JPEG compression failed")
            }
            output.toByteArray()
        }

    fun png(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IllegalStateException("PNG compression failed")
            }
            output.toByteArray()
        }

    /**
     * Official stock digital-speedometer glyphs. The firmware's built-in
     * dashboard draws the `Default_Dashboard/speed_digit_N-fs8.raw` mask at
     * its native 64x90 size. Scaling it to 125 px (1.39x) made the 1-bit
     * edges look broken on the scooter, so the default is native height.
     * The 21x28 Noto Sans preset is only a fallback.
     */
    fun digitBitmaps(context: Context, color: Int, height: Int = 90): List<Bitmap> {
        return (0..9).map { digit ->
            val source = load(context, "$ROOT/fonts/speed_digit_$digit.png")
                ?: load(context, "$ROOT/fonts/notosans_$digit.png")
                ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val scale = height.toFloat() / source.height
            val width = (source.width * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(source, width, height, true)
            if (scaled !== source) source.recycle()
            val tinted = tint(scaled, color)
            scaled.recycle()
            tinted
        }
    }

    fun colorFromHex(value: String): Int = try {
        Color.parseColor(value)
    } catch (_: Exception) {
        Color.WHITE
    }

    fun argb(color: Int): String = String.format(Locale.US, "#%08X", color)

    private fun load(context: Context, path: String): Bitmap? = try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    private fun drawSquare(canvas: Canvas, bitmap: Bitmap, paint: Paint) {
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()),
            paint
        )
    }
}
