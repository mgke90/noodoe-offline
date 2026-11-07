package com.noodoe.offline.protocol

import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Sunray / KYMCO Noodoe dashboard protocol, reversed from com.noodoe.sunray 2.1.16.
 *
 * Outer SPP frame:
 *   5A FF | total (u16 LE) | ctrl | index | ack | session | reserved |
 *   payload | checksum
 *
 * Command packet inside the payload:
 *   A5 5A | command | attribute | 00 00 | length (u32 LE) | data
 *
 * The clock is part of MOBILE_STATUS (command 2), not a dedicated command.
 */
object SunrayProtocol {

    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val ATTR_READ = 1
    const val ATTR_WRITE = 2

    const val CMD_MOBILE_STATUS = 2
    const val CMD_PREFERENCE_SETTING = 4
    const val CMD_FILE_TRANSFER_NEGOTIATE = 10
    const val CMD_FILE_TRANSFER_CONTROL = 11
    const val CMD_RIDING_STATUS = 12
    const val CMD_FILE_TRANSFER = 13
    const val CMD_CALL_STATUS = 19
    const val CMD_SMS = 20
    const val CMD_APP_NOTIFICATION = 21

    fun handshake(): ByteArray = byteArrayOf(0x05, 0x00, 0x00, 0x00, 0x00)

    fun command(id: Int, attribute: Int, data: ByteArray): ByteArray {
        val out = ByteArray(10 + data.size)
        out[0] = 0xA5.toByte()
        out[1] = 0x5A
        out[2] = id.toByte()
        out[3] = attribute.toByte()
        out[6] = data.size.toByte()
        out[7] = (data.size shr 8).toByte()
        out[8] = (data.size shr 16).toByte()
        out[9] = (data.size shr 24).toByte()
        data.copyInto(out, 10)
        return out
    }

    fun sequence(payload: ByteArray, index: Int, ackIndex: Int, session: Int = 0): ByteArray {
        val total = payload.size + 10
        val out = ByteArray(9 + payload.size + 1)
        out[0] = 0x5A
        out[1] = 0xFF.toByte()
        out[2] = total.toByte()
        out[3] = (total shr 8).toByte()
        out[4] = if (ackIndex > 0) 0x40.toByte() else 0x00.toByte()
        out[5] = index.toByte()
        out[6] = ackIndex.toByte()
        out[7] = (session and 0x01).toByte()
        payload.copyInto(out, 9)
        out[out.size - 1] = checksum(payload)
        return out
    }

    fun ack(index: Int, ackIndex: Int): ByteArray = byteArrayOf(
        0x5A, 0xFF.toByte(), 0x09, 0x00,
        if (ackIndex > 0) 0x40.toByte() else 0x00.toByte(),
        index.toByte(), ackIndex.toByte(), 0x00, 0x00
    )

    /**
     * MOBILE_STATUS combines the clock with the phone's GPS/internet/battery
     * state. The extra bytes are what light the dashboard status icons.
     */
    fun mobileStatusCommand(
        calendar: Calendar,
        gpsState: Int,
        internetState: Int,
        gpsAccuracy: Int,
        batteryLevel: Int,
        mapAvailability: Int
    ): ByteArray {
        val data = ByteArray(12)
        data[0] = (calendar.get(Calendar.YEAR) - 2000).toByte()
        data[1] = (calendar.get(Calendar.MONTH) + 1).toByte()
        data[2] = calendar.get(Calendar.DAY_OF_MONTH).toByte()
        data[3] = calendar.get(Calendar.HOUR_OF_DAY).toByte()
        data[4] = calendar.get(Calendar.MINUTE).toByte()
        data[5] = calendar.get(Calendar.SECOND).toByte()
        val dow = calendar.get(Calendar.DAY_OF_WEEK)
        data[6] = (if (dow == Calendar.SUNDAY) 7 else dow - 1).toByte()
        data[7] = gpsState.toByte()
        data[8] = internetState.toByte()
        data[9] = gpsAccuracy.toByte()
        data[10] = batteryLevel.toByte()
        data[11] = mapAvailability.toByte()
        return command(CMD_MOBILE_STATUS, ATTR_WRITE, data)
    }

