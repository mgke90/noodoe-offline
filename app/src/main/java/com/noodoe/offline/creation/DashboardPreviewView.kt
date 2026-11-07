package com.noodoe.offline.creation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.min

/**
 * The 480x480 dashboard preview. It draws the same official assets that are
 * written into the creation bundle, so what is shown is what is uploaded.
 */
class DashboardPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { CLOCK, SPEEDOMETER }

    private var mode = Mode.CLOCK

    private var clockBackground: Bitmap? = null
    private var handStyle = 1
    private var hourColor = Color.parseColor("#6b6b6b")
    private var minuteColor = Color.parseColor("#a9a9a9")
    private var secondColor = Color.parseColor("#f12b89")
    private var showSecond = true
    private var showDate = true

    private var speedBackground: Bitmap? = null
    private var unit = "kmh"
    private var maxSpeed = 200
    private var analog = true
    private var markStyle = 1
    private var markColor = Color.WHITE
    private var needleStyle = 1
    private var needleColor = Color.parseColor("#fff12b89")
    private var centerColor = Color.WHITE
    private var barStyle = 3
    private var barColor = Color.parseColor("#fff12b89")
    private var digitalColor = Color.WHITE
    private var showBar = false
    private var composeMark = true
    private var speedValue = 0

    private var speedComposite: Bitmap? = null
    private var needleBitmap: Bitmap? = null
    private var centerBitmap: Bitmap? = null
    private var barBitmap: Bitmap? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun setClock(
        background: Bitmap?,
        handStyle: Int,
        hourColor: Int,
        minuteColor: Int,
        secondColor: Int,
        showSecond: Boolean,
        showDate: Boolean
    ) {
        mode = Mode.CLOCK
        clockBackground = background
        this.handStyle = handStyle.coerceIn(1, 6)
        this.hourColor = hourColor
        this.minuteColor = minuteColor
        this.secondColor = secondColor
        this.showSecond = showSecond
        this.showDate = showDate
        invalidate()
    }

    fun setSpeedometer(
        background: Bitmap?,
        unit: String,
        maxSpeed: Int,
        analog: Boolean,
        markStyle: Int,
        markColor: Int,
        needleStyle: Int,
        needleColor: Int,
        centerColor: Int,
        barStyle: Int,
        barColor: Int,
        digitalColor: Int,
        showBar: Boolean,
        composeMark: Boolean,
        speed: Int
    ) {
        mode = Mode.SPEEDOMETER
        speedBackground = background
        this.unit = if (unit == "mph") "mph" else "kmh"
        this.maxSpeed = maxSpeed.coerceAtLeast(1)
        this.analog = analog
        this.markStyle = markStyle.coerceIn(1, 6)
        this.markColor = markColor
        this.needleStyle = needleStyle.coerceIn(1, 6)
        this.needleColor = needleColor
        this.centerColor = centerColor
        this.barStyle = barStyle.coerceIn(1, 6)
        this.barColor = barColor
        this.digitalColor = digitalColor
        this.showBar = showBar
        this.composeMark = composeMark
        speedValue = speed.coerceIn(0, this.maxSpeed)
        rebuildSpeed()
        invalidate()
    }

    fun setSpeedValue(value: Int) {
        speedValue = value.coerceIn(0, maxSpeed)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val left = (width - size) / 2f
        val top = (height - size) / 2f

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(size / CreationAssets.SIZE, size / CreationAssets.SIZE)

        when (mode) {
            Mode.CLOCK -> drawClock(canvas)
            Mode.SPEEDOMETER -> drawSpeedometer(canvas)
        }
        canvas.restore()
    }

    private fun drawClock(canvas: Canvas) {
        val background = clockBackground
        if (background != null) {
            paint.reset()
            paint.isAntiAlias = true
            paint.isFilterBitmap = true
            canvas.drawBitmap(
                background,
                null,
                RectF(0f, 0f, 480f, 480f),
                paint
            )
        } else {
            paint.reset()
            paint.color = Color.rgb(24, 24, 28)
            canvas.drawRect(0f, 0f, 480f, 480f, paint)
        }

        val hour = CreationAssets.clockHand(context, "hour", handStyle)
        val minute = CreationAssets.clockHand(context, "minute", handStyle)
        val second = CreationAssets.clockHand(context, "second", handStyle)
        // The visible hub of the 240x430 hand PNGs is at y=310.5 (hour/
        // minute) and y=309 (second), not at the image midpoint. Placing the
        // hub on the dial centre removes the small base offset.
        drawClockHand(canvas, hour, 305f, hourColor, pivotY = 310.5f, scale = 1.00f)
        drawClockHand(canvas, minute, 60f, minuteColor, pivotY = 310.5f, scale = 0.95f)
        if (showSecond) {
            drawClockHand(canvas, second, 108f, secondColor, pivotY = 309f, scale = 0.82f)
        }
        hour?.recycle()
        minute?.recycle()
        second?.recycle()

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = secondColor
        canvas.drawCircle(240f, 240f, 8f, paint)

        if (showDate) {
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 27f
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            val weekday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            val names = arrayOf(
                "SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY",
                "THURSDAY", "FRIDAY", "SATURDAY"
            )
            canvas.drawText(names[(weekday - 1).coerceIn(0, 6)], 240f, 372f, paint)
        }
    }

    private fun drawClockHand(
        canvas: Canvas,
        source: Bitmap?,
        angle: Float,
        color: Int,
        pivotY: Float,
        scale: Float
    ) {
        if (source == null) return
        val tinted = CreationAssets.tint(source, color)
        val width = 240f * scale
        val height = 430f * scale
        val left = 240f - 120f * scale
        val top = 240f - pivotY * scale
        canvas.save()
        canvas.rotate(angle, 240f, 240f)
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        canvas.drawBitmap(
            tinted,
            null,
            RectF(left, top, left + width, top + height),
            paint
        )
        canvas.restore()
        tinted.recycle()
    }

    private fun drawSpeedometer(canvas: Canvas) {
        val composite = speedComposite
        if (composite != null) {
            paint.reset()
            paint.isAntiAlias = true
            paint.isFilterBitmap = true
            canvas.drawBitmap(composite, 0f, 0f, paint)
        } else {
            paint.reset()
            paint.color = Color.BLACK
            canvas.drawRect(0f, 0f, 480f, 480f, paint)
        }

        if (!analog && showBar) {
            val bar = barBitmap
            if (bar != null) {
                paint.reset()
                paint.isAntiAlias = true
                paint.isFilterBitmap = true
                canvas.drawBitmap(bar, null, RectF(0f, 0f, 480f, 480f), paint)
            }
        }

        if (analog) {
            val needle = needleBitmap
            if (needle != null) {
                val fraction = speedValue.toFloat() / maxSpeed.toFloat()
                val angle = -135f + fraction * 270f
                paint.reset()
                paint.isAntiAlias = true
                paint.isFilterBitmap = true
                canvas.save()
                canvas.rotate(angle, 240f, 240f)
                canvas.drawBitmap(needle, null, RectF(147f, 0f, 333f, 333f), paint)
                canvas.restore()
            }
            val center = centerBitmap
            if (center != null) {
                paint.reset()
                paint.isAntiAlias = true
                paint.isFilterBitmap = true
                canvas.drawBitmap(center, null, RectF(147f, 147f, 333f, 333f), paint)
            }
        } else {
            // The preview is an idealized, smooth rendering. The installed
            // bundle still uses the stock 64x90 glyphs; those look right on
            // the scooter's display but upscale badly in an Android view.
            paint.reset()
            paint.isAntiAlias = true
            paint.color = digitalColor
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = 118f
            canvas.drawText(speedValue.toString(), 240f, 292f, paint)
            paint.textSize = 24f
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            canvas.drawText(if (unit == "mph") "mph" else "km/h", 240f, 330f, paint)
        }
    }

    private fun rebuildSpeed() {
        speedComposite?.recycle()
        speedComposite = null
        needleBitmap?.recycle()
        needleBitmap = null
        centerBitmap?.recycle()
        centerBitmap = null
        barBitmap?.recycle()
        barBitmap = null

        val mark = if (composeMark) {
            if (analog) CreationAssets.speedMark(context, unit, markStyle, maxSpeed)
            else CreationAssets.digitalMark(context)
        } else {
            null
        }
        speedComposite = CreationAssets.composeSpeedBackground(
            speedBackground,
            mark,
            markColor,
            composeMark
        )
        mark?.recycle()

        if (analog) {
            needleBitmap = CreationAssets.speedNeedle(context, needleStyle)?.let {
                val tinted = CreationAssets.tint(it, needleColor)
                it.recycle()
                tinted
            }
            centerBitmap = CreationAssets.speedCenter(context, needleStyle)?.let {
                val tinted = CreationAssets.tint(it, centerColor)
                it.recycle()
                tinted
            }
        } else if (showBar) {
            barBitmap = CreationAssets.speedBar(context, barStyle)?.let {
                val tinted = CreationAssets.tint(it, barColor)
                it.recycle()
                tinted
            }
        }
    }

    override fun onDetachedFromWindow() {
        speedComposite?.recycle()
        speedComposite = null
        needleBitmap?.recycle()
        needleBitmap = null
        centerBitmap?.recycle()
        centerBitmap = null
        barBitmap?.recycle()
        barBitmap = null
        super.onDetachedFromWindow()
    }
}
