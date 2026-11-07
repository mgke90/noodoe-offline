package com.noodoe.offline.creation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object ClockCreationBuilder {

    const val TEMPLATE_DIR = "creation_templates/clock"
    const val LOCATION_CLOCK = 512

    data class Options(
        val background: Bitmap?,
        val handType: Int = 1,
        val hourColor: String = "#6b6b6b",
        val minuteColor: String = "#a9a9a9",
        val secondColor: String = "#f12b89",
        val showSecond: Boolean = true,
        val showDate: Boolean = true
    )

    /**
     * Exact official clock template, including all seven distinct weekday
     * images. The firmware renders `format:"EEEE"` from `weekdayImages`, so
     * pruning or repeating that list is what made the clock show the wrong
     * day. This is a 9-file bundle.
     */
    fun buildStock(context: Context): CreationBundle {
        val template = readTemplate(context)
        val root = JSONObject(String(template.cfgBytes, StandardCharsets.UTF_8))
        val widgets = root.getJSONObject("configuration").getJSONArray("widgets")
        var backgroundName: String? = null
        for (i in 0 until widgets.length()) {
            val widget = widgets.getJSONObject(i)
            if (widget.optString("name") == "BackgroundWidget") {
                backgroundName = widget.optJSONArray("images")?.optString(0)
            }
        }
        val background = backgroundName
            ?: throw IllegalStateException("clock template has no background")
        val usedFiles = collectUsedFiles(widgets, background)
        root.put("files", JSONArray(usedFiles.toList()))
        val cfgBytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        val cfg = CreationFile(md5Hex(cfgBytes) + ".cfg", cfgBytes)
        verifyIdentity(cfg)
        val images = usedFiles.map { name ->
            val bytes = template.images[name]
                ?: throw IllegalStateException("template file is missing: $name")
            CreationFile(name, bytes).also { verifyIdentity(it) }
        }
        // The official app iterates a HashMap whose cfg key ("creation")
        // lands in the last bucket, so the cfg is uploaded after all images.
        // Sending it first makes the dashboard parse a DateWidget whose seven
        // weekday images are still incomplete; the device then stops answering.
        return CreationBundle(
            location = LOCATION_CLOCK,
            files = images + cfg,
            contentId = cfg.md5
        )
    }

    /**
     * Smallest safe stock test: official dial + preset hands, no date images.
     * The full template has 9 files; this has only the cfg and background.
     */
    fun buildMinimalStock(context: Context): CreationBundle {
        val template = readTemplate(context)
        val root = JSONObject(String(template.cfgBytes, StandardCharsets.UTF_8))
        val widgets = root.getJSONObject("configuration").getJSONArray("widgets")
        var backgroundName: String? = null
        val retained = JSONArray()
        for (i in 0 until widgets.length()) {
            val widget = widgets.getJSONObject(i)
            when (widget.optString("name")) {
                "BackgroundWidget" -> {
                    backgroundName = widget.optJSONArray("images")?.optString(0)
                    retained.put(widget)
                }
                "WatchHandWidget" -> retained.put(widget)
            }
        }
        val background = backgroundName
            ?: throw IllegalStateException("clock template has no background")
        val backgroundBytes = template.images[background]
            ?: throw IllegalStateException("clock background is missing")
        root.put("files", JSONArray().put(background))
        root.getJSONObject("configuration").put("widgets", retained)
        val cfgBytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        val cfg = CreationFile(md5Hex(cfgBytes) + ".cfg", cfgBytes)
        verifyIdentity(cfg)
        val backgroundFile = CreationFile(background, backgroundBytes)
        verifyIdentity(backgroundFile)
        return CreationBundle(
            location = LOCATION_CLOCK,
            files = listOf(backgroundFile, cfg),
            contentId = cfg.md5
        )
    }

    fun buildCustom(context: Context, options: Options): CreationBundle {
        val template = readTemplate(context)
        val root = JSONObject(String(template.cfgBytes, StandardCharsets.UTF_8))
        val filesJson = root.getJSONArray("files")
        val widgets = root.getJSONObject("configuration").getJSONArray("widgets")

        var oldBackground: String? = null
        var backgroundWidget: JSONObject? = null
        for (i in 0 until widgets.length()) {
            val widget = widgets.getJSONObject(i)
            if (widget.optString("name") == "BackgroundWidget") {
                backgroundWidget = widget
                oldBackground = widget.optJSONArray("images")?.optString(0)
            }
        }
        if (backgroundWidget == null || oldBackground.isNullOrEmpty()) {
            throw IllegalStateException("clock template has no background")
        }

        val newBackground = options.background?.let {
            val bytes = backgroundJpeg(it)
            val name = md5Hex(bytes) + ".jpg"
            Triple(name, bytes, name)
        }

        if (newBackground != null) {
            backgroundWidget.put("imageType", "single")
            backgroundWidget.put("images", JSONArray().put(newBackground.first))
        }

        val retainedWidgets = JSONArray()
        for (i in 0 until widgets.length()) {
            val widget = widgets.getJSONObject(i)
            when (widget.optString("name")) {
                "DateWidget" -> if (options.showDate) retainedWidgets.put(widget)
                "WatchHandWidget" -> {
                    widget.put("watchHandType", options.handType.coerceIn(1, 6))
                    widget.put("hourColor", normalizeColor(options.hourColor))
                    widget.put("minuteColor", normalizeColor(options.minuteColor))
                    widget.put(
                        "secondColor",
                        if (options.showSecond) {
                            normalizeColor(options.secondColor)
                        } else {
                            transparentColor(options.secondColor)
                        }
                    )
                    retainedWidgets.put(widget)
                }
                else -> retainedWidgets.put(widget)
            }
        }

        // Only send images that the retained widgets actually reference.
        // When the date is kept, this includes all seven distinct weekday
        // images because the firmware renders `EEEE` from them.
        val usedFiles = collectUsedFiles(
            retainedWidgets,
            newBackground?.first ?: oldBackground
        )
        root.put("files", JSONArray(usedFiles.toList()))
        root.getJSONObject("configuration").put("widgets", retainedWidgets)

        val cfgBytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        val cfgName = md5Hex(cfgBytes) + ".cfg"
        val cfg = CreationFile(cfgName, cfgBytes)
        verifyIdentity(cfg)

        val imageFiles = ArrayList<CreationFile>()
        for (name in usedFiles) {
            val bytes = if (newBackground != null && name == newBackground.first) {
                newBackground.second
            } else {
                template.images[name]
                    ?: throw IllegalStateException("template file is missing: $name")
            }
            val file = CreationFile(name, bytes)
            verifyIdentity(file)
            imageFiles.add(file)
        }
        return CreationBundle(
            location = LOCATION_CLOCK,
            files = imageFiles + cfg,
            contentId = cfg.md5
        )
    }

    private fun collectUsedFiles(
        widgets: JSONArray,
        backgroundName: String
    ): LinkedHashSet<String> {
        val used = LinkedHashSet<String>()
        used.add(backgroundName)
        for (i in 0 until widgets.length()) {
            val widget = widgets.getJSONObject(i)
            val images = widget.optJSONArray("images")
            if (images != null) {
                for (j in 0 until images.length()) used.add(images.getString(j))
            }
            for (key in listOf(
                "digitImages",
                "monthImages",
                "periodImages",
                "punctuationImages",
                "weekdayImages"
            )) {
                val array = widget.optJSONArray(key) ?: continue
                for (j in 0 until array.length()) used.add(array.getString(j))
            }
        }
        return used
    }

    fun templateBackground(context: Context): Bitmap? {
        val template = readTemplate(context)
        val root = JSONObject(String(template.cfgBytes, StandardCharsets.UTF_8))
        val widgets = root.getJSONObject("configuration").getJSONArray("widgets")
        for (i in 0 until widgets.length()) {
            val image = widgets.getJSONObject(i)
                .takeIf { it.optString("name") == "BackgroundWidget" }
                ?.optJSONArray("images")
                ?.optString(0)
                ?: continue
            val bytes = template.images[image] ?: continue
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        return null
    }

    private data class Template(
        val cfgName: String,
        val cfgBytes: ByteArray,
        val images: Map<String, ByteArray>
    )

    private fun readTemplate(context: Context): Template {
        val names = context.assets.list(TEMPLATE_DIR)?.toList()
            ?: throw IllegalStateException("missing clock template")
        val cfgName = names.firstOrNull { it.endsWith(".cfg") }
            ?: throw IllegalStateException("clock template has no cfg")
        val cfgBytes = context.assets.open("$TEMPLATE_DIR/$cfgName").use { it.readBytes() }
        val images = names.filterNot { it.endsWith(".cfg") }.associateWith { name ->
            context.assets.open("$TEMPLATE_DIR/$name").use { it.readBytes() }
        }
        return Template(cfgName, cfgBytes, images)
    }

    private fun verifyIdentity(file: CreationFile) {
        val extensionAt = file.name.lastIndexOf('.')
        if (extensionAt != 32) {
            throw IllegalStateException("invalid creation file name: ${file.name}")
        }
        val identity = file.name.substring(0, extensionAt)
        if (!identity.equals(md5Hex(file.data), ignoreCase = true)) {
            throw IllegalStateException("MD5 mismatch: ${file.name}")
        }
    }

    private fun backgroundJpeg(source: Bitmap): ByteArray {
        val width = source.width
        val height = source.height
        val side = minOf(width, height)
        val x = (width - side) / 2
        val y = (height - side) / 2
        val square = Bitmap.createBitmap(source, x, y, side, side)
        val scaled = Bitmap.createScaledBitmap(square, 480, 480, true)
        if (square !== scaled) square.recycle()
        return try {
            ByteArrayOutputStream().use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    throw IllegalStateException("background compression failed")
                }
                output.toByteArray()
            }
        } finally {
            scaled.recycle()
        }
    }

    private fun normalizeColor(value: String): String {
        val clean = value.trim().removePrefix("#")
        if (!clean.matches(Regex("[0-9a-fA-F]{6}"))) {
            throw IllegalArgumentException("invalid color: $value")
        }
        return "#" + clean.lowercase()
    }

    /**
     * The device recognizes ARGB color strings but does not have a
     * showSecondHand JSON field. Hiding the second hand is done by making its
     * color fully transparent.
     */
    private fun transparentColor(value: String): String {
        val clean = normalizeColor(value).removePrefix("#")
        return "#00$clean"
    }
}
