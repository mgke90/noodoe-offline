package com.noodoe.offline.creation

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.tabs.TabLayout
import com.noodoe.offline.NoodoeApp
import com.noodoe.offline.R
import com.noodoe.offline.databinding.ActivityCreationStudioBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CreationStudioActivity : AppCompatActivity() {

    private enum class BackgroundTarget { CLOCK, SPEED }

    private lateinit var binding: ActivityCreationStudioBinding
    private lateinit var controller: com.noodoe.offline.data.NoodoeController

    private var clockBackground: Bitmap? = null
    private var speedBackground: Bitmap? = null
    private var clockBackgroundAsset = "backgrounds/clock_01.jpg"
    private var speedBackgroundAsset = "backgrounds/kmh_7_200.jpg"
    private var speedCustom = false
    private var speedBackgroundIncludesFace = true
    private var backgroundTarget = BackgroundTarget.CLOCK

    private var hourColor = "#6b6b6b"
    private var minuteColor = "#a9a9a9"
    private var secondColor = "#f12b89"
    private var needleColor = "#f12b89"
    private var centerColor = "#ffffff"
    private var markColor = "#ffffff"
    private var barColor = "#f12b89"
    private var digitalColor = "#ffffff"

    private var digitMode = DigitMode.STOCK
    private var digitFont = DigitFont.CONDENSED_LIGHT
    private var digitHeight = 90
    private var digitScale = 80
    private var digitThickness = -1
    private var digitOffsetX = 0
    private var digitOffsetY = 0
    private var digitHard = false

    private var stockVerified = false
    private var installInProgress = false
    private var installLabel: String? = null
    private var installStartedStatus: String = ""
    private var updatingSpinners = false

    private val studioPrefs by lazy {
        getSharedPreferences("dashboard_studio", MODE_PRIVATE)
    }

    private val colors = listOf(
        "#ffffff", "#d9d9d9", "#a9a9a9", "#6b6b6b", "#333333", "#000000",
        "#e53935", "#f12b89", "#fb8c00", "#fdd835", "#43a047", "#00a77a",
        "#1e88e5", "#3949ab", "#8e24aa", "#6d4c41"
    )

    private val backgroundPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) loadBackground(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityCreationStudioBinding.inflate(layoutInflater)
        setContentView(binding.root)
        controller = (application as NoodoeApp).controller

        ViewCompat.setOnApplyWindowInsetsListener(binding.studioRoot) { view, insets ->
            view.updatePadding(
                top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
                bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            )
            insets
        }

        binding.studioToolbar.setNavigationIcon(
            androidx.appcompat.R.drawable.abc_ic_ab_back_material
        )
        binding.studioToolbar.navigationIcon?.setTint(Color.WHITE)
        binding.studioToolbar.setNavigationOnClickListener { finish() }

        setupSpinners()
        setupClockControls()
        setupSpeedControls()
        setupTabs()

        binding.minimalStockButton.setOnClickListener { confirmMinimalStockInstall() }
        binding.stockInstallButton.setOnClickListener { confirmClockInstall(stock = true) }
        binding.clockInstallButton.setOnClickListener { confirmClockInstall(stock = false) }
        binding.speedInstallButton.setOnClickListener { confirmSpeedInstall() }
        binding.digitalBatchButton.setOnClickListener { confirmDigitalBatchInstall() }
        stockVerified = studioPrefs.getBoolean("stock_verified", false)
        applyStockGate()

        lifecycleScope.launch {
            val clock = withContext(Dispatchers.IO) {
                CreationAssets.background(this@CreationStudioActivity, clockBackgroundAsset)
            }
            val speed = withContext(Dispatchers.IO) {
                CreationAssets.background(this@CreationStudioActivity, speedBackgroundAsset)
            }
            clockBackground = clock
            speedBackground = speed
            binding.clockBackgroundLabel.text = "Background: official clock 01"
            updateClockPreview()
            updateSpeedPreview()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { state ->
                    if (!state.connected && installInProgress) {
                        endInstall()
                    }
                    if (state.creationStatus.isNotBlank()) {
                        binding.studioStatus.text = state.creationStatus
                        if (state.creationStatus.startsWith("stock clock test: installed") ||
                            state.creationStatus.startsWith("stock clock minimal test: installed")
                        ) {
                            stockVerified = true
                            studioPrefs.edit().putBoolean("stock_verified", true).apply()
                            applyStockGate()
                        }
                        val currentLabel = installLabel
                        if (installInProgress && currentLabel != null &&
                            state.creationStatus != installStartedStatus &&
                            state.creationStatus.startsWith(currentLabel) &&
                            isTerminalCreationStatus(state.creationStatus)
                        ) {
                            endInstall()
                        }
                    }
                }
            }
        }
    }

    private fun applyStockGate() {
        val available = !installInProgress
        binding.minimalStockButton.isEnabled = available
        binding.stockInstallButton.isEnabled = available
        binding.clockInstallButton.isEnabled = stockVerified && available
        binding.clockInstallButton.alpha = if (stockVerified && available) 1f else 0.55f
        binding.minimalStockButton.alpha = if (available) 1f else 0.55f
        binding.stockInstallButton.alpha = if (available) 1f else 0.55f
        binding.clockInstallButton.text = when {
            installInProgress -> "INSTALLING…"
            stockVerified -> "INSTALL CUSTOM CLOCK"
            else -> "INSTALL CUSTOM CLOCK (stock test first)"
        }
        updateSpeedInstallGate()
    }

    private fun updateSpeedInstallGate() {
        val analog = binding.speedModeSpinner.selectedItemPosition == 0
        val available = stockVerified && !installInProgress
        binding.speedInstallButton.isEnabled = available
        binding.speedInstallButton.alpha = if (available) 1f else 0.55f
        binding.speedInstallButton.text = when {
            installInProgress -> "INSTALLING…"
            !stockVerified -> "INSTALL SPEEDOMETER (stock test first)"
            analog -> "INSTALL SPEEDOMETER (ANALOG)"
            else -> "INSTALL SPEEDOMETER (DIGITAL)"
        }
        binding.digitalBatchButton.isEnabled = stockVerified && !analog && !installInProgress
        binding.digitalBatchButton.alpha = if (stockVerified && !analog && !installInProgress) 1f else 0.55f
        binding.digitalBatchButton.text = if (stockVerified) {
            "INSTALL DIGITAL (BATCH FALLBACK)"
        } else {
            "DIGITAL FALLBACK (stock test first)"
        }
    }

    private fun isTerminalCreationStatus(status: String): Boolean {
        val value = status.lowercase()
        return value.contains("installed") ||
                value.contains("committed") ||
                value.contains("failed") ||
                value.contains("not connected") ||
                value.contains("already present")
    }

    private fun beginInstall(label: String) {
        installInProgress = true
        installLabel = label
        installStartedStatus = controller.state.value.creationStatus
        applyStockGate()
    }

    private fun endInstall() {
        installInProgress = false
        installLabel = null
        installStartedStatus = ""
        applyStockGate()
    }

    private fun setupTabs() {
        binding.studioTabs.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    val clock = tab.position == 0
                    binding.clockSection.visibility = if (clock) View.VISIBLE else View.GONE
                    binding.speedSection.visibility = if (clock) View.GONE else View.VISIBLE
                    if (clock) updateClockPreview() else updateSpeedPreview()
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            }
        )
    }

    private fun setupSpinners() {
        binding.handStyleSpinner.adapter = adapter((1..6).map { "Style $it" })
        binding.needleStyleSpinner.adapter = adapter((1..6).map { "Needle $it" })
        binding.markStyleSpinner.adapter = adapter((1..6).map { "Mark $it" })
        binding.barStyleSpinner.adapter = adapter((1..6).map { "Bar $it" })
        binding.speedModeSpinner.adapter = adapter(listOf("Analog", "Digital"))
        binding.unitSpinner.adapter = adapter(listOf("km/h", "mph"))
        binding.digitModeSpinner.adapter = adapter(DigitMode.values().map { it.label })
        binding.digitFontSpinner.adapter = adapter(DigitFont.values().map { it.label })
        binding.digitResolutionSpinner.adapter = adapter(
            DigitalDigitStyle.HEIGHTS.map { height ->
                when (height) {
                    90 -> "90px (stock)"
                    180 -> "180px (2x)"
                    270 -> "270px (3x)"
                    360 -> "360px (4x)"
                    else -> "${height}px"
                }
            }
        )

        binding.handStyleSpinner.onItemSelectedListener = simpleListener { updateClockPreview() }
        binding.needleStyleSpinner.onItemSelectedListener = simpleListener { updateSpeedPreview() }
        binding.markStyleSpinner.onItemSelectedListener = simpleListener { updateSpeedPreview() }
        binding.barStyleSpinner.onItemSelectedListener = simpleListener { updateSpeedPreview() }
        binding.speedModeSpinner.onItemSelectedListener = simpleListener {
            updateSpeedModeVisibility()
            updateSpeedPreview()
        }
        binding.digitModeSpinner.onItemSelectedListener = simpleListener {
            if (updatingSpinners) return@simpleListener
            digitMode = DigitMode.values()[
                binding.digitModeSpinner.selectedItemPosition.coerceIn(0, DigitMode.values().size - 1)
            ]
            onDigitTuningChanged()
        }
        binding.digitFontSpinner.onItemSelectedListener = simpleListener {
            if (updatingSpinners) return@simpleListener
            digitFont = DigitFont.values()[
                binding.digitFontSpinner.selectedItemPosition.coerceIn(0, DigitFont.values().size - 1)
            ]
            onDigitTuningChanged()
        }
        binding.digitResolutionSpinner.onItemSelectedListener = simpleListener {
            if (updatingSpinners) return@simpleListener
            digitHeight = DigitalDigitStyle.HEIGHTS[
                binding.digitResolutionSpinner.selectedItemPosition.coerceIn(
                    0,
                    DigitalDigitStyle.HEIGHTS.size - 1
                )
            ]
            onDigitTuningChanged()
        }
        binding.unitSpinner.onItemSelectedListener = simpleListener {
            if (updatingSpinners) return@simpleListener
            rebuildUnitOptions()
            updateSpeedPreview()
        }
        binding.maxSpeedSpinner.onItemSelectedListener = simpleListener {
            if (updatingSpinners) return@simpleListener
            updateSpeedSliderRange()
            updateSpeedPreview()
        }
        binding.speedBackgroundSpinner.onItemSelectedListener = simpleListener {
            if (updatingSpinners) return@simpleListener
            applySpeedBackgroundSelection()
        }
        binding.clockBackgroundSpinner.onItemSelectedListener = simpleListener {
            if (updatingSpinners) return@simpleListener
            applyClockBackgroundSelection()
        }
        rebuildUnitOptions()
        updateSpeedModeVisibility()
    }

    private fun setupClockControls() {
        binding.clockBackgroundSpinner.adapter = adapter(
            listOf("Custom image") + CreationAssets.clockBackgrounds.map { it.label }
        )
        updatingSpinners = true
        binding.clockBackgroundSpinner.setSelection(1, false)
        updatingSpinners = false

        binding.hourColorButton.text = colorButtonText("HOUR HAND", hourColor)
        binding.minuteColorButton.text = colorButtonText("MINUTE HAND", minuteColor)
        binding.secondColorButton.text = colorButtonText("SECOND HAND", secondColor)

        binding.hourColorButton.setOnClickListener {
            pickColor("Hour hand color", hourColor) {
                hourColor = it
                binding.hourColorButton.text = colorButtonText("HOUR HAND", it)
                updateClockPreview()
            }
        }
        binding.minuteColorButton.setOnClickListener {
            pickColor("Minute hand color", minuteColor) {
                minuteColor = it
                binding.minuteColorButton.text = colorButtonText("MINUTE HAND", it)
                updateClockPreview()
            }
        }
        binding.secondColorButton.setOnClickListener {
            pickColor("Second hand color", secondColor) {
                secondColor = it
                binding.secondColorButton.text = colorButtonText("SECOND HAND", it)
                updateClockPreview()
            }
        }

        binding.pickClockBackgroundButton.setOnClickListener {
            backgroundTarget = BackgroundTarget.CLOCK
            backgroundPicker.launch("image/*")
        }
        binding.useClockTemplateButton.setOnClickListener {
            setClockBackground(CreationAssets.clockBackgrounds.first())
        }
        binding.secondHandSwitch.setOnCheckedChangeListener { _, _ -> updateClockPreview() }
        binding.dateSwitch.setOnCheckedChangeListener { _, _ -> updateClockPreview() }
    }

    private fun setupSpeedControls() {
        binding.speedBackgroundSpinner.adapter = adapter(speedBackgroundLabels("kmh"))
        val defaultSpeedBackground = CreationAssets.speedBackgrounds("kmh")
            .indexOfFirst { it.asset == CreationAssets.defaultBackground("kmh") } + 2
        updatingSpinners = true
        binding.speedBackgroundSpinner.setSelection(defaultSpeedBackground, false)
        updatingSpinners = false

        binding.needleColorButton.text = colorButtonText("NEEDLE", needleColor)
        binding.centerColorButton.text = colorButtonText("NEEDLE CENTER", centerColor)
        binding.markColorButton.text = colorButtonText("MARK", markColor)
        binding.barColorButton.text = colorButtonText("BAR", barColor)
        binding.digitalColorButton.text = colorButtonText("DIGITS", digitalColor)

        binding.needleColorButton.setOnClickListener {
            pickColor("Needle color", needleColor) {
                needleColor = it
                binding.needleColorButton.text = colorButtonText("NEEDLE", it)
                updateSpeedPreview()
            }
        }
        binding.centerColorButton.setOnClickListener {
            pickColor("Needle center color", centerColor) {
                centerColor = it
                binding.centerColorButton.text = colorButtonText("NEEDLE CENTER", it)
                updateSpeedPreview()
            }
        }
        binding.markColorButton.setOnClickListener {
            pickColor("Speed mark color", markColor) {
                markColor = it
                binding.markColorButton.text = colorButtonText("MARK", it)
                updateSpeedPreview()
            }
        }
        binding.barColorButton.setOnClickListener {
            pickColor("Speed bar color", barColor) {
                barColor = it
                binding.barColorButton.text = colorButtonText("BAR", it)
                updateSpeedPreview()
            }
        }
        binding.digitalColorButton.setOnClickListener {
            pickColor("Digital speed color", digitalColor) {
                digitalColor = it
                binding.digitalColorButton.text = colorButtonText("DIGITS", it)
                updateSpeedPreview()
            }
        }

        binding.pickSpeedBackgroundButton.setOnClickListener {
            backgroundTarget = BackgroundTarget.SPEED
            backgroundPicker.launch("image/*")
        }
        binding.useSpeedBackgroundButton.setOnClickListener {
            setSpeedBackground(
                CreationAssets.speedBackgrounds(currentUnit())
                    .first { it.asset == CreationAssets.defaultBackground(currentUnit()) }
            )
        }
        binding.showBarSwitch.setOnCheckedChangeListener { _, _ -> updateSpeedPreview() }
        loadDigitTuning()
        binding.digitHardSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSpinners) return@setOnCheckedChangeListener
            digitHard = checked
            onDigitTuningChanged()
        }
        binding.digitScaleSlider.setOnSeekBarChangeListener(digitSliderListener(
            onProgress = {
                digitScale = 40 + it
                binding.digitScaleLabel.text = "Digit size: $digitScale%"
            },
            onFinished = { onDigitTuningChanged() }
        ))
        binding.digitThicknessSlider.setOnSeekBarChangeListener(digitSliderListener(
            onProgress = {
                digitThickness = it - 2
                binding.digitThicknessLabel.text =
                    "Thickness: ${if (digitThickness > 0) "+$digitThickness" else "$digitThickness"}"
            },
            onFinished = { onDigitTuningChanged() }
        ))
        binding.digitOffsetXSlider.setOnSeekBarChangeListener(digitSliderListener(
            onProgress = {
                digitOffsetX = it - 30
                binding.digitOffsetXLabel.text = "X offset: $digitOffsetX%"
            },
            onFinished = { onDigitTuningChanged() }
        ))
        binding.digitOffsetYSlider.setOnSeekBarChangeListener(digitSliderListener(
            onProgress = {
                digitOffsetY = it - 30
                binding.digitOffsetYLabel.text = "Y offset: $digitOffsetY%"
            },
            onFinished = { onDigitTuningChanged() }
        ))
        binding.digitResetButton.setOnClickListener { resetDigitTuning() }
        binding.speedSlider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateSpeedLabel(progress)
                    binding.speedPreview.setSpeedValue(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
    }

    private fun digitStyle(): DigitalDigitStyle = DigitalDigitStyle(
        mode = digitMode,
        font = digitFont,
        sourceHeight = digitHeight,
        scalePercent = digitScale,
        thickness = digitThickness,
        offsetXPercent = digitOffsetX,
        offsetYPercent = digitOffsetY,
        hardEdges = digitHard
    ).normalized()

    private fun loadDigitTuning() {
        digitMode = DigitMode.values().getOrElse(studioPrefs.getInt("digit_mode", 0)) {
            DigitMode.STOCK
        }
        digitFont = DigitFont.values().getOrElse(studioPrefs.getInt("digit_font", 0)) {
            DigitFont.CONDENSED_LIGHT
        }
        digitHeight = DigitalDigitStyle.nearestHeight(studioPrefs.getInt("digit_height", 90))
        digitScale = studioPrefs.getInt("digit_scale", 80).coerceIn(40, 100)
        digitThickness = studioPrefs.getInt("digit_thickness", -1).coerceIn(-2, 6)
        digitOffsetX = studioPrefs.getInt("digit_offset_x", 0).coerceIn(-30, 30)
        digitOffsetY = studioPrefs.getInt("digit_offset_y", 0).coerceIn(-30, 30)
        digitHard = studioPrefs.getBoolean("digit_hard_edges", false)

        updatingSpinners = true
        binding.digitModeSpinner.setSelection(DigitMode.values().indexOf(digitMode), false)
        binding.digitFontSpinner.setSelection(DigitFont.values().indexOf(digitFont), false)
        binding.digitResolutionSpinner.setSelection(
            DigitalDigitStyle.HEIGHTS.indexOf(digitHeight),
            false
        )
        binding.digitScaleSlider.progress = digitScale - 40
        binding.digitThicknessSlider.progress = digitThickness + 2
        binding.digitOffsetXSlider.progress = digitOffsetX + 30
        binding.digitOffsetYSlider.progress = digitOffsetY + 30
        binding.digitHardSwitch.isChecked = digitHard
        updatingSpinners = false
        updateDigitTuningUi()
    }

    private fun persistDigitTuning() {
        studioPrefs.edit()
            .putInt("digit_mode", DigitMode.values().indexOf(digitMode))
            .putInt("digit_font", DigitFont.values().indexOf(digitFont))
            .putInt("digit_height", digitHeight)
            .putInt("digit_scale", digitScale)
            .putInt("digit_thickness", digitThickness)
            .putInt("digit_offset_x", digitOffsetX)
            .putInt("digit_offset_y", digitOffsetY)
            .putBoolean("digit_hard_edges", digitHard)
            .apply()
    }

    private fun onDigitTuningChanged() {
        persistDigitTuning()
        updateDigitTuningUi()
        updateSpeedPreview()
    }

    private fun updateDigitTuningUi() {
        binding.digitScaleLabel.text = "Digit size: $digitScale%"
        binding.digitThicknessLabel.text =
            "Thickness: ${if (digitThickness > 0) "+$digitThickness" else "$digitThickness"}"
        binding.digitOffsetXLabel.text = "X offset: $digitOffsetX%"
        binding.digitOffsetYLabel.text = "Y offset: $digitOffsetY%"
        val textMode = digitMode == DigitMode.TEXT
        binding.digitFontSpinner.isEnabled = textMode
        binding.digitFontSpinner.alpha = if (textMode) 1f else 0.4f
        binding.digitTuningNote.text = "Applied on next install: ${digitStyle().describe()}"
    }

    private fun resetDigitTuning() {
        val defaults = DigitalDigitStyle.TUNING_DEFAULT
        digitMode = defaults.mode
        digitFont = defaults.font
        digitHeight = defaults.sourceHeight
        digitScale = defaults.scalePercent
        digitThickness = defaults.thickness
        digitOffsetX = defaults.offsetXPercent
        digitOffsetY = defaults.offsetYPercent
        digitHard = defaults.hardEdges
        updatingSpinners = true
        binding.digitModeSpinner.setSelection(DigitMode.values().indexOf(digitMode), false)
        binding.digitFontSpinner.setSelection(DigitFont.values().indexOf(digitFont), false)
        binding.digitResolutionSpinner.setSelection(
            DigitalDigitStyle.HEIGHTS.indexOf(digitHeight),
            false
        )
        binding.digitScaleSlider.progress = digitScale - 40
        binding.digitThicknessSlider.progress = digitThickness + 2
        binding.digitOffsetXSlider.progress = digitOffsetX + 30
        binding.digitOffsetYSlider.progress = digitOffsetY + 30
        binding.digitHardSwitch.isChecked = digitHard
        updatingSpinners = false
        onDigitTuningChanged()
    }

    private fun digitSliderListener(
        onProgress: (Int) -> Unit,
        onFinished: () -> Unit
    ) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgress(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = onFinished()
    }

    private fun speedBackgroundLabels(unit: String): List<String> =
        listOf("Custom image", "Plain background") +
                CreationAssets.speedBackgrounds(unit).map { it.label }

    private fun rebuildUnitOptions() {
        val unit = currentUnit()
        updatingSpinners = true
        binding.maxSpeedSpinner.adapter = adapter(CreationAssets.maxSpeeds(unit).map { "$it" })
        binding.maxSpeedSpinner.setSelection(
            CreationAssets.maxSpeeds(unit).indexOf(if (unit == "mph") 125 else 200)
                .coerceAtLeast(0),
            false
        )
        binding.speedBackgroundSpinner.adapter = adapter(speedBackgroundLabels(unit))
        when {
            speedCustom -> binding.speedBackgroundSpinner.setSelection(0, false)
            speedBackgroundIncludesFace -> {
                val default = CreationAssets.speedBackgrounds(unit)
                    .indexOfFirst { it.asset == CreationAssets.defaultBackground(unit) }
                binding.speedBackgroundSpinner.setSelection(default + 2, false)
            }
            else -> binding.speedBackgroundSpinner.setSelection(1, false)
        }
        updatingSpinners = false
        updateSpeedSliderRange()
        updateMarkControls()
        when {
            speedCustom -> Unit
            speedBackgroundIncludesFace -> {
                speedBackgroundAsset = CreationAssets.defaultBackground(unit)
                loadSpeedBackgroundAsset()
            }
            else -> {
                speedBackground = null
                updateSpeedPreview()
            }
        }
    }

    private fun updateSpeedSliderRange() {
        val max = selectedMaxSpeed()
        binding.speedSlider.max = max
        binding.speedSlider.progress = max / 2
        updateSpeedLabel(max / 2)
    }

    private fun updateSpeedLabel(value: Int) {
        binding.speedValueLabel.text = "Preview speed: $value ${if (currentUnit() == "mph") "mph" else "km/h"}"
    }

    private fun updateSpeedModeVisibility() {
        val analog = binding.speedModeSpinner.selectedItemPosition == 0
        binding.needleStyleSpinner.visibility = if (analog) View.VISIBLE else View.GONE
        binding.needleColorButton.visibility = if (analog) View.VISIBLE else View.GONE
        binding.centerColorButton.visibility = if (analog) View.VISIBLE else View.GONE
        binding.digitalOptions.visibility = if (analog) View.GONE else View.VISIBLE
        binding.speedModeNote.visibility = if (analog) View.GONE else View.VISIBLE
        updateDigitTuningUi()
        // The 10-file batch was an experiment; the normal path is the v0.5.8
        // pipelined transfer with the cfg last. Keep the batch hidden.
        binding.digitalBatchButton.visibility = View.GONE
        updateSpeedInstallGate()
    }

    private fun applyClockBackgroundSelection() {
        val index = binding.clockBackgroundSpinner.selectedItemPosition
        if (index <= 0) return
        setClockBackground(CreationAssets.clockBackgrounds[index - 1])
    }

    private fun applySpeedBackgroundSelection() {
        val index = binding.speedBackgroundSpinner.selectedItemPosition
        when {
            index == 0 -> {
                backgroundTarget = BackgroundTarget.SPEED
                backgroundPicker.launch("image/*")
            }
            index == 1 -> setPlainSpeedBackground()
            index >= 2 -> {
                val option = CreationAssets.speedBackgrounds(currentUnit())
                    .getOrNull(index - 2) ?: return
                setSpeedBackground(option)
            }
        }
    }

    private fun setClockBackground(option: CreationAssets.BackgroundOption) {
        clockBackgroundAsset = option.asset
        lifecycleScope.launch {
            clockBackground = withContext(Dispatchers.IO) {
                CreationAssets.background(this@CreationStudioActivity, option.asset)
            }
            binding.clockBackgroundLabel.text = "Background: ${option.label.lowercase()}"
            updatingSpinners = true
            binding.clockBackgroundSpinner.setSelection(
                CreationAssets.clockBackgrounds.indexOf(option) + 1,
                false
            )
            updatingSpinners = false
            updateClockPreview()
        }
    }

    private fun setSpeedBackground(option: CreationAssets.BackgroundOption) {
        speedCustom = false
        speedBackgroundIncludesFace = CreationAssets.hasBuiltInFace(option.asset)
        speedBackgroundAsset = option.asset
        lifecycleScope.launch {
            speedBackground = withContext(Dispatchers.IO) {
                CreationAssets.background(this@CreationStudioActivity, option.asset)
            }
            updatingSpinners = true
            binding.speedBackgroundSpinner.setSelection(
                CreationAssets.speedBackgrounds(currentUnit()).indexOf(option) + 2,
                false
            )
            updatingSpinners = false
            updateMarkControls()
            updateSpeedPreview()
        }
    }

    private fun setPlainSpeedBackground() {
        speedCustom = false
        speedBackgroundIncludesFace = false
        speedBackgroundAsset = ""
        speedBackground = null
        updatingSpinners = true
        binding.speedBackgroundSpinner.setSelection(1, false)
        updatingSpinners = false
        updateMarkControls()
        updateSpeedPreview()
    }

    private fun loadSpeedBackgroundAsset() {
        lifecycleScope.launch {
            speedBackground = withContext(Dispatchers.IO) {
                CreationAssets.background(this@CreationStudioActivity, speedBackgroundAsset)
            }
            updateMarkControls()
            updateSpeedPreview()
        }
    }

    private fun updateMarkControls() {
        val editable = !speedBackgroundIncludesFace
        binding.markStyleSpinner.isEnabled = editable
        binding.markColorButton.isEnabled = editable
        binding.markStyleSpinner.alpha = if (editable) 1f else 0.4f
        binding.markColorButton.alpha = if (editable) 1f else 0.4f
        binding.markHint.visibility = if (editable) View.GONE else View.VISIBLE
    }

    private fun loadBackground(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    decodeSampled(uri)
                } catch (_: Exception) {
                    null
                }
            }
            if (bitmap == null) {
                Toast.makeText(this@CreationStudioActivity, "Cannot read image", Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }
            val square = squareBitmap(bitmap)
            if (square !== bitmap) bitmap.recycle()
            when (backgroundTarget) {
                BackgroundTarget.CLOCK -> {
                    clockBackground = square
                    binding.clockBackgroundLabel.text = "Background: custom image"
                    updatingSpinners = true
                    binding.clockBackgroundSpinner.setSelection(0, false)
                    updatingSpinners = false
                    updateClockPreview()
                }
                BackgroundTarget.SPEED -> {
                    speedBackground = square
                    speedCustom = true
                    speedBackgroundIncludesFace = false
                    updatingSpinners = true
                    binding.speedBackgroundSpinner.setSelection(0, false)
                    updatingSpinners = false
                    updateMarkControls()
                    updateSpeedPreview()
                }
            }
        }
    }

    private fun updateClockPreview() {
        binding.clockPreview.setClock(
            background = clockBackground,
            handStyle = binding.handStyleSpinner.selectedItemPosition + 1,
            hourColor = CreationAssets.colorFromHex(hourColor),
            minuteColor = CreationAssets.colorFromHex(minuteColor),
            secondColor = CreationAssets.colorFromHex(secondColor),
            showSecond = binding.secondHandSwitch.isChecked,
            showDate = binding.dateSwitch.isChecked
        )
    }

    private fun updateSpeedPreview() {
        val max = selectedMaxSpeed()
        val analog = binding.speedModeSpinner.selectedItemPosition == 0
        binding.speedSlider.max = max
        if (binding.speedSlider.progress > max) binding.speedSlider.progress = max
        binding.speedPreview.setSpeedometer(
            background = speedBackground,
            unit = currentUnit(),
            maxSpeed = max,
            analog = analog,
            markStyle = binding.markStyleSpinner.selectedItemPosition + 1,
            markColor = CreationAssets.colorFromHex(markColor),
            needleStyle = binding.needleStyleSpinner.selectedItemPosition + 1,
            needleColor = CreationAssets.colorFromHex(needleColor),
            centerColor = CreationAssets.colorFromHex(centerColor),
            barStyle = binding.barStyleSpinner.selectedItemPosition + 1,
            barColor = CreationAssets.colorFromHex(barColor),
            digitalColor = CreationAssets.colorFromHex(digitalColor),
            showBar = binding.showBarSwitch.isChecked,
            composeMark = !speedBackgroundIncludesFace,
            speed = binding.speedSlider.progress,
            digitStyle = digitStyle()
        )
        updateSpeedLabel(binding.speedSlider.progress)
    }

    private fun currentUnit(): String =
        if (binding.unitSpinner.selectedItemPosition == 1) "mph" else "kmh"

    private fun selectedMaxSpeed(): Int {
        val unit = currentUnit()
        val choices = CreationAssets.maxSpeeds(unit)
        val index = binding.maxSpeedSpinner.selectedItemPosition
        return choices.getOrNull(index) ?: if (unit == "mph") 125 else 200
    }

    private fun colorButtonText(label: String, value: String): String =
        "$label  ${value.uppercase()}"

    private fun pickColor(title: String, current: String, onPick: (String) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val grid = GridLayout(this).apply { columnCount = 4 }
        val hexInput = EditText(this).apply {
            setText(current)
            hint = "#RRGGBB"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        for (color in colors) {
            val swatch = View(this)
            val size = (44 * resources.displayMetrics.density).toInt()
            val margin = (4 * resources.displayMetrics.density).toInt()
            val params = GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(margin, margin, margin, margin)
            }
            swatch.layoutParams = params
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CreationAssets.colorFromHex(color))
                setStroke((1 * resources.displayMetrics.density).toInt(), Color.WHITE)
            }
            swatch.setOnClickListener { hexInput.setText(color) }
            grid.addView(swatch)
        }

        container.addView(grid)
        container.addView(
            hexInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("APPLY") { _, _ ->
                val value = hexInput.text.toString().trim()
                val clean = value.removePrefix("#")
                if (!clean.matches(Regex("[0-9a-fA-F]{6}"))) {
                    Toast.makeText(this, "Use #RRGGBB", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onPick("#" + clean.lowercase())
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun squareBitmap(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        val cropped = Bitmap.createBitmap(source, x, y, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, 480, 480, true)
        if (cropped !== scaled) cropped.recycle()
        return scaled
    }

    private fun confirmMinimalStockInstall() {
        if (!controller.state.value.connected) {
            Toast.makeText(this, "Connect to the scooter first", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Install minimal stock clock?")
            .setMessage(
                "Uploads the official clock dial and preset hands only, without the date images. " +
                    "This is the smallest safe transfer test (2 files). Keep the scooter key on."
            )
            .setPositiveButton("INSTALL") { _, _ -> installClock(stock = true, minimal = true) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun confirmClockInstall(stock: Boolean) {
        if (!controller.state.value.connected) {
            Toast.makeText(this, "Connect to the scooter first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!stock && !stockVerified) {
            Toast.makeText(this, "Run the stock clock test first", Toast.LENGTH_SHORT).show()
            return
        }
        val message = if (stock) {
            "Uploads the exact official clock template, including all seven weekday images (9 files total). " +
                "The old clock slot is cleared first, including files left by failed attempts. " +
                "The transfer starts a group of files, streams their data and pipelines the " +
                "finishes, so the whole bundle reaches the dashboard before its ten-second " +
                "transfer window closes. Keep the scooter key on and stay on the clock screen."
        } else {
            "Builds and uploads your custom clock using the official assets. The weekday is now " +
                "rendered from the seven official images, so it stays correct every day. " +
                "A dated clock also clears the old clock slot first. Keep the scooter key on and " +
                "stay on the clock screen."
        }
        AlertDialog.Builder(this)
            .setTitle(if (stock) "Install stock clock?" else "Install custom clock?")
            .setMessage(message)
            .setPositiveButton("INSTALL") { _, _ -> installClock(stock) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun confirmSpeedInstall() {
        if (!controller.state.value.connected) {
            Toast.makeText(this, "Connect to the scooter first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!stockVerified) {
            Toast.makeText(this, "Run the stock clock test first", Toast.LENGTH_SHORT).show()
            return
        }
        val digital = binding.speedModeSpinner.selectedItemPosition != 0
        AlertDialog.Builder(this)
            .setTitle(if (digital) "Install digital speedometer?" else "Install speedometer?")
            .setMessage(
                (if (digital) {
                    "Builds the official 12-file digital bundle (background, ten digit glyphs and cfg). " +
                        "The old speedometer slot is cleared first. "
                } else {
                    "Builds the official analog speedometer bundle. "
                }) +
                    "Images are sent before the cfg, matching the official app, and the " +
                    "finishes are pipelined so the bundle reaches the dashboard before its " +
                    "ten-second transfer window closes. The clock test must already have succeeded. " +
                    "Keep the key on and stay on the dashboard. If it fails, share the new log; " +
                    "it will show the exact step."
            )
            .setPositiveButton("INSTALL") { _, _ -> installSpeedometer() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun installClock(stock: Boolean, minimal: Boolean = false) {
        val label = when {
            minimal -> "stock clock minimal test"
            stock -> "stock clock test"
            else -> "custom clock"
        }
        beginInstall(label)
        binding.studioStatus.text = "Building clock bundle…"
        lifecycleScope.launch {
            val bundle = try {
                withContext(Dispatchers.IO) {
                    if (minimal) {
                        ClockCreationBuilder.buildMinimalStock(this@CreationStudioActivity)
                    } else if (stock) {
                        ClockCreationBuilder.buildStock(this@CreationStudioActivity)
                    } else {
                        ClockCreationBuilder.buildCustom(
                            this@CreationStudioActivity,
                            ClockCreationBuilder.Options(
                                background = clockBackground,
                                handType = binding.handStyleSpinner.selectedItemPosition + 1,
                                hourColor = hourColor,
                                minuteColor = minuteColor,
                                secondColor = secondColor,
                                showSecond = binding.secondHandSwitch.isChecked,
                                showDate = binding.dateSwitch.isChecked
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                binding.studioStatus.text = "Build failed: ${e.message ?: "unknown"}"
                endInstall()
                return@launch
            }
            binding.studioStatus.text =
                "Starting upload (${bundle.files.size} files, pipelined)…"
            controller.installCreation(bundle, label)
        }
    }

    private fun confirmDigitalBatchInstall() {
        if (!controller.state.value.connected) {
            Toast.makeText(this, "Connect to the scooter first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!stockVerified) {
            Toast.makeText(this, "Run the stock clock test first", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Digital speedometer fallback?")
            .setMessage(
                "Use this only for diagnosis if the normal INSTALL SPEEDOMETER " +
                    "(DIGITAL) fails. The normal pipelined install is the default path; " +
                    "this older batch path opens up to ten files and waits for every " +
                    "finish reply, which is what hits the dashboard's ten-second window."
            )
            .setPositiveButton("USE FALLBACK") { _, _ -> installDigitalBatched() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun installDigitalBatched() {
        val label = "digital speedometer batched"
        beginInstall(label)
        binding.studioStatus.text = "Building digital speedometer bundle…"
        lifecycleScope.launch {
            val bundle = try {
                withContext(Dispatchers.IO) {
                    SpeedometerCreationBuilder.build(
                        this@CreationStudioActivity,
                        SpeedometerCreationBuilder.Options(
                            background = speedBackground,
                            unit = currentUnit(),
                            maxSpeed = selectedMaxSpeed(),
                            analog = false,
                            markStyle = binding.markStyleSpinner.selectedItemPosition + 1,
                            markColor = markColor,
                            needleStyle = binding.needleStyleSpinner.selectedItemPosition + 1,
                            needleColor = needleColor,
                            centerColor = centerColor,
                            digitalColor = digitalColor,
                            barStyle = binding.barStyleSpinner.selectedItemPosition + 1,
                            barColor = barColor,
                            showBar = binding.showBarSwitch.isChecked,
                            composeMark = !speedBackgroundIncludesFace,
                            digitStyle = digitStyle()
                        )
                    )
                }
            } catch (e: Exception) {
                binding.studioStatus.text = "Digital build failed: ${e.message ?: "unknown"}"
                endInstall()
                return@launch
            }
            binding.studioStatus.text = "Starting old batched digital upload (diagnostic only)…"
            controller.installCreationBatched(bundle, label)
        }
    }

    private fun installSpeedometer() {
        val analog = binding.speedModeSpinner.selectedItemPosition == 0
        val label = if (analog) "speedometer analog" else "speedometer digital"
        beginInstall(label)
        binding.studioStatus.text = "Building speedometer bundle…"
        lifecycleScope.launch {
            val bundle = try {
                withContext(Dispatchers.IO) {
                    SpeedometerCreationBuilder.build(
                        this@CreationStudioActivity,
                        SpeedometerCreationBuilder.Options(
                            background = speedBackground,
                            unit = currentUnit(),
                            maxSpeed = selectedMaxSpeed(),
                            analog = analog,
                            markStyle = binding.markStyleSpinner.selectedItemPosition + 1,
                            markColor = markColor,
                            needleStyle = binding.needleStyleSpinner.selectedItemPosition + 1,
                            needleColor = needleColor,
                            centerColor = centerColor,
                            digitalColor = digitalColor,
                            barStyle = binding.barStyleSpinner.selectedItemPosition + 1,
                            barColor = barColor,
                            showBar = binding.showBarSwitch.isChecked,
                            composeMark = !speedBackgroundIncludesFace,
                            digitStyle = digitStyle()
                        )
                    )
                }
            } catch (e: Exception) {
                binding.studioStatus.text = "Speedometer build failed: ${e.message ?: "unknown"}"
                endInstall()
                return@launch
            }
            binding.studioStatus.text =
                "Starting speedometer upload (${bundle.files.size} files, pipelined)…"
            controller.installCreation(bundle, label)
        }
    }

    private fun adapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.spinner_item, items).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    private fun simpleListener(onSelected: () -> Unit) =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = onSelected()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

    override fun onDestroy() {
        clockBackground?.recycle()
        clockBackground = null
        speedBackground?.recycle()
        speedBackground = null
        super.onDestroy()
    }
}