    /** Legacy clock-only shape, kept for the captured-frame test. */
    fun clockCommand(calendar: Calendar): ByteArray =
        mobileStatusCommand(calendar, 2, 2, 255, 255, 1)

    /**
     * PREFERENCE_SETTING. 0xFF = do not care, except shut-down time (0).
     * Pass null for timeFormat to leave it unchanged.
     */
    fun preferenceCommand(timeFormat: Int?): ByteArray =
        preferenceCommand(0xFF, 0xFF, timeFormat ?: 0xFF, 0xFF, 0xFF)

    /**
     * Full PREFERENCE_SETTING payload. Fields we do not manage are 0xFF
     * ("do not care"): breathing light, default dashboard, username,
     * notify-on-navigation. Shut-down time uses 0 as do-not-care.
     */
    fun preferenceCommand(
        distanceUnit: Int,
        temperatureUnit: Int,
        timeFormat: Int,
        brightness: Int,
        language: Int
    ): ByteArray {
        val data = ByteArray(10) { 0xFF.toByte() }
        data[0] = distanceUnit.toByte()
        data[1] = temperatureUnit.toByte()
        data[2] = timeFormat.toByte()
        data[4] = brightness.toByte()
        data[6] = language.toByte()
        data[7] = 0.toByte()
        return command(CMD_PREFERENCE_SETTING, ATTR_WRITE, data)
    }

    fun ridingStatusCommand(): ByteArray =
        command(CMD_RIDING_STATUS, ATTR_READ, ByteArray(0))

    /** Call state: 1 = incoming, 2 = incoming stopped, 3 = missed. */
    fun callStatusCommand(status: Int, caller: String): ByteArray =
        command(
            CMD_CALL_STATUS,
            ATTR_WRITE,
            byteArrayOf(status.toByte()) + encodedString(caller)
        )

    fun smsCommand(sender: String, message: String): ByteArray =
        command(
            CMD_SMS,
            ATTR_WRITE,
            encodedString(sender) + encodedString(message)
        )

    fun appNotificationCommand(
        appId: String,
        appName: String,
        notification: String
    ): ByteArray =
        command(
            CMD_APP_NOTIFICATION,
            ATTR_WRITE,
            encodedString(appId, Charsets.US_ASCII) +
                    encodedString(appName) +
                    encodedString(notification)
        )

    /**
     * 2-byte little-endian length prefix. The original app's
     * Helpers.createStringBytes() uses a short length for app/call/SMS
     * strings; only the user-name field uses the separate 1-byte helper.
     * Using 1 byte here makes the dashboard reply ERROR_INVALID_DATA (8).
     */
    private fun encodedString(
        value: String,
        charset: java.nio.charset.Charset = Charsets.UTF_8
    ): ByteArray {
        var text = value
        while (text.toByteArray(charset).size > 1024 && text.isNotEmpty()) {
            text = text.dropLast(1)
        }
        val bytes = text.toByteArray(charset)
        return byteArrayOf(
            (bytes.size and 0xFF).toByte(),
            ((bytes.size shr 8) and 0xFF).toByte()
        ) + bytes
    }

    // ---- creation/theme file transfer ---------------------------------

    fun fileNegotiate(
        taskId: Int,
        type: Int,
        location: Int,
        totalSize: Long,
        attribute: Int,
        contentId: ByteArray
    ): ByteArray {
        val data = ByteArray(27)
        putU16(data, 0, taskId)
        putU16(data, 2, type)
        putU16(data, 4, location)
        putU32(data, 6, totalSize)
        data[10] = attribute.toByte()
        contentId.copyInto(data, 11, 0, minOf(16, contentId.size))
        return command(CMD_FILE_TRANSFER_NEGOTIATE, ATTR_WRITE, data)
    }

