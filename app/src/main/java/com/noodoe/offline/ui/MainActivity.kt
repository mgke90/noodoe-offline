package com.noodoe.offline.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.noodoe.offline.R
import com.noodoe.offline.creation.CreationStudioActivity
import com.noodoe.offline.databinding.ActivityMainBinding
import com.noodoe.offline.notification.PhoneStateWatcher
import com.noodoe.offline.protocol.DeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var target: BluetoothDevice? = null
    private var receiverRegistered = false
    private var lastRenderedLanguage = Int.MIN_VALUE
    private var lastBusy = false

    private val languages = listOf(
        255 to "Keep current",
        1 to "English",
        2 to "Chinese (Traditional)",
        3 to "Chinese (Simplified)",
        4 to "German",
        5 to "Spanish",
        6 to "French",
        7 to "Italian",
        8 to "Greek",
        9 to "Japanese",
        10 to "Portuguese",
        11 to "Dutch",
        12 to "Korean",
        13 to "Vietnamese",
        14 to "Turkish",
        15 to "Polish",
        16 to "Hebrew",
        17 to "Indonesian"
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermissions()) {
                refreshBondedDevice()
            } else {
                binding.connectionState.text = "Bluetooth permissions are required"
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            PhoneStateWatcher.update(this)
            updateNotificationSettingsUi()
        }

    private val btReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                        ?: return
                    val name = device.name ?: return
                    if (target == null &&
                        (name.startsWith("KYMCO Noodoe") || name.startsWith("Sunray"))
                    ) {
                        target = device
                        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                        viewModel.setTarget(device)
                        binding.connectionState.text = "Pairing…"
                        if (!device.createBond()) {
                            binding.connectionState.text = "Could not start pairing"
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (target == null) {
                        binding.connectionState.text = "No KYMCO Noodoe found"
                    }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                        ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    val name = device.name ?: ""
                    val isNoodoe = name.startsWith("KYMCO Noodoe") || name.startsWith("Sunray")
                    when (state) {
                        BluetoothDevice.BOND_BONDED -> {
                            // Do not rely on `target`; it may have been cleared
                            // by onResume while the system pairing dialog was open.
                            val bonded = findBondedScooter()
                            if (bonded != null) {
                                target = bonded
                                viewModel.setTarget(bonded)
                                viewModel.connect(bonded)
                            }
                        }

                        BluetoothDevice.BOND_BONDING -> {
                            if (isNoodoe || target?.address == device.address) {
                                binding.connectionState.text = "Pairing…"
                            }
                        }

                        BluetoothDevice.BOND_NONE -> {
                            if (isNoodoe || target?.address == device.address) {
                                binding.connectionState.text = "Pairing failed"
                                refreshBondedDevice()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { view, insets ->
            view.updatePadding(
                top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            )
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { view, insets ->
            view.updatePadding(
                bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            )
            insets
        }
        binding.logScroll.isNestedScrollingEnabled = true
        // Keep vertical gestures inside the log area from being stolen by the
        // outer page scroll. Starting a gesture on the page still scrolls it.
        binding.logScroll.setOnTouchListener { view, _ ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        binding.footer.text = "Offline only — no account, cloud, maps or OTA.  v" + versionName()

        setupListeners()
        updateNotificationSettingsUi()
        registerBtReceiver()

        if (hasPermissions()) {
            refreshBondedDevice()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) {
            refreshBondedDevice()
        }
        updateNotificationSettingsUi()
        PhoneStateWatcher.update(this)
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(btReceiver)
            receiverRegistered = false
        }
        if (isFinishing && !viewModel.state.value.keepAlive) {
            viewModel.disconnect()
        }
        super.onDestroy()
    }

    private fun setupListeners() {
        val languageAdapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            languages.map { it.second }
        )
        languageAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.languageSpinner.adapter = languageAdapter
        val initialLanguage = languages
            .indexOfFirst { it.first == viewModel.state.value.language }
            .coerceAtLeast(0)
        binding.languageSpinner.setSelection(initialLanguage, false)
        binding.languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val value = languages[position].first
                if (value != viewModel.state.value.language) {
                    viewModel.setLanguage(value)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.brightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.brightnessValue.text = "${value.toInt()}%"
            }
        }
        binding.brightnessSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                viewModel.setBrightness(slider.value.toInt())
            }
        })

        binding.openStudioButton.setOnClickListener {
            startActivity(Intent(this, CreationStudioActivity::class.java))
        }
        binding.scanButton.setOnClickListener { scanAndPair() }
        binding.connectButton.setOnClickListener {
            if (viewModel.state.value.connected) {
                viewModel.disconnect()
            } else {
                target?.let { viewModel.connect(it) }
            }
        }
        binding.syncNowButton.setOnClickListener { viewModel.syncClockNow() }
        binding.setCustomButton.setOnClickListener { showDateTimePicker() }
        binding.refreshStatusButton.setOnClickListener { viewModel.refreshStatus() }
        binding.timeFormatSwitch.setOnCheckedChangeListener { _, checked ->
            val format = if (checked) 1 else 0
            if (format != viewModel.state.value.timeFormat) {
                viewModel.setTimeFormat(format)
            }
        }
        binding.liveLogSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != viewModel.state.value.showLiveLogs) {
                viewModel.setShowLiveLogs(checked)
            }
        }
        binding.autoSyncSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != viewModel.state.value.autoSyncClock) {
                viewModel.setAutoSyncClock(checked)
            }
        }
        binding.distanceMilesSwitch.setOnCheckedChangeListener { _, checked ->
            val unit = if (checked) 1 else 0
            if (unit != viewModel.state.value.distanceUnit) {
                viewModel.setDistanceUnit(unit)
            }
        }
        binding.temperatureFahrenheitSwitch.setOnCheckedChangeListener { _, checked ->
            val unit = if (checked) 1 else 0
            if (unit != viewModel.state.value.temperatureUnit) {
                viewModel.setTemperatureUnit(unit)
            }
        }
        binding.keepAliveSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != viewModel.state.value.keepAlive) {
                viewModel.setKeepAlive(checked)
                if (checked) requestNotificationPermissions(false, false)
            }
        }
        binding.appNotificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != viewModel.state.value.notifyApps) {
                if (checked) requestNotificationPermissions(false, false)
                viewModel.setNotifyApps(checked)
            }
        }
        binding.callNotificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != viewModel.state.value.notifyCalls) {
                viewModel.setNotifyCalls(checked)
                if (checked) requestNotificationPermissions(true, false)
                PhoneStateWatcher.update(this)
            }
        }
        binding.smsNotificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != viewModel.state.value.notifySms) {
                viewModel.setNotifySms(checked)
                if (checked) requestNotificationPermissions(false, true)
            }
        }
        binding.notifyOnlyKeyOnSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != viewModel.state.value.notifyOnlyKeyOn) {
                viewModel.setNotifyOnlyKeyOn(checked)
            }
        }
        binding.notificationAccessButton.setOnClickListener { openNotificationAccess() }
        binding.notificationPermissionButton.setOnClickListener {
            requestNotificationPermissions(true, true)
        }
        binding.testNotificationButton.setOnClickListener {
            viewModel.sendTestNotification()
        }
        binding.mobileStatusTestButton.setOnClickListener {
            viewModel.sendMobileStatusTest()
        }
        binding.notificationIdsTestButton.setOnClickListener {
            viewModel.sendNotificationIdTests()
        }
        binding.callSmsTestButton.setOnClickListener {
            viewModel.sendCallSmsTest()
        }
        binding.newLogButton.setOnClickListener { viewModel.newLog() }
        binding.shareLogButton.setOnClickListener { shareLog() }
        binding.shareAllLogsButton.setOnClickListener { shareAllLogs() }
        binding.clearLogButton.setOnClickListener { confirmClearLogs() }
    }

    private fun registerBtReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            btReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        return permissions.toTypedArray()
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(Manifest.permission.BLUETOOTH_SCAN) &&
                    isGranted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun isGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun hasCallPermission(): Boolean =
        isGranted(Manifest.permission.READ_PHONE_STATE)

    private fun hasSmsPermission(): Boolean =
        isGranted(Manifest.permission.RECEIVE_SMS)

    private fun openNotificationAccess() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Notification access settings unavailable", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun requestNotificationPermissions(calls: Boolean, sms: Boolean) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isGranted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (calls && !hasCallPermission()) {
            permissions += Manifest.permission.READ_PHONE_STATE
        }
        if (sms && !hasSmsPermission()) {
            permissions += Manifest.permission.RECEIVE_SMS
        }
        if (permissions.isEmpty()) {
            PhoneStateWatcher.update(this)
            updateNotificationSettingsUi()
        } else {
            notificationPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun updateNotificationSettingsUi() {
        val access = hasNotificationAccess()
        binding.notificationAccessStatus.text =
            "Notification access: ${if (access) "granted" else "not granted"}"

        val phone = hasCallPermission()
        val sms = hasSmsPermission()
        binding.notificationPermissionStatus.text = when {
            phone && sms -> "Phone/SMS permissions: granted"
            !phone && !sms -> "Phone/SMS permissions: not granted"
            phone -> "Phone permission granted; SMS not granted"
            else -> "SMS permission granted; phone not granted"
        }
        binding.notificationPermissionButton.isEnabled = !(phone && sms)
    }

    @SuppressLint("MissingPermission")
    private fun findBondedScooter(): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        return adapter.bondedDevices.firstOrNull {
            val name = it.name
            name != null && (name.startsWith("KYMCO Noodoe") || name.startsWith("Sunray"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBondedDevice() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            binding.deviceName.text = "No Bluetooth adapter"
            binding.connectButton.isEnabled = false
            return
        }
        if (!adapter.isEnabled) {
            binding.deviceName.text = "Bluetooth is off"
            binding.connectButton.isEnabled = false
            return
        }

        val device = findBondedScooter()
        target = device
        if (device != null) {
            viewModel.setTarget(device)
            if (!viewModel.state.value.connected && !viewModel.state.value.busy) {
                viewModel.connect(device)
            }
        } else {
            viewModel.clearTarget()
            binding.connectionState.text = "No bonded Noodoe — tap SCAN & PAIR"
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanAndPair() {
        if (!hasPermissions()) {
            permissionLauncher.launch(requiredPermissions())
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) {
            binding.connectionState.text = "Bluetooth is off"
            return
        }

        val bonded = findBondedScooter()
        if (bonded != null) {
            target = bonded
            viewModel.setTarget(bonded)
            viewModel.connect(bonded)
            return
        }

        target = null
        viewModel.clearTarget()
        adapter.cancelDiscovery()
        val started = adapter.startDiscovery()
        binding.connectionState.text =
            if (started) "Scanning… put the dashboard in pairing mode" else "Scan failed"
    }

    private fun showDateTimePicker() {
        val now = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        val calendar = Calendar.getInstance()
                        calendar.set(year, month, day, hour, minute, 0)
                        viewModel.setCustomClock(calendar)
                    },
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    true
                ).show()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun shareLog() {
        val intent = viewModel.shareIntent()
        if (intent == null) {
            Toast.makeText(this, "No log file to share", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent.createChooser(intent, "Share log"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app available to share the log", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareAllLogs() {
        lifecycleScope.launch {
            val intent = withContext(Dispatchers.IO) { viewModel.shareAllLogsIntent() }
            if (intent == null) {
                Toast.makeText(this@MainActivity, "No logs to zip", Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                startActivity(Intent.createChooser(intent, "Share all logs"))
            } catch (_: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "No app available to share the ZIP",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle("Delete log files?")
            .setMessage(
                "Only noodoe-*.txt / .log / .zip files in the app's own log folder " +
                        "will be deleted."
            )
            .setPositiveButton("DELETE") { _, _ -> viewModel.clearLogs() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun render(state: UiState) {
        val keepScrollY = binding.scroll.scrollY

        fun actionState(button: MaterialButton, available: Boolean) {
            button.isEnabled = available
            button.isClickable = available && !state.busy
            button.alpha = if (available && !state.busy) 1f else 0.55f
        }

        binding.deviceName.text = state.deviceName ?: "No scooter selected"
        binding.deviceAddress.text = state.deviceAddress ?: ""
        binding.connectionState.text = state.statusMessage
        binding.connectButton.text = if (state.connected) "DISCONNECT" else "CONNECT"
        actionState(binding.connectButton, target != null)
        actionState(binding.scanButton, true)

        val modern = state.deviceInfo?.deviceType == DeviceType.VERSION_1_5 ||
                state.deviceInfo?.deviceType == DeviceType.VERSION_2_0
        val commandAvailable = state.connected && modern
        actionState(binding.syncNowButton, commandAvailable)
        actionState(binding.setCustomButton, commandAvailable)
        actionState(binding.refreshStatusButton, commandAvailable)
        binding.timeFormatSwitch.isEnabled = true

        binding.clockValue.text = state.lastSync ?: "--"
        binding.lastSyncText.text = if (state.lastSync != null) {
            "Last sync: ${state.lastSync}"
        } else {
            "Not synced yet"
        }

        val miles = state.distanceUnit == 1
        val speedUnit = if (miles) "mph" else "km/h"
        val distanceUnit = if (miles) "mi" else "km"
        val status = state.status
        if (status != null) {
            val speed = if (miles) status.currentSpeedKph * 0.621371 else status.currentSpeedKph.toDouble()
            val odo = if (miles) status.odoKm * 0.621371 else status.odoKm
            val max = if (miles) status.maxSpeedKph * 0.621371 else status.maxSpeedKph.toDouble()
            binding.statusLine.text = String.format(
                Locale.US,
                "%d %s   |   ODO %.0f %s",
                Math.round(speed),
                speedUnit,
                odo,
                distanceUnit
            )
            val maxText = if (status.keyOn && status.currentSpeedKph > 0) {
                "${Math.round(max)} $speedUnit"
            } else {
                "--"
            }
            binding.statusDetail.text = String.format(
                Locale.US,
                "Dash key %s   |   Max %s   |   FW %s",
                if (status.keyOn) "ON" else "OFF",
                maxText,
                state.deviceInfo?.firmware ?: "--"
            )
            binding.statusUpdated.text = "Updated: ${state.lastStatusAt ?: "--"}"
        } else {
            binding.statusLine.text = "Speed -- $speedUnit   |   ODO -- $distanceUnit"
            binding.statusDetail.text =
                "Dash key --   |   Max --   |   FW ${state.deviceInfo?.firmware ?: "--"}"
            binding.statusUpdated.text = "No status yet"
        }

        val want24h = state.timeFormat == 1
        if (binding.timeFormatSwitch.isChecked != want24h) {
            binding.timeFormatSwitch.isChecked = want24h
        }
        if (binding.autoSyncSwitch.isChecked != state.autoSyncClock) {
            binding.autoSyncSwitch.isChecked = state.autoSyncClock
        }
        if (binding.keepAliveSwitch.isChecked != state.keepAlive) {
            binding.keepAliveSwitch.isChecked = state.keepAlive
        }
        if (binding.appNotificationSwitch.isChecked != state.notifyApps) {
            binding.appNotificationSwitch.isChecked = state.notifyApps
        }
        if (binding.callNotificationSwitch.isChecked != state.notifyCalls) {
            binding.callNotificationSwitch.isChecked = state.notifyCalls
        }
        if (binding.smsNotificationSwitch.isChecked != state.notifySms) {
            binding.smsNotificationSwitch.isChecked = state.notifySms
        }
        if (binding.notifyOnlyKeyOnSwitch.isChecked != state.notifyOnlyKeyOn) {
            binding.notifyOnlyKeyOnSwitch.isChecked = state.notifyOnlyKeyOn
        }
        binding.mobileStatusResult.text = if (state.mobileStatusText.isNotBlank()) {
            "Phone status: ${state.mobileStatusText}"
        } else {
            "GPS: not tested yet"
        }
        val wantMiles = state.distanceUnit == 1
        if (binding.distanceMilesSwitch.isChecked != wantMiles) {
            binding.distanceMilesSwitch.isChecked = wantMiles
        }
        val wantFahrenheit = state.temperatureUnit == 1
        if (binding.temperatureFahrenheitSwitch.isChecked != wantFahrenheit) {
            binding.temperatureFahrenheitSwitch.isChecked = wantFahrenheit
        }
        if (binding.brightnessSlider.value != state.brightness.toFloat()) {
            binding.brightnessSlider.value = state.brightness.toFloat()
        }
        binding.brightnessValue.text = "${state.brightness}%"
        if (lastRenderedLanguage != state.language) {
            lastRenderedLanguage = state.language
            val languageIndex = languages.indexOfFirst { it.first == state.language }
                .coerceAtLeast(0)
            if (binding.languageSpinner.selectedItemPosition != languageIndex) {
                binding.languageSpinner.setSelection(languageIndex, false)
            }
        }

        if (binding.liveLogSwitch.isChecked != state.showLiveLogs) {
            binding.liveLogSwitch.isChecked = state.showLiveLogs
        }
        val wasVisible = binding.logScroll.visibility == View.VISIBLE
        binding.logScroll.visibility = if (state.showLiveLogs) View.VISIBLE else View.GONE

        binding.logPath.text = state.logPath ?: "Log unavailable"
        if (binding.logText.text.toString() != state.logText) {
            val stickToBottom = !binding.logScroll.canScrollVertically(1)
            binding.logText.text = state.logText
            if (stickToBottom || !wasVisible) {
                binding.logScroll.doOnNextLayout {
                    binding.logScroll.scrollTo(0, binding.logText.bottom)
                }
            }
        }

        val busyChanged = state.busy != lastBusy
        lastBusy = state.busy
        if (busyChanged && !binding.scroll.isPressed) {
            binding.scroll.post {
                if (!binding.scroll.isPressed) {
                    binding.scroll.scrollTo(0, keepScrollY)
                }
            }
        }
    }
}
