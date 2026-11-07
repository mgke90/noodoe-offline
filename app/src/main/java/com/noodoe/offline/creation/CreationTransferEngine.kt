package com.noodoe.offline.creation

import com.noodoe.offline.protocol.SunrayProtocol

/**
 * Transport used by the in-flight creation installer. The real implementation
 * is a thin adapter over NoodoeSession; tests use an in-memory dashboard.
 */
interface CreationTransport {
    /** Send one command and return frames collected while waiting for its ACK. */
    fun send(command: ByteArray, timeoutMs: Long = 3000): ByteArray

    /** Read and acknowledge frames without sending a command. */
    fun read(timeoutMs: Long = 20): ByteArray
}

/**
 * Official-style pipelined creation transfer.
 *
 * The dashboard accepts up to ten active files and closes the transfer task
 * ten seconds after the begin. This engine therefore keeps several files in
 * flight, advances each file when its outer SPP packet is acknowledged, and
 * does not wait for every inner 0x0D data reply. Start/finish replies are
 * processed asynchronously by task id and transfer id.
 *
 * The command order follows the official `SunrayTransferFileTaskHandler`:
 * START -> (start reply) -> DATA chunks -> FINISH -> (finish reply), with a
 * TRANSFER_UPDATE_CONTINUE every ten seconds and a final
 * TRANSFER_UPDATE_DONE after all files have completed.
 */
