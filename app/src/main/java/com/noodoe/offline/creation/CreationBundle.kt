package com.noodoe.offline.creation

import java.security.MessageDigest
import java.util.zip.CRC32

data class CreationFile(val name: String, val data: ByteArray) {
    val md5: ByteArray = md5Bytes(data)
    val paddedCrc: Long = crc32Padded(data)
}

data class CreationBundle(
    val location: Int,
    val files: List<CreationFile>,
    val contentId: ByteArray
)

internal fun md5Bytes(data: ByteArray): ByteArray =
    MessageDigest.getInstance("MD5").digest(data)

internal fun md5Hex(data: ByteArray): String =
    md5Bytes(data).joinToString("") { String.format("%02x", it.toInt() and 0xFF) }

internal fun crc32Padded(data: ByteArray): Long {
    val crc = CRC32()
    crc.update(data)
    var padding = (-data.size) and 3
    while (padding-- > 0) crc.update(0)
    return crc.value
}
