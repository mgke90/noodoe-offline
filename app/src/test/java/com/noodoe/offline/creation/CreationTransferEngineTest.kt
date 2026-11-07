package com.noodoe.offline.creation

import com.noodoe.offline.protocol.SunrayProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreationTransferEngineTest {

    @Test
    fun twelveFilesStayInFlightAndComplete() {
        val files = (0 until 12).map { index ->
            val size = if (index == 0) 30_000 else 120 + index * 40
            CreationFile("file$index.png", ByteArray(size) { (it + index).toByte() })
        }
        val bundle = CreationBundle(
            location = 1024,
            files = files,
            contentId = ByteArray(16) { it.toByte() }
        )
        val fake = FakeDashboard()
        val engine = CreationTransferEngine(
            transport = fake,
            bundle = bundle,
            taskId = 7,
            totalSize = files.sumOf { it.data.size.toLong() },
            log = {}
        )

        assertNull(engine.run())
        assertTrue(fake.done)
        assertEquals(12, fake.finished.size)
        assertEquals(10, fake.maxActive)
        assertTrue(fake.order.contains("START 11"))
        assertTrue(fake.order.contains("START 12"))
        assertEquals(30_000, fake.received[1])
        assertTrue(fake.order.indexOf("START 11") < fake.order.indexOf("DONE"))
    }

    @Test
    fun existingFileIsSkippedWithoutData() {
        val files = (1..4).map { index ->
            CreationFile("file$index.png", ByteArray(200))
        }
        val bundle = CreationBundle(
            location = 512,
            files = files,
            contentId = ByteArray(16)
        )
        val fake = FakeDashboard(existsTransferId = 2)
        val engine = CreationTransferEngine(
            transport = fake,
            bundle = bundle,
            taskId = 3,
            totalSize = files.sumOf { it.data.size.toLong() },
            log = {}
        )

        assertNull(engine.run())
        assertNull(fake.firstChunkSize[2])
        // File 2 was already present, so it has no data/finish command.
        assertEquals(3, fake.finished.size)
    }

    @Test
    fun partialFileResumesFromStartReplyOffset() {
        val file = CreationFile("resume.png", ByteArray(200))
        val bundle = CreationBundle(
            location = 512,
            files = listOf(file),
            contentId = ByteArray(16)
        )
        val fake = FakeDashboard(resumeTransferId = 1, resumeAt = 50)
        val engine = CreationTransferEngine(
            transport = fake,
            bundle = bundle,
            taskId = 4,
            totalSize = file.data.size.toLong(),
            log = {}
        )

        assertNull(engine.run())
        assertEquals(200, fake.received[1])
        assertEquals(150, fake.firstChunkSize[1])
    }

    @Test
    fun startErrorAborts() {
        val file = CreationFile("bad.png", ByteArray(100))
        val bundle = CreationBundle(512, listOf(file), ByteArray(16))
        val fake = FakeDashboard(errorStartTransferId = 1)
        val engine = CreationTransferEngine(
            transport = fake,
            bundle = bundle,
            taskId = 5,
            totalSize = 100,
            log = {}
        )

        assertEquals("file 1 start status 5", engine.run())
    }

    @Test
    fun finishErrorAborts() {
        val file = CreationFile("bad.png", ByteArray(100))
        val bundle = CreationBundle(512, listOf(file), ByteArray(16))
        val fake = FakeDashboard(errorFinishTransferId = 1)
        val engine = CreationTransferEngine(
            transport = fake,
            bundle = bundle,
            taskId = 6,
            totalSize = 100,
            log = {}
        )

        assertEquals("file 1 finish status 5", engine.run())
    }

    private class FakeDashboard(
        private val errorStartTransferId: Int? = null,
        private val errorFinishTransferId: Int? = null,
        private val existsTransferId: Int? = null,
        private val resumeTransferId: Int? = null,
        private val resumeAt: Int = 0
    ) : CreationTransport {
        var deviceIndex = 0x80
        var done = false
        var maxActive = 0
        val order = ArrayList<String>()
        val finished = HashSet<Int>()
        val received = HashMap<Int, Int>()
        val firstChunkSize = HashMap<Int, Int>()
        private val started = HashSet<Int>()

        override fun send(command: ByteArray, timeoutMs: Long): ByteArray {
            val id = command[2].toInt() and 0xFF
            val length = u16(command, 6)
            val data = command.copyOfRange(10, 10 + length)
            return when (id) {
                SunrayProtocol.CMD_FILE_TRANSFER_CONTROL -> control(data)
                SunrayProtocol.CMD_FILE_TRANSFER -> transfer(data)
                SunrayProtocol.CMD_FILE_TRANSFER_NEGOTIATE -> negotiate(data)
                else -> ByteArray(0)
            }
        }

        override fun read(timeoutMs: Long): ByteArray = ByteArray(0)

        private fun control(data: ByteArray): ByteArray {
            val task = u16(data, 0)
            val operation = u16(data, 2)
            val transferId = u16(data, 4)
            val size = u32(data, 26).toInt()
            if (operation == 1) {
                order.add("START $transferId")
                maxActive = maxOf(maxActive, started.size + 1)
                val status = when {
                    errorStartTransferId == transferId -> 5
                    existsTransferId == transferId -> 21
                    else -> 0
                }
                val startReceived = if (resumeTransferId == transferId) resumeAt else 0
                started.add(transferId)
                received[transferId] = startReceived
                val reply = byteArrayOf(
                    status.toByte(), 0,
                    task.toByte(), (task shr 8).toByte(),
                    transferId.toByte(), (transferId shr 8).toByte()
                ) + u32Bytes(startReceived.toLong())
                return reply(SunrayProtocol.command(11, 0x0A, reply))
            }
            order.add("FINISH $transferId")
            val status = if (errorFinishTransferId == transferId) 5 else 0
            if (status == 0) {
                finished.add(transferId)
                started.remove(transferId)
            }
            val reply = byteArrayOf(
                status.toByte(), 0,
                task.toByte(), (task shr 8).toByte(),
                transferId.toByte(), (transferId shr 8).toByte()
            ) + u32Bytes(size.toLong())
            return reply(SunrayProtocol.command(11, 0x0A, reply))
        }

        private fun transfer(data: ByteArray): ByteArray {
            val task = u16(data, 0)
            val transferId = u16(data, 2)
            val chunk = data.copyOfRange(6, data.size)
            if (!firstChunkSize.containsKey(transferId)) {
                firstChunkSize[transferId] = chunk.size
            }
            received[transferId] = (received[transferId] ?: 0) + chunk.size
            order.add("DATA $transferId")
            val reply = byteArrayOf(
                0, 0,
                task.toByte(), (task shr 8).toByte(),
                transferId.toByte(), (transferId shr 8).toByte(),
                1, 0
            ) + u32Bytes(chunk.size.toLong()) +
                    u32Bytes((received[transferId] ?: 0).toLong())
            return reply(SunrayProtocol.command(13, 0x0A, reply))
        }

        private fun negotiate(data: ByteArray): ByteArray {
            val task = u16(data, 0)
            val attribute = data[10].toInt() and 0xFF
            if (attribute == 3) {
                done = true
                order.add("DONE")
            }
            val reply = byteArrayOf(
                0, 0,
                task.toByte(), (task shr 8).toByte(),
                attribute.toByte()
            )
            return reply(SunrayProtocol.command(10, 0x0A, reply))
        }

        private fun reply(command: ByteArray): ByteArray {
            val frame = SunrayProtocol.sequence(command, deviceIndex, 0)
            deviceIndex = if (deviceIndex >= 0xFF) 0x80 else deviceIndex + 1
            return frame
        }

        private fun u16(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8)

        private fun u32(data: ByteArray, offset: Int): Long =
            (data[offset].toLong() and 0xFF) or
                    ((data[offset + 1].toLong() and 0xFF) shl 8) or
                    ((data[offset + 2].toLong() and 0xFF) shl 16) or
                    ((data[offset + 3].toLong() and 0xFF) shl 24)

        private fun u32Bytes(value: Long): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
}