class CreationTransferEngine(
    private val transport: CreationTransport,
    private val bundle: CreationBundle,
    private val taskId: Int,
    private val totalSize: Long,
    private val log: (String) -> Unit
) {
    private enum class Kind { START, DATA, FINISH }
    private enum class Phase {
        AWAITING_START,
        DATA_READY,
        FINISH_READY,
        AWAITING_FINISH,
        COMPLETE
    }

    private data class Pending(
        val command: ByteArray,
        val kind: Kind,
        val dataLength: Int = 0
    )

    private class FileState(
        val transferId: Int,
        val file: CreationFile
    ) {
        var phase = Phase.DATA_READY
        var sentOffset = 0
        var pending: Pending? = null
        var waitingSince = 0L
    }

    private val files = bundle.files.mapIndexed { index, file ->
        FileState(index + 1, file)
    }
    private val active = ArrayList<FileState>()
    private val waiting = ArrayDeque<FileState>()
    private var completed = 0
    private var error: String? = null
    private var doneSent = false
    private var doneStatus: Int? = null

    init {
        waiting.addAll(files)
        fillActive()
        for (file in files) {
            file.pending = startCommand(file)
        }
    }

    fun run(): String? {
        val startedAt = System.currentTimeMillis()
        var lastKeepAlive = startedAt

        while (completed < files.size) {
            error?.let { return it }
            val now = System.currentTimeMillis()
            if (now - startedAt > 60_000L) {
                return "transfer timeout"
            }

            for (file in active) {
                if ((file.phase == Phase.AWAITING_START ||
                        file.phase == Phase.AWAITING_FINISH) &&
                    file.waitingSince > 0 &&
                    now - file.waitingSince > 20_000L
                ) {
                    return "file ${file.transferId} reply timeout"
                }
            }

            if (now - lastKeepAlive >= 10_000L) {
                val stream = transport.send(keepAliveCommand(), 4000)
                lastKeepAlive = System.currentTimeMillis()
                process(stream)
                continue
            }

            val file = active.firstOrNull { it.pending != null }
            if (file == null) {
                process(transport.read(20))
                continue
            }

            val pending = file.pending ?: continue
            file.pending = null
            val stream = transport.send(pending.command, 4000)
            onSent(file, pending)
            process(stream)
        }

        error?.let { return it }
        val done = SunrayProtocol.fileNegotiate(
            taskId,
            2,
            bundle.location,
            totalSize,
            3,
            bundle.contentId
        )
        doneSent = true
        process(transport.send(done, 4000))

        val doneDeadline = System.currentTimeMillis() + 10_000L
        while (doneStatus == null && System.currentTimeMillis() < doneDeadline) {
            error?.let { return it }
            process(transport.read(20))
        }
        error?.let { return it }
        return when (val status = doneStatus) {
            null -> "done no reply"
            0 -> null
            else -> "done status $status"
        }
    }

    private fun fillActive() {
        while (active.size < 10 && waiting.isNotEmpty()) {
            active.add(waiting.removeFirst())
        }
    }

    private fun startCommand(file: FileState): Pending = Pending(
        SunrayProtocol.fileControlByMd5(
            taskId,
            1,
            file.transferId,
            file.file.md5,
            file.file.paddedCrc,
            file.file.data.size.toLong(),
            file.file.name
        ),
        Kind.START
    )

    private fun dataCommand(file: FileState): Pending? {
        if (file.sentOffset >= file.file.data.size) return null
        val remaining = file.file.data.size - file.sentOffset
        val count = if (remaining <= 11816) remaining else 11816
        return Pending(
            SunrayProtocol.fileData(
                taskId,
                file.transferId,
                file.file.data,
                file.sentOffset,
                count
            ),
            Kind.DATA,
            count
        )
    }

    private fun finishCommand(file: FileState): Pending = Pending(
        SunrayProtocol.fileControlByMd5(
            taskId,
            2,
            file.transferId,
            file.file.md5,
            file.file.paddedCrc,
            file.file.data.size.toLong(),
            file.file.name
        ),
        Kind.FINISH
    )

    private fun keepAliveCommand(): ByteArray = SunrayProtocol.fileNegotiate(
        taskId,
        2,
        bundle.location,
        totalSize,
        2,
        bundle.contentId
    )

    private fun onSent(file: FileState, pending: Pending) {
        when (pending.kind) {
            Kind.START -> {
                file.phase = Phase.AWAITING_START
                file.waitingSince = System.currentTimeMillis()
                log("creation start sent: ${file.file.name}")
            }
            Kind.DATA -> {
                file.sentOffset += pending.dataLength
                file.phase = Phase.DATA_READY
                file.pending = if (file.sentOffset >= file.file.data.size) {
                    file.phase = Phase.FINISH_READY
                    finishCommand(file)
                } else {
                    dataCommand(file)
                }
                log(
                    "creation data sent: ${file.file.name} " +
                            "${file.sentOffset}/${file.file.data.size}"
                )
                if (file.sentOffset >= file.file.data.size) {
                    log("creation data complete: ${file.file.name}")
                }
            }
            Kind.FINISH -> {
                file.phase = Phase.AWAITING_FINISH
                file.waitingSince = System.currentTimeMillis()
                log("creation finish sent: ${file.file.name}")
            }
        }
        // Round-robin: let the next active file have a turn. This mirrors the
        // official getCommandList(), which takes one packet per active file.
        if (file.phase != Phase.COMPLETE) {
            active.remove(file)
            active.add(file)
        }
    }

    private fun process(stream: ByteArray) {
        if (stream.isEmpty()) return
        for (packet in SunrayProtocol.allCommandPackets(stream)) {
            if (packet.size < 10) continue
            val commandId = packet[2].toInt() and 0xFF
            val length = (packet[6].toInt() and 0xFF) or
                    ((packet[7].toInt() and 0xFF) shl 8)
            if (packet.size < 10 + length) continue
            val data = packet.copyOfRange(10, 10 + length)
            when (commandId) {
                SunrayProtocol.CMD_FILE_TRANSFER_CONTROL -> handleControl(data)
                SunrayProtocol.CMD_FILE_TRANSFER -> handleData(data)
                SunrayProtocol.CMD_FILE_TRANSFER_NEGOTIATE -> handleNegotiate(data)
            }
        }
    }

    private fun handleControl(data: ByteArray) {
        if (SunrayProtocol.payloadTaskId(data) != taskId) return
        val transferId = SunrayProtocol.payloadTransferId(data) ?: return
        val file = files.firstOrNull { it.transferId == transferId } ?: return
        val status = SunrayProtocol.payloadStatus(data)
        when (file.phase) {
            Phase.AWAITING_START -> {
                if (status == 21) {
                    log("creation file exists: ${file.file.name}")
                    complete(file, finishConfirmed = false)
                    return
                }
                if (status != 0) {
                    error = "file $transferId start status $status"
                    return
                }
                val received = SunrayProtocol.payloadReceivedLength(data).toInt()
                if (received >= file.file.data.size) {
                    log("creation file already complete: ${file.file.name}")
                    complete(file, finishConfirmed = false)
                    return
                }
                file.sentOffset = received
                if (received > 0) {
                    log(
                        "creation file resume: ${file.file.name} at " +
                                "$received/${file.file.data.size}"
                    )
                }
                file.phase = Phase.DATA_READY
                file.waitingSince = 0L
                file.pending = dataCommand(file)
            }
            Phase.AWAITING_FINISH -> {
                if (status == 0 || status == 21) {
                    complete(file)
                } else {
                    error = "file $transferId finish status $status"
                }
            }
            else -> {
                // Duplicate or stale control reply; the current state already
                // moved on. The official handler also ignores these.
            }
        }
    }

    private fun handleData(data: ByteArray) {
        // The official app advances data chunks on the outer SPP ACK and does
        // not require the inner 0x0D reply. Keep this hook for diagnostics and
        // future per-chunk error reporting without stalling the pipeline.
        val status = SunrayProtocol.payloadStatus(data)
        if (status != 0 && SunrayProtocol.payloadTaskId(data) == taskId) {
            val transferId = SunrayProtocol.payloadTransferId(data)
            log("creation data reply: file $transferId status $status")
        }
    }

    private fun handleNegotiate(data: ByteArray) {
        if (SunrayProtocol.payloadTaskId(data) != taskId) return
        val status = SunrayProtocol.payloadStatus(data)
        val attribute = SunrayProtocol.payloadNegotiateAttribute(data)
        if (doneSent && attribute == 3) {
            if (doneStatus == null) doneStatus = status
        } else if (status != 0) {
            log("creation keepalive status=$status")
        }
    }

    private fun complete(file: FileState, finishConfirmed: Boolean = true) {
        if (file.phase == Phase.COMPLETE) return
        file.phase = Phase.COMPLETE
        file.pending = null
        completed++
        active.remove(file)
        if (finishConfirmed) {
            log("creation finish confirmed: ${file.file.name}")
        }
        fillActive()
    }
}
