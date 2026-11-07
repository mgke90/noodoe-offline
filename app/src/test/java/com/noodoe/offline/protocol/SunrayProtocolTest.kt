package com.noodoe.offline.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SunrayProtocolTest {

    @Test
    fun clockFrameMatchesCapturedDevice() {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(2026, Calendar.NOVEMBER, 5, 19, 10, 57)

        val command = SunrayProtocol.clockCommand(calendar)
        val frame = SunrayProtocol.sequence(command, 0, 0)

        assertEquals(
            "5A FF 20 00 00 00 00 00 00 " +
                    "A5 5A 02 02 00 00 0C 00 00 00 " +
                    "1A 0B 05 13 0A 39 04 02 02 FF FF 01 6A",
            SunrayProtocol.hex(frame)
        )
    }

    @Test
    fun deviceInfoParsesCapturedReply() {
        val raw = hex(
            "85 27 00 00 00 00 05 00 10 00 00 00 4F 38 3C E3 64 C4 " +
                    "00 00 0F 00 05 00 0E 00 F2 FD 03 00 00 00 00 00 00 00 00 00 00 00 00 00 01 02"
        )
        val info = SunrayProtocol.parseDeviceInfo(raw)
        assertNotNull(info)
        info!!
        assertEquals(5, info.firmwareMajor)
        assertEquals(16, info.firmwareMinor)
        assertEquals(0, info.protocolMajor)
        assertEquals(0, info.protocolMinor)
        assertEquals(0, info.hardwareVersion)
        assertEquals("C4:64:E3:3C:38:4F", info.mac)
        assertEquals(DeviceType.VERSION_1_5, info.deviceType)
    }

    @Test
    fun ridingStatusParsesCapturedReply() {
        val data = hex("00 00 01 1A 04 01 00 64 00 00 00")
        val command = SunrayProtocol.command(12, 0x0A, data)
        val frame = SunrayProtocol.sequence(command, 0x80, 0)

        val status = SunrayProtocol.parseRidingStatus(frame)
        assertNotNull(status)
        status!!
        assertTrue(status.keyOn)
        assertEquals(66_586L, status.odoKmRaw)
        assertEquals(66_586.0, status.odoKm, 0.001)
        assertEquals(100, status.maxSpeedKph)
        assertEquals(0, status.currentSpeedKph)
        assertEquals(0, status.stopDuration)
    }

    @Test
    fun deviceFramesKeepAckIndexStable() {
        val payload = SunrayProtocol.command(12, 0x0A, hex("00 00 01 1A 04 01 00 64 00 00 00"))
        val frame1 = SunrayProtocol.sequence(payload, 0x82, 2)
        val frame2 = SunrayProtocol.sequence(payload, 0x83, 3)
        val frame3 = SunrayProtocol.sequence(payload, 0x83, 3)

        val frames = SunrayProtocol.deviceFrames(frame1 + frame2 + frame3)
        assertEquals(listOf(0x82, 0x83, 0x83), frames.map { it.index })
        assertTrue(frames.all { it.hasPayload })

        // The ACK packet index must stay at the next send index (4 here).
        val acks = frames.map { SunrayProtocol.ack(4, it.index) }
        assertEquals("5A FF 09 00 40 04 82 00 00", SunrayProtocol.hex(acks[0]))
        assertEquals("5A FF 09 00 40 04 83 00 00", SunrayProtocol.hex(acks[1]))
        assertEquals("5A FF 09 00 40 04 83 00 00", SunrayProtocol.hex(acks[2]))
    }

    @Test
    fun ackPacketMatchesCapturedShape() {
        assertEquals(
            "5A FF 09 00 40 01 80 00 00",
            SunrayProtocol.hex(SunrayProtocol.ack(1, 0x80))
        )
    }

    @Test
    fun preferenceFrameHasExpectedLayout() {
        val command = SunrayProtocol.preferenceCommand(1)
        assertEquals(10 + 10, command.size)
        assertEquals("FF FF 01 FF FF FF FF 00 FF FF",
            SunrayProtocol.hex(command.copyOfRange(10, command.size)))
    }

    @Test
    fun fullPreferenceFrameHasExpectedLayout() {
        val command = SunrayProtocol.preferenceCommand(
            distanceUnit = 1,
            temperatureUnit = 1,
            timeFormat = 0,
            brightness = 70,
            language = 9
        )
        assertEquals(10 + 10, command.size)
        assertEquals("01 01 00 FF 46 FF 09 00 FF FF",
            SunrayProtocol.hex(command.copyOfRange(10, command.size)))
    }

    @Test
    fun mobileStatusFrameHasExpectedLayout() {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(2026, Calendar.NOVEMBER, 5, 19, 10, 57)

        val command = SunrayProtocol.mobileStatusCommand(
            calendar = calendar,
            gpsState = 1,
            internetState = 2,
            gpsAccuracy = 255,
            batteryLevel = 73,
            mapAvailability = 0
        )
        assertEquals(
            "1A 0B 05 13 0A 39 04 01 02 FF 49 00",
            SunrayProtocol.hex(command.copyOfRange(10, command.size))
        )
    }

    @Test
    fun fileNegotiateHasExpectedLayout() {
        val contentId = ByteArray(16) { it.toByte() }
        val command = SunrayProtocol.fileNegotiate(
            taskId = 5,
            type = 2,
            location = 512,
            totalSize = 1000,
            attribute = 1,
            contentId = contentId
        )
        assertEquals(
            "05 00 02 00 00 02 E8 03 00 00 01 " +
                    "00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F",
            SunrayProtocol.hex(command.copyOfRange(10, command.size))
        )
    }

    @Test
    fun fileNegotiateRemoveHasExpectedLayout() {
        val command = SunrayProtocol.fileNegotiateRemove(taskId = 5, location = 512)
        val data = command.copyOfRange(10, command.size)
        assertEquals(27, data.size)
        assertEquals(
            "05 00 02 00 00 02 00 00 00 00 04",
            SunrayProtocol.hex(data.copyOfRange(0, 11))
        )
        assertEquals(
            "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
            SunrayProtocol.hex(data.copyOfRange(11, 27))
        )
    }

    @Test
    fun fileControlHasExpectedLayout() {
        val md5 = ByteArray(16) { it.toByte() }
        val command = SunrayProtocol.fileControlByMd5(
            taskId = 5,
            operation = 1,
            transferId = 2,
            md5 = md5,
            paddedCrc32 = 0x11223344,
            size = 1000,
            targetName = "abc.jpg"
        )
        val data = command.copyOfRange(10, command.size)
        assertEquals(39, data.size)
        assertEquals("05 00 01 00 02 00", SunrayProtocol.hex(data.copyOfRange(0, 6)))
        assertEquals("44 33 22 11 E8 03 00 00 07 00", SunrayProtocol.hex(data.copyOfRange(22, 32)))
        assertEquals("abc.jpg", String(data.copyOfRange(32, 39), Charsets.UTF_8))
    }

    @Test
    fun fileDataHasExpectedLayout() {
        val command = SunrayProtocol.fileData(
            taskId = 5,
            transferId = 2,
            source = "ABCD".toByteArray(),
            offset = 1,
            length = 2
        )
        assertEquals(
            "05 00 02 00 01 00 42 43",
            SunrayProtocol.hex(command.copyOfRange(10, command.size))
        )
    }

    @Test
    fun fileDataReplyParses() {
        val data = hex(
            "00 00 05 00 02 00 00 00 " +
                    "E8 03 00 00 E8 03 00 00"
        )
        val reply = SunrayProtocol.sequence(
            SunrayProtocol.command(13, 0x0A, data),
            0x80,
            0
        )
        val parsed = SunrayProtocol.parseFileDataReply(
            SunrayProtocol.replyPayload(reply, 13)
        )
        assertNotNull(parsed)
        parsed!!
        assertEquals(0, parsed.status)
        assertEquals(5, parsed.taskId)
        assertEquals(2, parsed.transferId)
        assertEquals(1000L, parsed.chunkSize)
        assertEquals(1000L, parsed.cumulativeSize)
    }

    @Test
    fun sequenceSessionByteSeparatesControlAndFileData() {
        val control = SunrayProtocol.sequence(byteArrayOf(1), 5, 0, 0)
        val data = SunrayProtocol.sequence(byteArrayOf(1), 5, 0, 1)
        assertEquals(0, control[7].toInt() and 0xFF)
        assertEquals(1, data[7].toInt() and 0xFF)
    }

    @Test
    fun replyStatusReadsErrorCode() {
        val reply = SunrayProtocol.sequence(
            SunrayProtocol.command(21, 0x0A, hex("08 00")),
            0x82,
            0
        )
        assertEquals(8, SunrayProtocol.replyStatus(reply, 21))
        assertEquals(
            0,
            SunrayProtocol.replyStatus(
                SunrayProtocol.sequence(
                    SunrayProtocol.command(2, 0x0A, hex("00 00")),
                    0x80,
                    0
                ),
                2
            )
        )
    }

    @Test
    fun callStatusFrameHasExpectedLayout() {
        val command = SunrayProtocol.callStatusCommand(1, "Alice")
        assertEquals(
            "01 05 00 41 6C 69 63 65",
            SunrayProtocol.hex(command.copyOfRange(10, command.size))
        )
    }

    @Test
    fun smsFrameHasExpectedLayout() {
        val command = SunrayProtocol.smsCommand("12345", "Hello")
        assertEquals(
            "05 00 31 32 33 34 35 05 00 48 65 6C 6C 6F",
            SunrayProtocol.hex(command.copyOfRange(10, command.size))
        )
    }

    @Test
    fun appNotificationFrameHasExpectedLayout() {
        val command = SunrayProtocol.appNotificationCommand("com.example", "Example", "Hi")
        assertEquals(
            "0B 00 63 6F 6D 2E 65 78 61 6D 70 6C 65 " +
                    "07 00 45 78 61 6D 70 6C 65 02 00 48 69",
            SunrayProtocol.hex(command.copyOfRange(10, command.size))
        )
    }

    @Test
    fun longNotificationUsesFullLengthField() {
        val command = SunrayProtocol.appNotificationCommand(
            "com.example",
            "Example",
            "x".repeat(300)
        )
        val dataLength = command.copyOfRange(6, 10)
            .foldIndexed(0) { index, acc, byte ->
                acc or ((byte.toInt() and 0xFF) shl (8 * index))
            }
        assertEquals(command.size - 10, dataLength)
    }

    @Test
    fun controlReplyPayloadsKeepsEveryFinish() {
        val data1 = hex("00 00 05 00 01 00 00 00 00 00")
        val data2 = hex("00 00 05 00 02 00 00 00 00 00")
        val finish1 = SunrayProtocol.command(11, 0x0A, data1)
        val finish2 = SunrayProtocol.command(11, 0x0A, data2)
        val stream = SunrayProtocol.sequence(finish1, 0x80, 0) +
                SunrayProtocol.sequence(finish2, 0x81, 0)

        val replies = SunrayProtocol.controlReplyPayloads(stream)
        assertEquals(2, replies.size)
        assertEquals(1, SunrayProtocol.payloadTransferId(replies[0]))
        assertEquals(2, SunrayProtocol.payloadTransferId(replies[1]))
    }

    @Test
    fun negotiateReplyAttributeIsOneByte() {
        val data = hex("00 00 04 00 03")
        val reply = SunrayProtocol.sequence(
            SunrayProtocol.command(10, 0x0A, data),
            0x80,
            0
        )
        val payload = SunrayProtocol.replyPayload(reply, 10)
        assertEquals(4, SunrayProtocol.payloadTaskId(payload))
        assertEquals(3, SunrayProtocol.payloadNegotiateAttribute(payload))
    }

    @Test
    fun startReplyCarriesAlreadyReceivedLength() {
        val data = hex("00 00 05 00 02 00 E8 03 00 00")
        val reply = SunrayProtocol.sequence(
            SunrayProtocol.command(11, 0x0A, data),
            0x80,
            0
        )
        val payload = SunrayProtocol.replyPayloadForTransfer(reply, 11, 2)
        assertEquals(5, SunrayProtocol.payloadTaskId(payload))
        assertEquals(2, SunrayProtocol.payloadTransferId(payload))
        assertEquals(1000L, SunrayProtocol.payloadReceivedLength(payload))
    }

    private fun hex(value: String): ByteArray =
        value.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
