package com.noodoe.offline.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.noodoe.offline.bluetooth.NoodoeSession
import com.noodoe.offline.creation.CreationBundle
import com.noodoe.offline.creation.CreationFile
import com.noodoe.offline.creation.CreationTransport
import com.noodoe.offline.creation.CreationTransferEngine
import com.noodoe.offline.protocol.DeviceInfo
import com.noodoe.offline.protocol.DeviceType
import com.noodoe.offline.protocol.RidingStatus
import com.noodoe.offline.protocol.SunrayProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class LinkState(
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
    val creationStatus: String = "",
    val logPath: String? = null,
    val logText: String = ""
)

/**
 * App-scoped Bluetooth link. It outlives the Activity so the foreground
 * service and notification listeners can use the same SPP session.
 */
@SuppressLint("MissingPermission")
class NoodoeController(context: Context) {

    val settings = SettingsStore(context)
    private val appContext = context.applicationContext
    private val logStore = LogStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        LinkState(
            deviceName = settings.lastDeviceName,
            deviceAddress = settings.lastDeviceAddress,
            logPath = logStore.currentFile?.absolutePath,
            lastSync = settings.lastClockText
        )
    )
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private var session: NoodoeSession? = null
    private var targetDevice: BluetoothDevice? = null
    private var lastTargetAddress: String? = null
    private var lastIoAt = 0L
    private var transferTaskId = 1
    private var keepaliveJob: Job? = null
    private var keepaliveFailures = 0

    val currentLogFile: File?
        get() = logStore.currentFile

    init {
        log("Noodoe Offline started")
    }

    fun setTarget(device: BluetoothDevice) {
        targetDevice = device
        val name = device.name ?: "KYMCO Noodoe"
        settings.lastDeviceAddress = device.address
        settings.lastDeviceName = name
        _state.update {
            it.copy(
                deviceName = name,
                deviceAddress = device.address,
                bonded = device.bondState == BluetoothDevice.BOND_BONDED
            )
        }
        if (lastTargetAddress != device.address) {
            lastTargetAddress = device.address
            log("target: $name  ${device.address}")
        }
    }

    fun clearTarget() {
        targetDevice = null
        _state.update {
            it.copy(deviceName = null, deviceAddress = null, bonded = false)
        }
    }

    fun connect(device: BluetoothDevice) {
        if (_state.value.connected || _state.value.busy) return
        setTarget(device)
        scope.launch {
            launchAction("Connecting…") {
                try {
                    val info = openSession(device)
                    log("device: fw ${info.firmware}, protocol ${info.protocol}, " +
                            "hw ${info.hardwareVersion}, mac ${info.mac}")
                    if (info.deviceType == DeviceType.VERSION_1_0 ||
                        info.deviceType == DeviceType.INVALID
                    ) {
                        _state.update {
                            it.copy(
                                connected = true,
                                deviceInfo = info,
                                statusMessage = if (info.deviceType == DeviceType.VERSION_1_0)
                                    "Legacy 1.0 — limited support"
                                else "Unsupported protocol",
                                lastSync = null,
                                lastStatusAt = null
                            )
                        }
                        log("device type ${info.deviceType}: modern commands disabled")
                        return@launchAction
                    }

                    val clock = chooseClockForConnect()
                    val mobileStatus = buildMobileStatus(clock.calendar)
                    val mobileText = mobileStatusDescription()
                    val commands = mutableListOf<ByteArray>()
                    commands += SunrayProtocol.preferenceCommand(
                        settings.distanceUnit,
                        settings.temperatureUnit,
                        settings.timeFormat,
                        settings.brightness,
                        settings.language
                    )
                    commands += mobileStatus
                    commands += SunrayProtocol.ridingStatusCommand()
                    val reply = sendWithReconnectLocked(commands, 3500)
                    val status = SunrayProtocol.parseRidingStatus(reply)

                    settings.lastClockText = clock.text
                    settings.lastClockWasCustom = clock.custom
                    settings.lastClockSetAt = System.currentTimeMillis()
                    _state.update {
                        it.copy(
                            connected = true,
                            deviceInfo = info,
                            status = status,
                            lastSync = clock.text,
                            lastStatusAt = clockText(Calendar.getInstance()),
                            mobileStatusText = mobileText,
                            statusMessage = "Connected"
                        )
                    }
                    log("initial sync done")
                    log("${clock.label}: ${clock.text}")
                    val mobileReply = SunrayProtocol.replyStatus(
                        reply,
                        SunrayProtocol.CMD_MOBILE_STATUS
                    )
                    when (mobileReply) {
                        null -> log("mobile status: $mobileText (ack only)")
                        0 -> log("mobile status: $mobileText")
                        else -> log(
                            "mobile status rejected: status $mobileReply ($mobileText)"
                        )
                    }
                    if (status != null) {
                        log("status: ${statusSummary(status)}")
                    } else {
                        log("status: no RIDING_STATUS reply")
                    }
                } catch (e: Exception) {
                    session?.close()
                    session = null
                    _state.update {
                        it.copy(connected = false, deviceInfo = null, status = null)
                    }
                    throw e
                }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            mutex.withLock {
                session?.close()
                session = null
                lastIoAt = 0L
                _state.update {
                    it.copy(
                        connected = false,
                        status = null,
                        statusMessage = "Disconnected"
                    )
                }
                log("disconnected")
            }
            stopKeepalive()
        }
    }

    fun syncClockNow() {
        if (!_state.value.connected) {
            log("clock: not connected")
            return
        }
        scope.launch {
            launchAction("Syncing clock…") {
                val now = Calendar.getInstance()
                val reply = sendWithReconnectLocked(
                    listOf(SunrayProtocol.clockCommand(now)),
                    2500
                )
                if (!hasCommand(reply, 2)) {
                    log("clock: no reply")
                    _state.update { it.copy(statusMessage = "Clock failed") }
                    return@launchAction
                }
                val text = clockText(now)
                settings.lastClockText = text
                settings.lastClockWasCustom = false
                settings.lastClockSetAt = System.currentTimeMillis()
                _state.update {
                    it.copy(lastSync = text, statusMessage = "Connected")
                }
                log("clock synced: $text")
            }
        }
    }

    fun setCustomClock(calendar: Calendar) {
        if (!_state.value.connected) {
            log("clock: not connected")
            return
        }
        scope.launch {
            launchAction("Setting custom clock…") {
                val reply = sendWithReconnectLocked(
                    listOf(SunrayProtocol.clockCommand(calendar)),
                    2500
                )
                if (!hasCommand(reply, 2)) {
                    log("clock: no reply")
                    _state.update { it.copy(statusMessage = "Clock failed") }
                    return@launchAction
                }
                val text = clockText(calendar)
                settings.lastClockText = text
                settings.lastClockWasCustom = true
                settings.lastClockSetAt = System.currentTimeMillis()
                _state.update {
                    it.copy(lastSync = text, statusMessage = "Connected")
                }
                log("clock set: $text")
            }
        }
    }

    fun refreshStatus() {
        if (!_state.value.connected) {
            log("status: not connected")
            return
        }
        scope.launch {
            launchAction("Reading status…") {
                // Status only: never touch the clock.
                val status = readStatusLocked()
                if (status != null) {
                    log("status: ${statusSummary(status)}")
                } else {
                    log("status: no RIDING_STATUS reply")
                    _state.update { it.copy(statusMessage = "No status reply") }
                }
            }
        }
    }

    /**
     * Applies the full preference block. Preference-only: never sends the
     * clock, so a custom clock survives every settings change.
     */
    fun applyPreferences(label: String) {
        if (!_state.value.connected) {
            log("$label saved; will apply on next connect")
            return
        }
        scope.launch {
            try {
                mutex.withLock {
                    val reply = sendWithReconnectLocked(
                        listOf(
                            SunrayProtocol.preferenceCommand(
                                settings.distanceUnit,
                                settings.temperatureUnit,
                                settings.timeFormat,
                                settings.brightness,
                                settings.language
                            )
                        ),
                        2000,
                        requirePayload = false
                    )
                    if (reply.isNotEmpty()) {
                        log("$label applied")
                    } else {
                        log("$label: no acknowledgement")
                    }
                    _state.update { it.copy(statusMessage = "Connected") }
                }
            } catch (e: Exception) {
                log("$label failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Sends MOBILE_STATUS with the phone's GPS/internet/battery state. */
    fun sendMobileStatusTest() {
        if (!_state.value.connected) {
            log("mobile status test: not connected")
            return
        }
        scope.launch {
            try {
                mutex.withLock {
                    val command = buildMobileStatus(currentClockForMobile())
                    val description = mobileStatusDescription()
                    val reply = sendWithReconnectLocked(
                        listOf(command),
                        2500,
                        requirePayload = false
                    )
                    val status = SunrayProtocol.replyStatus(
                        reply,
                        SunrayProtocol.CMD_MOBILE_STATUS
                    )
                    _state.update { it.copy(mobileStatusText = description) }
                    when {
                        status == null -> log("mobile status test: ack only ($description)")
                        status == 0 -> log("mobile status test sent ($description)")
                        else -> log("mobile status test rejected: status $status ($description)")
                    }
                }
            } catch (e: Exception) {
                log("mobile status test failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private data class ClockChoice(
        val calendar: Calendar,
        val text: String,
        val custom: Boolean,
        val label: String
    )

    private fun chooseClockForConnect(): ClockChoice {
        val now = Calendar.getInstance()
        if (settings.autoSyncClock) {
            return ClockChoice(now, clockText(now), false, "clock synced")
        }
        effectiveCustomClock()?.let { return it }
        return ClockChoice(now, clockText(now), false, "clock initialized")
    }

    private fun currentClockForMobile(): Calendar {
        if (settings.autoSyncClock) return Calendar.getInstance()
        return effectiveCustomClock()?.calendar ?: Calendar.getInstance()
    }

    /** Custom clock advances with real elapsed time instead of jumping back. */
    private fun effectiveCustomClock(): ClockChoice? {
        if (!settings.lastClockWasCustom) return null
        val base = settings.lastClockText?.let { parseClock(it) } ?: return null
        val setAt = settings.lastClockSetAt
        val now = System.currentTimeMillis()
        val elapsed = if (setAt in 1 until now) now - setAt else 0L
        val effective = Calendar.getInstance().apply {
            timeInMillis = base.timeInMillis + elapsed
        }
        return ClockChoice(effective, clockText(effective), true, "clock preserved")
    }

    private fun parseClock(text: String): Calendar? = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(text)
        if (parsed == null) null else Calendar.getInstance().apply { time = parsed }
    } catch (_: Exception) {
        null
    }

    private fun buildMobileStatus(calendar: Calendar): ByteArray =
        SunrayProtocol.mobileStatusCommand(
            calendar,
            if (gpsOn()) 2 else 0,
            if (internetOn()) 2 else 0,
            gpsAccuracy(),
            batteryLevel(),
            // The official app initializes map availability to 1; this is
            // likely one of the phone-link icons on the dashboard.
            1
        )

    private fun gpsOn(): Boolean {
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return false
        return try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    private fun internetOn(): Boolean {
        return try {
            val manager = appContext.getSystemService(ConnectivityManager::class.java)
                ?: return false
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun gpsAccuracy(): Int {
        if (!gpsOn()) return 255
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return 255
        val location = try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            null
        } ?: return 255
        if (System.currentTimeMillis() - location.time > 10 * 60 * 1000L) return 255
        return location.accuracy.toInt().coerceIn(0, 255)
    }

    private fun batteryLevel(): Int {
        val manager = appContext.getSystemService(BatteryManager::class.java) ?: return 255
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else 255
    }

    private fun mobileStatusDescription(): String {
        val gps = if (gpsOn()) "on" else "off"
        val accuracy = gpsAccuracy()
        val accuracyText = if (accuracy == 255) "?" else "${accuracy}m"
        val internet = if (internetOn()) "on" else "off"
        val battery = batteryLevel()
        val batteryText = if (battery == 255) "?" else "$battery%"
        return "gps=$gps ($accuracyText), internet=$internet, " +
                "battery=$batteryText, map=on"
    }

    /**
     * Installs a creation bundle with the official stock file-transfer
     * sequence (commands 0x0A/0x0B/0x0D).
     */
    fun installCreation(bundle: CreationBundle, label: String) {
        if (!_state.value.connected) {
            log("creation: not connected")
            setCreationStatus("$label: not connected")
            return
        }
        scope.launch {
            try {
                mutex.withLock {
                    val active = ensureTransferSessionLocked(force = false)
                    setCreationStatus("$label: beginning")
                    val error = transferBundle(active, bundle, label)
                    if (error == null) {
                        setCreationStatus("$label: installed")
                        log("creation installed: $label")
                    } else {
                        setCreationStatus("$label: failed")
                        log("creation failed: $label — $error")
                    }
                }
            } catch (e: Exception) {
                setCreationStatus("$label: failed")
                log("creation failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Experimental batched creation transfer. Starts a whole batch of files
     * before sending their data/finish commands, approximating the official
     * SunrayInstallTaskHandler queue. Used only for the digital speedometer
     * experiment.
     */
    fun installCreationBatched(bundle: CreationBundle, label: String) {
        if (!_state.value.connected) {
            log("creation: not connected")
            setCreationStatus("$label: not connected")
            return
        }
        scope.launch {
            try {
                mutex.withLock {
                    val active = ensureTransferSessionLocked(force = false)
                    setCreationStatus("$label: beginning (batched)")
                    val error = transferBundleBatched(active, bundle, label)
                    if (error == null) {
                        setCreationStatus("$label: installed")
                        log("creation installed: $label")
                    } else {
                        setCreationStatus("$label: failed")
                        log("creation failed: $label — $error")
                    }
                }
            } catch (e: Exception) {
                setCreationStatus("$label: failed")
                log("creation failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun setCreationStatus(text: String) {
        _state.update { it.copy(creationStatus = text) }
    }

    private fun ensureTransferSessionLocked(force: Boolean = false): NoodoeSession {
        var active = session
        if (force ||
            active == null ||
            (lastIoAt > 0 && System.currentTimeMillis() - lastIoAt > 7000L)
        ) {
            active = reconnectLocked(
                currentDevice() ?: throw IllegalStateException("no target device")
            )
        }
        return active
    }

    private fun transferBundle(
        active: NoodoeSession,
        bundle: CreationBundle,
        label: String
    ): String? {
        if (bundle.files.isEmpty()) return "empty bundle"
        // Large bundles are exactly the ones that leave partial files behind
        // after a failure. Start them from a clean location; the 2-file tests
        // intentionally leave the existing dashboard in place.
        if (bundle.files.size > 6) {
            setCreationStatus("$label: clearing old slot…")
            val clearStatus = clearCreation(active, bundle.location, bundle.contentId, label)
            if (clearStatus != 0 && clearStatus != 2 && clearStatus != 21) {
                return "clear status $clearStatus"
            }
        }
        val task = nextTransferTaskId()
        val total = bundle.files.sumOf { it.data.size.toLong() }
        log(
            "creation begin (pipelined): $label task=$task location=0x" +
                    Integer.toHexString(bundle.location) + " files=${bundle.files.size} " +
                    "bytes=$total"
        )
        var began = false

        fun cleanup() {
            if (!began) return
            try {
                val payload = transferCommand(
                    active,
                    SunrayProtocol.fileNegotiate(
                        task,
                        2,
                        bundle.location,
                        total,
                        3,
                        bundle.contentId
                    ),
                    20000
                )
                log("creation cleanup status=${SunrayProtocol.payloadStatus(payload)}")
            } catch (_: Exception) {
            }
        }

        return try {
            val beginPayload = transferCommand(
                active,
                SunrayProtocol.fileNegotiate(
                    task,
                    2,
                    bundle.location,
                    total,
                    1,
                    bundle.contentId
                ),
                20000,
                settleMs = 100
            )
            val beginStatus = SunrayProtocol.payloadStatus(beginPayload)
            if (beginStatus == 21) {
                // ERROR_EXIST on begin means the dashboard already has this
                // exact creation. The official app reports success here too.
                setCreationStatus("$label: installed (already present)")
                log("creation already exists: $label")
                return null
            }
            if (beginStatus != 0) {
                return "begin status $beginStatus"
            }
            began = true

            if (bundle.files.size > 6) {
                // Official-style in-flight install: up to ten active files,
                // advance on the outer SPP ACK, process start/finish replies
                // asynchronously, refill slots as finishes arrive. This keeps
                // every command inside the dashboard's ten-second window even
                // for the larger digital bundle.
                setCreationStatus("$label: transferring (in flight)…")
                val engine = CreationTransferEngine(
                    transport = object : CreationTransport {
                        override fun send(command: ByteArray, timeoutMs: Long): ByteArray {
                            val reply = active.sendFast(command, timeoutMs)
                            lastIoAt = System.currentTimeMillis()
                            return reply
                        }

                        override fun read(timeoutMs: Long): ByteArray {
                            val reply = active.readFrames(timeoutMs)
                            lastIoAt = System.currentTimeMillis()
                            return reply
                        }
                    },
                    bundle = bundle,
                    taskId = task,
                    totalSize = total,
                    log = ::log
                )
                val engineError = engine.run()
                if (engineError != null) {
                    cleanup()
                    return engineError
                }
                setCreationStatus("$label: committed; check the dashboard")
                return null
            }

            // The dashboard closes the creation transfer task ten seconds
            // after the begin command. The official app keeps several files
            // in flight and sends each finish without waiting for its reply.
            // Waiting for every finish reply is what made a 9/12-file install
            // stop at file 4 in the 20:44 log. Nine is within the official
            // install handler's ten-file limit, so the 9-file clock fits in a
            // single group; the 12-file digital uses 9 + 3.
            val pendingFinishes = LinkedHashMap<Int, String>()
            val maxActive = 9
            var base = 0
            while (base < bundle.files.size) {
                val end = minOf(base + maxActive, bundle.files.size)
                val group = bundle.files.subList(base, end)
                val started = ArrayList<Triple<Int, CreationFile, Long>>()

                for ((indexInGroup, file) in group.withIndex()) {
                    val transferId = base + indexInGroup + 1
                    setCreationStatus(
                        "$label: starting file $transferId/${bundle.files.size} ${file.name}"
                    )
                    val (startPayload, startStream) = transferCommandRaw(
                        active,
                        SunrayProtocol.fileControlByMd5(
                            task,
                            1,
                            transferId,
                            file.md5,
                            file.paddedCrc,
                            file.data.size.toLong(),
                            file.name
                        ),
                        10000,
                        expectedTransferId = transferId,
                        settleMs = 10
                    )
                    checkFinishReplies(startStream, pendingFinishes, task)?.let {
                        cleanup()
                        return it
                    }
                    val startStatus = SunrayProtocol.payloadStatus(startPayload)
                    if (startStatus == 21) {
                        log("creation file exists: ${file.name}")
                        continue
                    }
                    if (startStatus != 0) {
                        cleanup()
                        return "file $transferId start status $startStatus"
                    }
                    val received = SunrayProtocol.payloadReceivedLength(startPayload)
                    if (received >= file.data.size) {
                        log("creation file already complete: ${file.name}")
                        continue
                    }
                    if (received > 0) {
                        log("creation file resume: ${file.name} at $received/${file.data.size}")
                    }
                    started.add(Triple(transferId, file, received))
                }

                for ((transferId, file, startOffset) in started) {
                    var offset = startOffset.toInt()
                    while (offset < file.data.size) {
                        val remaining = file.data.size - offset
                        val count = if (remaining <= 11816) remaining else 11816
                        val (dataPayload, dataStream) = transferCommandRaw(
                            active,
                            SunrayProtocol.fileData(
                                task,
                                transferId,
                                file.data,
                                offset,
                                count
                            ),
                            10000,
                            expectedTransferId = transferId,
                            settleMs = 10
                        )
                        checkFinishReplies(dataStream, pendingFinishes, task)?.let {
                            cleanup()
                            return it
                        }
                        val dataReply = SunrayProtocol.parseFileDataReply(dataPayload)
                        if (dataReply == null) {
                            cleanup()
                            return "file $transferId no data reply"
                        }
                        if (dataReply.status != 0) {
                            cleanup()
                            return "file $transferId data status ${dataReply.status}"
                        }
                        if (dataReply.taskId != task ||
                            dataReply.transferId != transferId
                        ) {
                            cleanup()
                            return "file $transferId data identity mismatch"
                        }
                        offset += count
                        setCreationStatus(
                            "$label: file $transferId/${bundle.files.size} " +
                                    "$offset/${file.data.size}"
                        )
                    }
                    log("creation data complete: ${file.name}")
                }

                // Send every finish without waiting for its inner reply. The
                // replies are collected while the next start/data command
                // runs, and the final done reply confirms the commit.
                for ((transferId, file, _) in started) {
                    pendingFinishes[transferId] = file.name
                    val finishStream = active.sendFast(
                        SunrayProtocol.fileControlByMd5(
                            task,
                            2,
                            transferId,
                            file.md5,
                            file.paddedCrc,
                            file.data.size.toLong(),
                            file.name
                        ),
                        5000
                    )
                    lastIoAt = System.currentTimeMillis()
                    checkFinishReplies(finishStream, pendingFinishes, task)?.let {
                        cleanup()
                        return it
                    }
                    log("creation finish sent: ${file.name}")
                }
                base = end
            }

            val (donePayload, doneStream) = transferCommandRaw(
                active,
                SunrayProtocol.fileNegotiate(
                    task,
                    2,
                    bundle.location,
                    total,
                    3,
                    bundle.contentId
                ),
                10000,
                settleMs = 100
            )
            checkFinishReplies(doneStream, pendingFinishes, task)?.let { return it }
            val doneStatus = SunrayProtocol.payloadStatus(donePayload)
            if (doneStatus != 0) {
                return "done status $doneStatus"
            }
            if (pendingFinishes.isNotEmpty()) {
                log(
                    "creation finish replies pending: " +
                            pendingFinishes.keys.joinToString(",")
                )
            }
            setCreationStatus("$label: committed; check the dashboard")
            null
        } catch (e: Exception) {
            cleanup()
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun transferBundleBatched(
        active: NoodoeSession,
        bundle: CreationBundle,
        label: String
    ): String? {
        if (bundle.files.isEmpty()) return "empty bundle"
        if (bundle.files.size > 6) {
            setCreationStatus("$label: clearing old slot…")
            val clearStatus = clearCreation(active, bundle.location, bundle.contentId, label)
            if (clearStatus != 0 && clearStatus != 2 && clearStatus != 21) {
                return "clear status $clearStatus"
            }
        }
        val task = nextTransferTaskId()
        val total = bundle.files.sumOf { it.data.size.toLong() }
        val batchSize = 10
        log(
            "creation begin (batched): $label task=$task location=0x" +
                    Integer.toHexString(bundle.location) + " files=${bundle.files.size} " +
                    "bytes=$total batch=$batchSize"
        )
        var began = false

        fun cleanup() {
            if (!began) return
            try {
                val payload = transferCommand(
                    active,
                    SunrayProtocol.fileNegotiate(
                        task,
                        2,
                        bundle.location,
                        total,
                        3,
                        bundle.contentId
                    ),
                    20000
                )
                log("creation cleanup status=${SunrayProtocol.payloadStatus(payload)}")
            } catch (_: Exception) {
            }
        }

        return try {
            val beginPayload = transferCommand(
                active,
                SunrayProtocol.fileNegotiate(
                    task,
                    2,
                    bundle.location,
                    total,
                    1,
                    bundle.contentId
                ),
                20000
            )
            val beginStatus = SunrayProtocol.payloadStatus(beginPayload)
            if (beginStatus != 0) return "begin status $beginStatus"
            began = true
            var lastKeepAliveAt = System.currentTimeMillis()
            fun keepAliveIfNeeded() {
                if (System.currentTimeMillis() - lastKeepAliveAt >= 5000L) {
                    lastKeepAliveAt = transferKeepAlive(
                        active,
                        task,
                        bundle.location,
                        total,
                        bundle.contentId,
                        label
                    )
                }
            }

            val batches = bundle.files.chunked(batchSize)
            var baseIndex = 0
            for ((batchIndex, batch) in batches.withIndex()) {
                val started = ArrayList<Pair<Int, CreationFile>>()
                setCreationStatus(
                    "$label: batch ${batchIndex + 1}/${batches.size} " +
                            "starting ${batch.size} files"
                )
                for ((indexInBatch, file) in batch.withIndex()) {
                    keepAliveIfNeeded()
                    val transferId = baseIndex + indexInBatch + 1
                    val startPayload = transferCommand(
                        active,
                        SunrayProtocol.fileControlByMd5(
                            task,
                            1,
                            transferId,
                            file.md5,
                            file.paddedCrc,
                            file.data.size.toLong(),
                            file.name
                        ),
                        20000,
                        expectedTransferId = transferId
                    )
                    val startStatus = SunrayProtocol.payloadStatus(startPayload)
                    if (startStatus == 21) {
                        log("creation file exists: ${file.name}")
                        continue
                    }
                    if (startStatus != 0) {
                        cleanup()
                        return "file $transferId start status $startStatus"
                    }
                    started.add(transferId to file)
                }

                // Keep the whole batch open while sending data.
                for ((transferId, file) in started) {
                    var offset = 0
                    while (offset < file.data.size) {
                        keepAliveIfNeeded()
                        val remaining = file.data.size - offset
                        val count = if (remaining <= 11816) remaining else 11816
                        val dataPayload = transferCommand(
                            active,
                            SunrayProtocol.fileData(
                                task,
                                transferId,
                                file.data,
                                offset,
                                count
                            ),
                            20000,
                            expectedTransferId = transferId
                        )
                        val dataReply = SunrayProtocol.parseFileDataReply(dataPayload)
                        if (dataReply == null) {
                            cleanup()
                            return "file $transferId no data reply"
                        }
                        if (dataReply.status != 0) {
                            cleanup()
                            return "file $transferId data status ${dataReply.status}"
                        }
                        if (dataReply.taskId != task ||
                            dataReply.transferId != transferId
                        ) {
                            cleanup()
                            return "file $transferId data identity mismatch"
                        }
                        offset += count
                        setCreationStatus(
                            "$label: batch ${batchIndex + 1}/${batches.size} " +
                                    "file $transferId $offset/${file.data.size}"
                        )
                    }
                }

                for ((transferId, file) in started) {
                    keepAliveIfNeeded()
                    val finishPayload = transferCommand(
                        active,
                        SunrayProtocol.fileControlByMd5(
                            task,
                            2,
                            transferId,
                            file.md5,
                            file.paddedCrc,
                            file.data.size.toLong(),
                            file.name
                        ),
                        20000,
                        expectedTransferId = transferId
                    )
                    val finishStatus = SunrayProtocol.payloadStatus(finishPayload)
                    if (finishStatus != 0) {
                        cleanup()
                        return "file $transferId finish status $finishStatus"
                    }
                    log("creation file complete: ${file.name}")
                }
                baseIndex += batch.size
            }

            keepAliveIfNeeded()
            val donePayload = transferCommand(
                active,
                SunrayProtocol.fileNegotiate(
                    task,
                    2,
                    bundle.location,
                    total,
                    3,
                    bundle.contentId
                ),
                20000
            )
            val doneStatus = SunrayProtocol.payloadStatus(donePayload)
            if (doneStatus != 0) return "done status $doneStatus"
            setCreationStatus("$label: committed; check the dashboard")
            null
        } catch (e: Exception) {
            cleanup()
            e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * Delete the old creation at this location before uploading a replacement.
     * Failed installs leave partial files behind, and the dashboard's creation
     * slot fills up. The official protocol uses TRANSFER_REMOVE (attribute 4)
     * with its own task ID. Status 0 = removed, 2 = nothing to remove; both are
     * fine to continue from.
     */
    private fun clearCreation(
        active: NoodoeSession,
        location: Int,
        contentId: ByteArray,
        label: String
    ): Int {
        var lastStatus = 2
        // Pass 1 removes the creation identified by the same cfg hash, which
        // frees the image files referenced by a previous failed attempt.
        // Pass 2 is the official no-content id remove; it clears whatever
        // creation is still current at the location (for example the old
        // analog speedometer before installing the digital one).
        for ((pass, id) in listOf<ByteArray?>(contentId, null).withIndex()) {
            val task = nextTransferTaskId()
            val command = if (id != null) {
                SunrayProtocol.fileNegotiate(task, 2, location, 0, 4, id)
            } else {
                SunrayProtocol.fileNegotiateRemove(task, location)
            }
            val status = try {
                val payload = transferCommand(active, command, 15000)
                SunrayProtocol.payloadStatus(payload)
            } catch (e: Exception) {
                log(
                    "creation clear ($label) pass ${pass + 1} failed: " +
                            (e.message ?: e.javaClass.simpleName)
                )
                -1
            }
            log(
                "creation clear ($label) pass ${pass + 1} task=$task location=0x" +
                        Integer.toHexString(location) + " status=$status"
            )
            lastStatus = status
            if (status == 0 || status == 21) {
                // The delete is asynchronous on the dashboard. Starting the
                // begin a few milliseconds later leaves the old files in the
                // slot and makes the install stop around file 4-5 / 10.
                log("creation clear ($label) pass ${pass + 1} settling")
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        return lastStatus
    }

    /**
     * Official installations send a FILE_TRANSFER_NEGOTIATE(continue) every
     * 10 s. The dashboard drops a task that goes ~15 s without one; that is
     * why the 7th clock file / 8th digital file stopped answering even though
     * the device accepts far more than six files. Send it more often (5 s)
     * and ignore failures so a slow keepalive cannot abort the install.
     */
    private fun transferKeepAlive(
        active: NoodoeSession,
        task: Int,
        location: Int,
        total: Long,
        contentId: ByteArray,
        label: String
    ): Long {
        try {
            val payload = transferCommand(
                active,
                SunrayProtocol.fileNegotiate(task, 2, location, total, 2, contentId),
                10000
            )
            log("creation keepalive ($label) status=${SunrayProtocol.payloadStatus(payload)}")
        } catch (e: Exception) {
            log("creation keepalive ($label) failed: ${e.message ?: e.javaClass.simpleName}")
        }
        return System.currentTimeMillis()
    }

    private fun transferCommand(
        active: NoodoeSession,
        command: ByteArray,
        timeoutMs: Long,
        session: Int = 0,
        expectedTransferId: Int? = null,
        settleMs: Long = 600L
    ): ByteArray? = transferCommandRaw(
        active,
        command,
        timeoutMs,
        session,
        expectedTransferId,
        settleMs
    ).first

    /**
     * Same as transferCommand, but also returns the complete received stream.
     * Pipelined finishes are acknowledged by later commands, so their replies
     * arrive inside a later command's stream and must be inspected from there.
     */
    private fun transferCommandRaw(
        active: NoodoeSession,
        command: ByteArray,
        timeoutMs: Long,
        session: Int = 0,
        expectedTransferId: Int? = null,
        settleMs: Long = 600L
    ): Pair<ByteArray?, ByteArray> {
        val commandId = command[2].toInt() and 0xFF
        val reply = active.sendAll(
            listOf(command),
            timeoutMs,
            session,
            waitForPayload = true,
            expectedCommandId = commandId,
            expectedTransferId = expectedTransferId,
            settleMs = settleMs
        )
        lastIoAt = System.currentTimeMillis()
        val payload = if (expectedTransferId != null) {
            SunrayProtocol.replyPayloadForTransfer(reply, commandId, expectedTransferId)
        } else {
            SunrayProtocol.replyPayload(reply, commandId)
        }
        return payload to reply
    }

    /**
     * Check any finish replies that arrived while a later command was being
     * sent. Returns an error string if a finish failed.
     */
    private fun checkFinishReplies(
        stream: ByteArray,
        pending: MutableMap<Int, String>,
        task: Int
    ): String? {
        if (pending.isEmpty()) return null
        for (payload in SunrayProtocol.controlReplyPayloads(stream)) {
            if (SunrayProtocol.payloadTaskId(payload) != task) continue
            val transferId = SunrayProtocol.payloadTransferId(payload) ?: continue
            val name = pending[transferId] ?: continue
            val status = SunrayProtocol.payloadStatus(payload)
            if (status != 0 && status != 21) {
                return "file $transferId finish status $status"
            }
            pending.remove(transferId)
            log("creation finish confirmed: $name")
        }
        return null
    }

    private fun nextTransferTaskId(): Int {
        val value = transferTaskId
        transferTaskId = if (value >= 127) 1 else value + 1
        return value
    }

    fun newLog() {
        logStore.startNewLog()
        _state.update { it.copy(logPath = logStore.currentFile?.absolutePath, logText = "") }
        log("new log started")
    }

    fun clearLogs() {
        val deleted = logStore.clearLogs()
        _state.update { it.copy(logPath = logStore.currentFile?.absolutePath, logText = "") }
        log("deleted $deleted log file(s)")
    }

    fun shareIntent() = logStore.shareIntent()

    fun shareAllLogsIntent(): Intent? {
        val intent = logStore.shareAllLogsIntent()
        log(if (intent != null) "all logs zipped for sharing" else "no logs to zip")
        return intent
    }

    // ---- notifications -------------------------------------------------

    fun notifyIncomingCall(caller: String) {
        if (!settings.notifyCalls) return
        sendNotification("call", SunrayProtocol.callStatusCommand(1, caller))
    }

    fun notifyCallStop() {
        if (!settings.notifyCalls) return
        sendNotification("call end", SunrayProtocol.callStatusCommand(2, ""))
    }

    fun notifyMissedCall(caller: String) {
        if (!settings.notifyCalls) return
        sendNotification("missed call", SunrayProtocol.callStatusCommand(3, caller))
    }

    fun notifySms(sender: String, message: String) {
        if (!settings.notifySms) return
        sendNotification("SMS", SunrayProtocol.smsCommand(sender, message))
    }

    fun notifyApp(appId: String, appName: String, content: String) {
        if (!settings.notifyApps) return
        val command = SunrayProtocol.appNotificationCommand(appId, appName, content)
        val fallback = if (appId != "chat" && appId != "email") {
            SunrayProtocol.appNotificationCommand("chat", appName, content)
        } else {
            null
        }
        sendNotification("app", command, fallback = fallback)
    }

    /** Bypasses the per-source switches and the key-on filter. */
    fun sendTestNotification() {
        if (!_state.value.connected) {
            log("test notification: not connected")
            return
        }
        // Use the dashboard's generic chat ID. Some dashboards reject an
        // arbitrary package name with ERROR_INVALID_DATA (8).
        sendNotification(
            "test",
            SunrayProtocol.appNotificationCommand(
                "chat",
                "Test",
                "Test notification"
            ),
            force = true
        )
    }

    /** Diagnostic: try the app IDs the dashboard may accept. */
    fun sendNotificationIdTests() {
        if (!_state.value.connected) {
            log("notification id test: not connected")
            return
        }
        scope.launch {
            try {
                mutex.withLock {
                    val ids = listOf(
                        "chat" to "Chat",
                        "email" to "Email",
                        "com.whatsapp" to "WhatsApp",
                        "com.facebook.orca" to "Messenger"
                    )
                    for ((id, name) in ids) {
                        val status = sendNotificationCommand(
                            SunrayProtocol.appNotificationCommand(
                                id,
                                name,
                                "Test notification"
                            )
                        )
                        log("notify id '$id': ${statusText(status)}")
                        delay(250)
                    }
                }
            } catch (e: Exception) {
                log("notification id test failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Diagnostic: check whether the dash accepts call and SMS commands. */
    fun sendCallSmsTest() {
        if (!_state.value.connected) {
            log("call/SMS test: not connected")
            return
        }
        scope.launch {
            try {
                mutex.withLock {
                    val call = sendNotificationCommand(
                        SunrayProtocol.callStatusCommand(1, "Test Caller")
                    )
                    log("call test: ${statusText(call)}")
                    delay(250)
                    val sms = sendNotificationCommand(
                        SunrayProtocol.smsCommand("Test", "Test message")
                    )
                    log("SMS test: ${statusText(sms)}")
                }
            } catch (e: Exception) {
                log("call/SMS test failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun statusText(status: Int?): String = when (status) {
        null -> "no status reply"
        0 -> "accepted"
        8 -> "rejected: invalid data (8)"
        5 -> "rejected: invalid state (5)"
        else -> "rejected: status $status"
    }

    private fun sendNotification(
        label: String,
        command: ByteArray,
        force: Boolean = false,
        fallback: ByteArray? = null
    ) {
        if (!force && !canNotify()) return
        scope.launch {
            try {
                mutex.withLock {
                    if (!force && !canNotify()) return@withLock
                    val status = sendNotificationCommand(command)
                    when {
                        status == null -> log("$label notification: no status reply")
                        status == 0 -> log("$label notification sent")
                        else -> {
                            log("$label notification rejected: status $status")
                            if (fallback != null) {
                                val fallbackStatus = sendNotificationCommand(fallback)
                                if (fallbackStatus == 0) {
                                    log("$label notification sent (chat fallback)")
                                } else {
                                    log(
                                        "$label chat fallback rejected: " +
                                                "status ${fallbackStatus ?: "none"}"
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("$label notification failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun sendNotificationCommand(command: ByteArray): Int? {
        val reply = sendWithReconnectLocked(
            listOf(command),
            2000,
            requirePayload = false
        )
        return SunrayProtocol.replyStatus(reply, command[2].toInt() and 0xFF)
    }

    private fun canNotify(): Boolean {
        if (!_state.value.connected) return false
        if (!settings.notifyOnlyKeyOn) return true
        return _state.value.status?.keyOn == true
    }

    // ---- keepalive -----------------------------------------------------

    fun startKeepalive() {
        if (keepaliveJob?.isActive == true) return
        log("keepalive started")
        keepaliveJob = scope.launch {
            while (isActive) {
                if (!settings.keepAlive) {
                    delay(5000)
                    continue
                }
                val delayMs = if (!_state.value.connected) {
                    minOf(60000L, 5000L * (keepaliveFailures + 1))
                } else {
                    // Keep the link awake while the service is enabled. Some
                    // dashboards report key-on incorrectly in RIDING_STATUS.
                    6000L
                }
                delay(delayMs)
                try {
                    mutex.withLock {
                        if (!_state.value.connected) {
                            val device = currentDevice() ?: return@withLock
                            reconnectLocked(device)
                        } else {
                            readStatusLocked()
                        }
                    }
                    keepaliveFailures = 0
                } catch (e: Exception) {
                    keepaliveFailures++
                    if (keepaliveFailures == 1 || keepaliveFailures % 10 == 0) {
                        log("keepalive: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    fun stopKeepalive() {
        if (keepaliveJob == null) return
        keepaliveJob?.cancel()
        keepaliveJob = null
        log("keepalive stopped")
    }

    // ---- internals -----------------------------------------------------

    private suspend fun launchAction(message: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, statusMessage = message) }
        try {
            mutex.withLock { block() }
        } catch (e: Exception) {
            log("ERROR: ${e.message ?: e.javaClass.simpleName}")
            _state.update {
                it.copy(statusMessage = "Error: ${e.message ?: "unknown"}")
            }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun openSession(device: BluetoothDevice): DeviceInfo {
        val newSession = NoodoeSession { message -> log(message) }
        session?.close()
        session = newSession
        try {
            val info = newSession.connect(device)
            lastIoAt = System.currentTimeMillis()
            _state.update {
                it.copy(connected = true, deviceInfo = info, statusMessage = "Connected")
            }
            return info
        } catch (e: Exception) {
            newSession.close()
            session = null
            throw e
        }
    }

    private fun reconnectLocked(device: BluetoothDevice): NoodoeSession {
        session?.close()
        session = null
        log("reconnecting…")
        val fresh = NoodoeSession { message -> log(message) }
        try {
            val info = fresh.connect(device)
            session = fresh
            lastIoAt = System.currentTimeMillis()
            _state.update {
                it.copy(connected = true, deviceInfo = info, statusMessage = "Connected")
            }
            log("reconnected: fw ${info.firmware}, protocol ${info.protocol}")
            try {
                val mobile = buildMobileStatus(currentClockForMobile())
                fresh.sendAll(
                    listOf(mobile),
                    2500,
                    waitForPayload = false,
                    settleMs = 20L
                )
                lastIoAt = System.currentTimeMillis()
                log("mobile status refreshed: ${mobileStatusDescription()}")
            } catch (e: Exception) {
                log("mobile status refresh failed: ${e.message ?: e.javaClass.simpleName}")
            }
            return fresh
        } catch (e: Exception) {
            fresh.close()
            _state.update {
                it.copy(connected = false, statusMessage = "Reconnect failed")
            }
            throw e
        }
    }

    private fun currentDevice(): BluetoothDevice? {
        targetDevice?.let { return it }
        val address = settings.lastDeviceAddress ?: return null
        return try {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
        } catch (_: Exception) {
            null
        }
    }

    private fun sendWithReconnectLocked(
        commands: List<ByteArray>,
        timeoutMs: Long,
        requirePayload: Boolean = true
    ): ByteArray {
        var active = session
        if (active == null ||
            (lastIoAt > 0 && System.currentTimeMillis() - lastIoAt > 7000L)
        ) {
            if (active != null) log("session idle; reconnecting…")
            active = reconnectLocked(currentDevice() ?: throw IllegalStateException("no target device"))
        }
        var reply = try {
            active.sendAll(commands, timeoutMs)
        } catch (e: Exception) {
            log("send failed: ${e.message ?: e.javaClass.simpleName}; reconnecting…")
            active = reconnectLocked(currentDevice() ?: throw IllegalStateException("no target device"))
            active.sendAll(commands, timeoutMs)
        }
        val hasReply = if (requirePayload) {
            SunrayProtocol.sequencePayloads(reply).isNotEmpty()
        } else {
            reply.isNotEmpty()
        }
        if (!hasReply) {
            log("no reply; reconnecting…")
            active = reconnectLocked(currentDevice() ?: throw IllegalStateException("no target device"))
            reply = active.sendAll(commands, timeoutMs)
        }
        lastIoAt = System.currentTimeMillis()
        return reply
    }

    private fun readStatusLocked(): RidingStatus? {
        val commands = listOf(SunrayProtocol.ridingStatusCommand())
        var reply = sendWithReconnectLocked(commands, 2500)
        var status = SunrayProtocol.parseRidingStatus(reply)
        if (status == null) {
            reply = sendWithReconnectLocked(commands, 3000)
            status = SunrayProtocol.parseRidingStatus(reply)
        }
        if (status != null) {
            _state.update {
                it.copy(
                    status = status,
                    lastStatusAt = clockText(Calendar.getInstance()),
                    statusMessage = "Connected"
                )
            }
        }
        return status
    }

    private fun hasCommand(reply: ByteArray, commandId: Int): Boolean {
        val payload = SunrayProtocol.join(
            *SunrayProtocol.sequencePayloads(reply).toTypedArray()
        )
        return SunrayProtocol.commandPackets(payload).any {
            it.size >= 3 && (it[2].toInt() and 0xFF) == commandId
        }
    }

    fun log(message: String) {
        val line = logStore.append(message)
        _state.update {
            it.copy(logText = (it.logText + line + "\n").takeLast(16000))
        }
    }

    private fun statusSummary(status: RidingStatus): String {
        return "keyOn=${status.keyOn}, speed=${status.currentSpeedKph} km/h, " +
                "max=${status.maxSpeedKph} km/h, " +
                "ODO=${String.format(Locale.US, "%.0f", status.odoKm)} km, " +
                "stop=${status.stopDuration}"
    }

    private fun clockText(calendar: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(calendar.time)
}