    /** Official TRANSFER_REMOVE (attribute 4): delete the creation at a location. */
    fun fileNegotiateRemove(taskId: Int, location: Int): ByteArray {
        // The official serializer always emits 16 bytes after the attribute;
        // with no content id it writes 16 zero bytes.
        val data = ByteArray(27)
        putU16(data, 0, taskId)
        putU16(data, 2, 2)
        putU16(data, 4, location)
        putU32(data, 6, 0)
        data[10] = 4
        return command(CMD_FILE_TRANSFER_NEGOTIATE, ATTR_WRITE, data)
    }

    fun fileControlByMd5(
        taskId: Int,
        operation: Int,
        transferId: Int,
        md5: ByteArray,
        paddedCrc32: Long,
        size: Long,
        targetName: String
    ): ByteArray {
        val nameBytes = targetName.toByteArray(Charsets.UTF_8)
        val data = ByteArray(2 + 2 + 2 + 16 + 4 + 4 + 2 + nameBytes.size)
        var p = 0
        putU16(data, p, taskId); p += 2
        putU16(data, p, operation); p += 2
        putU16(data, p, transferId); p += 2
        md5.copyInto(data, p, 0, minOf(16, md5.size)); p += 16
        putU32(data, p, paddedCrc32); p += 4
        putU32(data, p, size); p += 4
        putU16(data, p, nameBytes.size); p += 2
        nameBytes.copyInto(data, p)
        return command(CMD_FILE_TRANSFER_CONTROL, ATTR_WRITE, data)
    }

