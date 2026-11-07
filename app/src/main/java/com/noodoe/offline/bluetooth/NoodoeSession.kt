package com.noodoe.offline.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.noodoe.offline.protocol.DeviceInfo
import com.noodoe.offline.protocol.SunrayProtocol
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One foreground session with a Noodoe dashboard.
 *
 * The socket is kept open between commands. Call from a background thread.
 */
@SuppressLint("MissingPermission")
class NoodoeSession(
    private val logger: (String) -> Unit = {}
) {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private var sendIndex = 0
    private val rxPending = ByteArrayOutputStream()

    var deviceInfo: DeviceInfo? = null
        private set

    val isConnected: Boolean
        get() = socket?.isConnected == true

    fun connect(device: BluetoothDevice): DeviceInfo {
        var last: Exception? = null
        for (attempt in 1..2) {
            try {
                close()
                val newSocket = device.createRfcommSocketToServiceRecord(SunrayProtocol.SPP_UUID)
                try {
                    newSocket.connect()
                } catch (e: IOException) {
                    try {
                        newSocket.close()
                    } catch (_: IOException) {
                    }
                    throw e
                }

                socket = newSocket
                input = newSocket.inputStream
                output = newSocket.outputStream
                sendIndex = 0
                rxPending.reset()
                deviceInfo = null

                val handshake = SunrayProtocol.handshake()
                logger("TX handshake " + SunrayProtocol.hex(handshake))
                output!!.write(handshake)
                output!!.flush()

                val rawInfo = readFor(input!!, 3500)
                logger("RX info " + SunrayProtocol.hex(rawInfo))
                val info = SunrayProtocol.parseDeviceInfo(rawInfo)
                    ?: throw IOException("dashboard did not return device info")
                deviceInfo = info
                return info
            } catch (e: Exception) {
                last = e
                close()
                if (attempt == 1) {
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        throw last ?: IOException("connect failed")
    }

    fun send(command: ByteArray, timeoutMs: Long = 3000, session: Int = 0): ByteArray {
        return sendAll(listOf(command), timeoutMs, session)
    }

    fun sendAll(
        commands: List<ByteArray>,
        timeoutMs: Long = 3000,
        session: Int = 0,
        waitForPayload: Boolean = false,
        expectedCommandId: Int? = null,
        expectedTransferId: Int? = null,
        settleMs: Long = 600L
    ): ByteArray {
        val activeOutput = output ?: throw IllegalStateException("not connected")
        val activeInput = input ?: throw IllegalStateException("not connected")

        val payload = SunrayProtocol.join(*commands.toTypedArray())
        val packetIndex = sendIndex
        val frame = SunrayProtocol.sequence(payload, packetIndex, 0, session)
        sendIndex = if (sendIndex >= 127) 0 else sendIndex + 1

        logger("TX " + SunrayProtocol.hex(frame))
        activeOutput.write(frame)
        activeOutput.flush()

        val collected = ByteArrayOutputStream()
        val chunk = ByteArray(512)
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastDataAt = System.currentTimeMillis()
        var gotAck = false
        var gotPayload = false
        var gotExpected = false
        var lastSendAt = System.currentTimeMillis()
        var sendAttempts = 1

        while (System.currentTimeMillis() < deadline) {
            val available = try {
                activeInput.available()
            } catch (_: IOException) {
                break
            }

            if (available > 0) {
                val n = try {
                    activeInput.read(chunk, 0, minOf(chunk.size, available))
                } catch (_: IOException) {
                    break
                }
                if (n < 0) break
                rxPending.write(chunk, 0, n)
                lastDataAt = System.currentTimeMillis()

                while (true) {
                    val frameBytes = nextFrame() ?: break
                    val total = (frameBytes[2].toInt() and 0xFF) or
                            ((frameBytes[3].toInt() and 0xFF) shl 8)
                    collected.write(frameBytes, 0, frameBytes.size)

                    val control = frameBytes[4].toInt() and 0xFF
                    val deviceIndex = frameBytes[5].toInt() and 0xFF
                    val ackIndex = frameBytes[6].toInt() and 0xFF
                    if (control == 0x40 && ackIndex == packetIndex) gotAck = true

                    if (total > 10) {
                        gotPayload = true
                        // ACK immediately, while the dashboard is waiting.
                        // Before our own frame is ACKed, the dashboard still
                        // expects the current packet index, not the next one.
                        val ackPacketIndex = if (gotAck) sendIndex else packetIndex
                        sendAckNow(activeOutput, deviceIndex, ackPacketIndex)
                        if (expectedCommandId != null) {
                            val payload = if (expectedTransferId != null) {
                                SunrayProtocol.replyPayloadForTransfer(
                                    frameBytes,
                                    expectedCommandId,
                                    expectedTransferId
                                )
                            } else {
                                SunrayProtocol.replyPayload(
                                    frameBytes,
                                    expectedCommandId
                                )
                            }
                            if (payload != null) gotExpected = true
                        } else {
                            gotExpected = true
                        }
                    }
                }
            } else {
                val now = System.currentTimeMillis()
                val replyReady = if (expectedCommandId != null) {
                    gotExpected
                } else {
                    !waitForPayload || gotPayload
                }
                if (gotAck && replyReady && now - lastDataAt >= settleMs) {
                    break
                }
                // The official sequence processor retries an unacknowledged
                // packet after 3 s. Do the same for creation commands so a
                // dashboard stuck retransmitting an older reply can recover.
                if (!gotAck && !gotExpected &&
                    sendAttempts < 3 && now - lastSendAt >= 3000L
                ) {
                    logger("TX retry " + SunrayProtocol.hex(frame))
                    activeOutput.write(frame)
                    activeOutput.flush()
                    lastSendAt = now
                    sendAttempts++
                }
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return collected.toByteArray()
    }

    /**
     * Extract the next complete sequence frame from the shared receive
     * buffer. Partial frames stay buffered, so a frame split across two
     * send/read calls is not lost.
     */
    private fun nextFrame(): ByteArray? {
        while (true) {
            val data = rxPending.toByteArray()
            if (data.size < 9) return null
            if ((data[0].toInt() and 0xFF) != 0x5A ||
                (data[1].toInt() and 0xFF) != 0xFF
            ) {
                rxPending.reset()
                rxPending.write(data, 1, data.size - 1)
                continue
            }
            val total = (data[2].toInt() and 0xFF) or
                    ((data[3].toInt() and 0xFF) shl 8)
            if (total < 9) {
                rxPending.reset()
                rxPending.write(data, 1, data.size - 1)
                continue
            }
            if (data.size < total) return null
            val frame = data.copyOf(total)
            rxPending.reset()
            rxPending.write(data, total, data.size - total)
            logger("RX " + SunrayProtocol.hex(frame))
            return frame
        }
    }

    /**
     * Read and acknowledge frames without sending a command. The in-flight
     * transfer engine calls this when every active file is waiting for an
     * inner reply; the official app's reader behaves the same way.
     */
    fun readFrames(timeoutMs: Long = 20): ByteArray {
        val activeInput = input ?: throw IllegalStateException("not connected")
        val activeOutput = output ?: throw IllegalStateException("not connected")
        val collected = ByteArrayOutputStream()
        val chunk = ByteArray(512)
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastDataAt = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            val available = try {
                activeInput.available()
            } catch (_: IOException) {
                break
            }
            if (available > 0) {
                val n = try {
                    activeInput.read(chunk, 0, minOf(chunk.size, available))
                } catch (_: IOException) {
                    break
                }
                if (n < 0) break
                rxPending.write(chunk, 0, n)
                lastDataAt = System.currentTimeMillis()
                while (true) {
                    val frame = nextFrame() ?: break
                    collected.write(frame, 0, frame.size)
                    val total = (frame[2].toInt() and 0xFF) or
                            ((frame[3].toInt() and 0xFF) shl 8)
                    if (total > 10) {
                        val deviceIndex = frame[5].toInt() and 0xFF
                        sendAckNow(activeOutput, deviceIndex, sendIndex)
                    }
                }
            } else {
                if (collected.size() > 0 &&
                    System.currentTimeMillis() - lastDataAt >= 5L
                ) {
                    break
                }
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return collected.toByteArray()
    }

    /**
     * Send one command and wait only for the outer sequence ACK. The inner
     * file-transfer reply is collected if it arrives in the short settle
     * window, but it is not required. The official installer pipelines up to
     * ten files this way; waiting for every finish reply is what pushed our
     * sequential install past the dashboard's ten-second transfer window.
     */
    fun sendFast(
        command: ByteArray,
        timeoutMs: Long = 5000,
        session: Int = 0
    ): ByteArray = sendAll(
        listOf(command),
        timeoutMs,
        session,
        waitForPayload = false,
        settleMs = 10L
    )

    fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        input = null
        output = null
        deviceInfo = null
    }

    private fun sendAckNow(
        out: OutputStream,
        deviceIndex: Int,
        packetIndex: Int
    ) {
        try {
            val ack = SunrayProtocol.ack(packetIndex, deviceIndex)
            logger("TX ack " + SunrayProtocol.hex(ack))
            out.write(ack)
            out.flush()
        } catch (_: IOException) {
        }
    }

    private fun readFor(stream: InputStream, millis: Long): ByteArray {
        val buffer = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + millis
        val chunk = ByteArray(512)

        while (System.currentTimeMillis() < deadline) {
            val available = try {
                stream.available()
            } catch (_: IOException) {
                break
            }
            if (available > 0) {
                val n = try {
                    stream.read(chunk, 0, minOf(chunk.size, available))
                } catch (_: IOException) {
                    break
                }
                if (n < 0) break
                buffer.write(chunk, 0, n)
            } else {
                try {
                    Thread.sleep(25)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return buffer.toByteArray()
    }
}
