package com.noodoe.offline.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noodoe.offline.NoodoeApp
import com.noodoe.offline.notification.NoodoeService
import com.noodoe.offline.protocol.DeviceInfo
import com.noodoe.offline.protocol.RidingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

data class UiState(
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val bonded: Boolean = false,
    val connected: Boolean = false,
    val deviceInfo: DeviceInfo? = null,
    val status: RidingStatus? = null,
    val busy: Boolean = false,
    val statusMessage: String = "Disconnected",
    val lastSync: String? = null,
    val lastStatusAt: String? = null,
    val mobileStatusText: String = "",
    val logPath: String? = null,
    val logText: String = "",
    val timeFormat: Int = 1,
    val showLiveLogs: Boolean = true,
    val autoSyncClock: Boolean = false,
    val distanceUnit: Int = 0,
    val temperatureUnit: Int = 0,
    val brightness: Int = 50,
    val language: Int = 255,
    val notifyApps: Boolean = false,
    val notifyCalls: Boolean = false,
    val notifySms: Boolean = false,
    val notifyOnlyKeyOn: Boolean = false,
    val keepAlive: Boolean = false
)

@SuppressLint("MissingPermission")
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = (application as NoodoeApp).controller
    private val settings = controller.settings

    private val _state = MutableStateFlow(
        UiState(
            logPath = controller.currentLogFile?.absolutePath,
            timeFormat = settings.timeFormat,
            showLiveLogs = settings.showLiveLogs,
            autoSyncClock = settings.autoSyncClock,
            distanceUnit = settings.distanceUnit,
            temperatureUnit = settings.temperatureUnit,
            brightness = settings.brightness,
            language = settings.language,
            notifyApps = settings.notifyApps,
            notifyCalls = settings.notifyCalls,
            notifySms = settings.notifySms,
            notifyOnlyKeyOn = settings.notifyOnlyKeyOn,
            keepAlive = settings.keepAlive,
            lastSync = settings.lastClockText
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    val currentLogFile: File?
        get() = controller.currentLogFile

    init {
        viewModelScope.launch {
            controller.state.collect { link ->
                _state.update {
                    it.copy(
                        deviceName = link.deviceName,
                        deviceAddress = link.deviceAddress,
                        bonded = link.bonded,
                        connected = link.connected,
                        deviceInfo = link.deviceInfo,
                        status = link.status,
                        busy = link.busy,
                        statusMessage = link.statusMessage,
                        lastSync = link.lastSync,
                        lastStatusAt = link.lastStatusAt,
                        mobileStatusText = link.mobileStatusText,
                        logPath = link.logPath,
                        logText = link.logText
                    )
                }
                if (link.connected) updateBackgroundService()
            }
        }
    }

    fun setTarget(device: BluetoothDevice) = controller.setTarget(device)

    fun clearTarget() = controller.clearTarget()

    fun connect(device: BluetoothDevice) = controller.connect(device)

    fun disconnect() {
        stopBackgroundService()
        controller.disconnect()
    }

    fun syncClockNow() = controller.syncClockNow()

    fun setCustomClock(calendar: Calendar) = controller.setCustomClock(calendar)

    fun refreshStatus() = controller.refreshStatus()

    fun setTimeFormat(format: Int) {
        if (settings.timeFormat == format) return
        settings.timeFormat = format
        _state.update { it.copy(timeFormat = format) }
        controller.log("time format saved: ${if (format == 0) "12h" else "24h"}")
        controller.applyPreferences("time format")
    }

    fun setDistanceUnit(value: Int) {
        if (settings.distanceUnit == value) return
        settings.distanceUnit = value
        _state.update { it.copy(distanceUnit = value) }
        controller.applyPreferences("distance unit")
    }

    fun setTemperatureUnit(value: Int) {
        if (settings.temperatureUnit == value) return
        settings.temperatureUnit = value
        _state.update { it.copy(temperatureUnit = value) }
        controller.applyPreferences("temperature unit")
    }

    fun setBrightness(value: Int) {
        val clamped = value.coerceIn(0, 100)
        if (settings.brightness == clamped) return
        settings.brightness = clamped
        _state.update { it.copy(brightness = clamped) }
        controller.applyPreferences("brightness")
    }

    fun setLanguage(value: Int) {
        if (settings.language == value) return
        settings.language = value
        _state.update { it.copy(language = value) }
        controller.applyPreferences("language")
    }

    fun setAutoSyncClock(enabled: Boolean) {
        settings.autoSyncClock = enabled
        _state.update { it.copy(autoSyncClock = enabled) }
        controller.log("clock auto-sync on connect: ${if (enabled) "on" else "off"}")
    }

    fun setShowLiveLogs(enabled: Boolean) {
        settings.showLiveLogs = enabled
        _state.update { it.copy(showLiveLogs = enabled) }
    }

    fun setNotifyApps(enabled: Boolean) {
        settings.notifyApps = enabled
        if (enabled) setKeepAlive(true)
        _state.update { it.copy(notifyApps = enabled) }
        controller.log("app notifications: ${if (enabled) "on" else "off"}")
    }

    fun setNotifyCalls(enabled: Boolean) {
        settings.notifyCalls = enabled
        if (enabled) setKeepAlive(true)
        _state.update { it.copy(notifyCalls = enabled) }
        controller.log("call notifications: ${if (enabled) "on" else "off"}")
    }

    fun setNotifySms(enabled: Boolean) {
        settings.notifySms = enabled
        if (enabled) setKeepAlive(true)
        _state.update { it.copy(notifySms = enabled) }
        controller.log("SMS notifications: ${if (enabled) "on" else "off"}")
    }

    fun setNotifyOnlyKeyOn(enabled: Boolean) {
        settings.notifyOnlyKeyOn = enabled
        _state.update { it.copy(notifyOnlyKeyOn = enabled) }
        controller.log("notify only while key on: ${if (enabled) "on" else "off"}")
    }

    fun setKeepAlive(enabled: Boolean) {
        val changed = settings.keepAlive != enabled
        settings.keepAlive = enabled
        if (changed) {
            _state.update { it.copy(keepAlive = enabled) }
            controller.log("background connection: ${if (enabled) "on" else "off"}")
        }
        if (enabled) {
            updateBackgroundService()
        } else {
            stopBackgroundService()
        }
    }

    fun sendTestNotification() = controller.sendTestNotification()

    fun sendMobileStatusTest() = controller.sendMobileStatusTest()

    fun sendNotificationIdTests() = controller.sendNotificationIdTests()

    fun sendCallSmsTest() = controller.sendCallSmsTest()

    fun newLog() = controller.newLog()

    fun clearLogs() = controller.clearLogs()

    fun shareIntent() = controller.shareIntent()

    fun shareAllLogsIntent(): Intent? = controller.shareAllLogsIntent()

    override fun onCleared() {
        if (!settings.keepAlive) {
            stopBackgroundService()
            controller.disconnect()
        }
        super.onCleared()
    }

    private fun updateBackgroundService() {
        if (!settings.keepAlive || !_state.value.connected || NoodoeService.isRunning) return
        val context = getApplication<NoodoeApp>()
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NoodoeService::class.java)
            )
        } catch (e: Exception) {
            controller.log("background service failed: ${e.javaClass.simpleName}")
        }
    }

    private fun stopBackgroundService() {
        if (!NoodoeService.isRunning) return
        val context = getApplication<NoodoeApp>()
        context.stopService(Intent(context, NoodoeService::class.java))
    }
}
