package com.noodoe.offline.creation

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Builds a speedometer creation from the official Pack B assets.
 *
 * The official APK has no packaged speedometer creation template; its
 * speedometer editor writes the same bundle shape used here:
 *   - a flattened 480x480 background containing the speed mark
 *   - a SpeedNeedleWidget for analog, or SpeedDigitWidget (+ optional bar)
 *
 * This is intentionally marked experimental for the current release.
 */
object SpeedometerCreationBuilder {

    const val LOCATION_SPEEDOMETER = 1024

    data class Options(
        val background: Bitmap?,
        val unit: String = "kmh",
        val maxSpeed: Int = 200,
        val analog: Boolean = true,
        val markStyle: Int = 1,
        val markColor: String = "#FFFFFFFF",
        val needleStyle: Int = 1,
        val needleColor: String = "#FFF12B89",
        val centerColor: String = "#FFFFFFFF",
        val digitalColor: String = "#FFFFFFFF",
        val barStyle: Int = 3,
        val barColor: String = "#FFF12B89",
        val showBar: Boolean = false,
        val composeMark: Boolean = true,
        val digitStyle: DigitalDigitStyle = DigitalDigitStyle()
    )

    fun build(context: Context, options: Options): CreationBundle {
        val unit = if (options.unit == "mph") "mph" else "kmh"
        val maxSpeed = CreationAssets.nearestMax(unit, options.maxSpeed)
        val markColor = CreationAssets.colorFromHex(options.markColor)
        val mark = if (options.composeMark) {
            if (options.analog) CreationAssets.speedMark(context, unit, options.markStyle, maxSpeed)
            else CreationAssets.digitalMark(context)
        } else {
            null
        }
        val composite = CreationAssets.composeSpeedBackground(
            options.background,
            mark,
            markColor,
            options.composeMark
        )
        mark?.recycle()
        val backgroundBytes = CreationAssets.jpeg(composite)
        composite.recycle()
        val backgroundName = md5Hex(backgroundBytes) + ".jpg"

        val assets = ArrayList<CreationFile>()
        assets.add(verifiedFile(backgroundName, backgroundBytes))

        val widgets = JSONArray()
        widgets.put(
            JSONObject()
                .put("name", "BackgroundWidget")
                .put("images", JSONArray().put(backgroundName))
                .put(
                    "imageType",
                    if (options.analog || !options.composeMark) "analogSpeed" else "single"
                )
        )

        if (options.analog) {
            widgets.put(
                JSONObject()
                    .put("name", "SpeedNeedleWidget")
                    .put("needleType", options.needleStyle.coerceIn(1, 6))
                    .put("needleColor", CreationAssets.argb(CreationAssets.colorFromHex(options.needleColor)))
                    .put("centerColor", CreationAssets.argb(CreationAssets.colorFromHex(options.centerColor)))
            )
        } else {
            val glyphNames = ArrayList<String>()
            for (glyph in CreationAssets.digitBitmaps(
                context,
                CreationAssets.colorFromHex(options.digitalColor),
                options.digitStyle
            )) {
                val bytes = CreationAssets.png(glyph)
                glyph.recycle()
                val name = md5Hex(bytes) + ".png"
                assets.add(verifiedFile(name, bytes))
                glyphNames.add(name)
            }
            widgets.put(
                JSONObject()
                    .put("name", "SpeedDigitWidget")
                    .put("images", JSONArray(glyphNames))
            )
            // The firmware uses SpeedBarWidget to mark the creation as a
            // digital speedometer. Keep it present even when the bar is
            // hidden; a fully transparent color makes it invisible.
            widgets.put(
                JSONObject()
                    .put("name", "SpeedBarWidget")
                    .put(
                        "speedBarColor",
                        if (options.showBar) {
                            CreationAssets.argb(CreationAssets.colorFromHex(options.barColor))
                        } else {
                            "#00000000"
                        }
                    )
                    .put("speedBarType", options.barStyle.coerceIn(1, 6))
            )
        }

        val root = JSONObject()
        root.put("files", JSONArray(assets.map { it.name }))
        root.put(
            "configuration",
            JSONObject().put("widgets", widgets)
        )
        val cfgBytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        val cfgName = md5Hex(cfgBytes) + ".cfg"
        val cfg = verifiedFile(cfgName, cfgBytes)

        // Match the official bundle order: images first, cfg last. The
        // dashboard must not parse a SpeedDigitWidget before all ten glyphs
        // are present.
        return CreationBundle(
            location = LOCATION_SPEEDOMETER,
            files = assets + cfg,
            contentId = cfg.md5
        )
    }

    private fun verifiedFile(name: String, data: ByteArray): CreationFile {
        val extensionAt = name.lastIndexOf('.')
        require(extensionAt == 32) { "invalid creation file name: $name" }
        require(name.substring(0, extensionAt).equals(md5Hex(data), ignoreCase = true)) {
            "MD5 mismatch: $name"
        }
        return CreationFile(name, data)
    }
}