    fun fileData(
        taskId: Int,
        transferId: Int,
        source: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray {
        val data = ByteArray(6 + length)
        putU16(data, 0, taskId)
        putU16(data, 2, transferId)
        putU16(data, 4, 1)
        source.copyInto(data, 6, offset, offset + length)
        return command(CMD_FILE_TRANSFER, ATTR_WRITE, data)
    }

    /** Data bytes of the first command reply with the requested command id. */
    fun replyPayload(stream: ByteArray, commandId: Int): ByteArray? {
        val payload = join(*sequencePayloads(stream).toTypedArray())
        val packet = commandPackets(payload).firstOrNull {
            it.size >= 10 && (it[2].toInt() and 0xFF) == commandId
        } ?: return null
        val length = u16(packet, 6)
        if (packet.size < 10 + length) return null
        return packet.copyOfRange(10, 10 + length)
    }

    /**
     * Pick the command reply for a specific transfer id. The dashboard may
     * retransmit an older file reply while we are waiting for the next one;
     * selecting by transfer id prevents that stale reply from being treated
     * as the new file's start/finish acknowledgement.
     */
    fun replyPayloadForTransfer(
        stream: ByteArray,
        commandId: Int,
        transferId: Int
    ): ByteArray? {
        val payload = join(*sequencePayloads(stream).toTypedArray())
        for (packet in commandPackets(payload)) {
            if (packet.size < 10 || (packet[2].toInt() and 0xFF) != commandId) continue
            val length = u16(packet, 6)
            if (length < 6 || packet.size < 10 + length) continue
            val data = packet.copyOfRange(10, 10 + length)
            if (u16(data, 4) == transferId) return data
        }
        return null
    }

    fun payloadStatus(payload: ByteArray?): Int =
        if (payload == null || payload.size < 2) -1 else u16(payload, 0)

    /** Task id in a FILE_TRANSFER_CONTROL or FILE_TRANSFER reply payload. */
    fun payloadTaskId(payload: ByteArray?): Int? =
        if (payload == null || payload.size < 4) null else u16(payload, 2)

    /**
     * Operation/attribute in a FILE_TRANSFER_NEGOTIATE reply payload.
     * The dashboard emits status(2), task(2), attribute(1), e.g.
     * `00 00 04 00 02` for a continue reply to task 4.
     */
    fun payloadNegotiateAttribute(payload: ByteArray?): Int? =
        if (payload == null || payload.size < 5) null else payload[4].toInt() and 0xFF

    /** Every command packet across the sequence frames in a received stream. */
    fun allCommandPackets(stream: ByteArray): List<ByteArray> =
        commandPackets(join(*sequencePayloads(stream).toTypedArray()))

    /** Transfer id in a FILE_TRANSFER_CONTROL or FILE_TRANSFER reply payload. */
    fun payloadTransferId(payload: ByteArray?): Int? =
        if (payload == null || payload.size < 6) null else u16(payload, 4)

    /**
     * Bytes the dashboard already holds for a file. A start reply carries this
     * at offset 6; non-zero means a previous attempt left a partial file and
     * the data stream must resume from that offset (official
     * handleControlReply skips this many bytes).
     */
    fun payloadReceivedLength(payload: ByteArray?): Long =
        if (payload == null || payload.size < 10) 0 else u32(payload, 6)

    /**
     * Every FILE_TRANSFER_CONTROL reply payload in a received stream, in order.
     * The pipelined install sends several finishes before reading all replies,
     * so they must all be inspected, not just the first matching one.
     */
    fun controlReplyPayloads(stream: ByteArray): List<ByteArray> {
        val payload = join(*sequencePayloads(stream).toTypedArray())
        val out = ArrayList<ByteArray>()
        for (packet in commandPackets(payload)) {
            if (packet.size < 10 ||
                (packet[2].toInt() and 0xFF) != CMD_FILE_TRANSFER_CONTROL
            ) {
                continue
            }
            val length = u16(packet, 6)
            if (length < 6 || packet.size < 10 + length) continue
            out.add(packet.copyOfRange(10, 10 + length))
        }
        return out
    }

    data class FileDataReply(
        val status: Int,
        val taskId: Int,
        val transferId: Int,
        val chunkSize: Long,
        val cumulativeSize: Long
    )

    fun parseFileDataReply(payload: ByteArray?): FileDataReply? {
        if (payload == null || payload.size < 16) return null
        return FileDataReply(
            status = u16(payload, 0),
            taskId = u16(payload, 2),
            transferId = u16(payload, 4),
            chunkSize = u32(payload, 8),
            cumulativeSize = u32(payload, 12)
        )
    }

    fun join(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    /**
     * First data byte of a command reply: 0 = success, 8 = invalid data,
     * 5 = invalid state, etc. Returns null when the device only sent an ACK.
     */
    fun replyStatus(stream: ByteArray, commandId: Int): Int? {
        val payload = join(*sequencePayloads(stream).toTypedArray())
        val packet = commandPackets(payload).firstOrNull {
            it.size >= 11 && (it[2].toInt() and 0xFF) == commandId
        } ?: return null
        if (u16(packet, 6) < 1) return null
        return packet[10].toInt() and 0xFF
    }

    fun parseDeviceInfo(raw: ByteArray): DeviceInfo? {
        if (raw.size < 12 || raw[0].toInt() and 0xFF != 0x85) return null
        val dataStart = 6
        if (raw.size < dataStart + 12) return null

        fun u8(offset: Int): Int =
            if (dataStart + offset < raw.size) raw[dataStart + offset].toInt() and 0xFF else 0

        fun u16(offset: Int): Int {
            if (dataStart + offset + 1 >= raw.size) return 0
            return (raw[dataStart + offset].toInt() and 0xFF) or
                    ((raw[dataStart + offset + 1].toInt() and 0xFF) shl 8)
        }

        fun ascii(offset: Int, length: Int): String {
            if (dataStart + offset + length > raw.size) return ""
            val bytes = raw.copyOfRange(dataStart + offset, dataStart + offset + length)
            return bytes.toString(Charsets.US_ASCII).trimEnd('\u0000', ' ')
        }

        val mac = buildString {
            for (i in 11 downTo 6) {
                if (isNotEmpty()) append(':')
                append(String.format(Locale.US, "%02X", u8(i)))
            }
        }

        val protocol = u8(4)
        return DeviceInfo(
            firmwareMajor = u16(0),
            firmwareMinor = u16(2),
            protocolMajor = protocol shr 4,
            protocolMinor = protocol and 0x0F,
            hardwareVersion = u8(5),
            mac = mac,
            bootloaderMajor = u16(12),
            bootloaderMinor = u16(14),
            resourceMajor = u16(16),
            resourceMinor = u16(18),
            modelName = ascii(38, 10),
            bikeMaxSpeed = u16(48),
            bikeSeriesNumber = ascii(50, 18),
            bikeType = u8(68),
            pinCode = u16(69),
            motorSeries = u16(71),
            pcbaHardwareVersion = ascii(73, 6),
            defaultDashboardId = u8(79)
        )
    }

    fun parseRidingStatus(stream: ByteArray): RidingStatus? {
        val packet = commandPackets(join(*sequencePayloads(stream).toTypedArray()))
            .firstOrNull { it.size >= 10 && (it[2].toInt() and 0xFF) == CMD_RIDING_STATUS }
            ?: return null
        val length = u16(packet, 6)
        if (packet.size < 10 + length || length < 9) return null
        val p = 10
        // The reply starts with a 2-byte status (00 00 = success), then
        // key-on, ODO in km, max speed and current speed. The dashboard photo
        // (066573 km) confirms the ODO is data[3..6], not data[1..4].
        if (u16(packet, p) != 0) return null
        return RidingStatus(
            keyOn = packet[p + 2].toInt() != 0,
            odoKmRaw = u32(packet, p + 3),
            stopDuration = if (length >= 11) u16(packet, p + 9) else 0,
            maxSpeedKph = packet[p + 7].toInt() and 0xFF,
            currentSpeedKph = packet[p + 8].toInt() and 0xFF
        )
    }

    /** Sequence frames received from the dashboard, with their packet index. */
    fun deviceFrames(stream: ByteArray): List<DeviceFrame> {
        val out = ArrayList<DeviceFrame>()
        var i = 0
        while (i + 9 <= stream.size) {
            if ((stream[i].toInt() and 0xFF) != 0x5A ||
                (stream[i + 1].toInt() and 0xFF) != 0xFF
            ) {
                i++
                continue
            }
            val total = u16(stream, i + 2)
            if (total < 9 || i + total > stream.size) break
            out.add(DeviceFrame(stream[i + 5].toInt() and 0xFF, total > 10))
            i += total
        }
        return out
    }

    fun sequencePayloads(stream: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 9 <= stream.size) {
            if ((stream[i].toInt() and 0xFF) != 0x5A ||
                (stream[i + 1].toInt() and 0xFF) != 0xFF
            ) {
                i++
                continue
            }
            val total = u16(stream, i + 2)
            if (total < 9 || i + total > stream.size) break
            if (total > 10) {
                out.add(stream.copyOfRange(i + 9, i + total - 1))
            }
            i += total
        }
        return out
    }

    fun commandPackets(stream: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 10 <= stream.size) {
            if ((stream[i].toInt() and 0xFF) != 0xA5 ||
                (stream[i + 1].toInt() and 0xFF) != 0x5A
            ) {
                i++
                continue
            }
            val length = u16(stream, i + 6)
            val total = 10 + length
            if (i + total > stream.size) break
            out.add(stream.copyOfRange(i, i + total))
            i += total
        }
        return out
    }

    fun checksum(payload: ByteArray): Byte {
        var sum = 0
        for (byte in payload) sum += byte.toInt() and 0xFF
        return ((256 - (sum and 0xFF)) and 0xFF).toByte()
    }

    fun hex(data: ByteArray): String =
        data.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

    private fun putU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putU32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun u16(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return 0
        return (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }
}
