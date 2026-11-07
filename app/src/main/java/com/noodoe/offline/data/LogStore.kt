package com.noodoe.offline.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * App-specific log storage only:
 *   /sdcard/Android/data/com.noodoe.offline/files/logs/
 *
 * No storage permission is requested. Clear only removes noodoe-*.txt, .log
 * and .zip files directly inside that directory.
 */
class LogStore(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private var writer: BufferedWriter? = null

    var currentFile: File? = null
        private set

    private val logDir: File
        get() {
            val external = appContext.getExternalFilesDir("logs")
            val dir = external ?: File(appContext.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    init {
        startNewLog()
    }

    fun startNewLog(): File? {
        synchronized(lock) {
            closeLocked()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            var file = File(logDir, "noodoe-$stamp.txt")
            var suffix = 1
            while (file.exists()) {
                file = File(logDir, "noodoe-$stamp-${suffix++}.txt")
            }
            currentFile = file
            return try {
                writer = BufferedWriter(FileWriter(file, true)).also { out ->
                    out.write("# Noodoe Offline ${versionName()}")
                    out.newLine()
                    out.write("# started " + timestamp())
                    out.newLine()
                    out.write("# device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; " +
                            "Android ${android.os.Build.VERSION.RELEASE}; SDK ${android.os.Build.VERSION.SDK_INT}")
                    out.newLine()
                    out.write("# log ${file.absolutePath}")
                    out.newLine()
                    out.newLine()
                    out.flush()
                }
                file
            } catch (_: IOException) {
                writer = null
                null
            }
        }
    }

    fun append(message: String): String {
        val line = timestamp() + "  " + message
        synchronized(lock) {
            try {
                writer?.apply {
                    write(line)
                    newLine()
                    flush()
                }
            } catch (_: IOException) {
                closeLocked()
            }
        }
        return line
    }

    /** Returns how many noodoe-*.txt/.log files were deleted. Never recurses. */
    fun clearLogs(): Int {
        val dir = logDir
        synchronized(lock) {
            closeLocked()
        }
        var deleted = 0
        try {
            val canonicalDir = dir.canonicalFile
            val files = canonicalDir.listFiles() ?: return 0
            for (file in files) {
                val canonical = file.canonicalFile
                if (canonical.parentFile != canonicalDir) continue
                if (!canonical.isFile) continue
                if (!canonical.name.startsWith("noodoe-")) continue
                if (!canonical.name.endsWith(".txt") &&
                    !canonical.name.endsWith(".log") &&
                    !canonical.name.endsWith(".zip")) continue
                if (canonical.delete()) deleted++
            }
        } catch (_: IOException) {
        }
        startNewLog()
        return deleted
    }

    fun shareIntent(): Intent? {
        val file = currentFile ?: return null
        if (!file.isFile) return null
        val uri = FileProvider.getUriForFile(
            appContext,
            appContext.packageName + ".fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Zips every noodoe-*.txt/.log in the log folder and shares the archive. */
    fun shareAllLogsIntent(): Intent? {
        var zipFile: File? = null
        synchronized(lock) {
            val dir = logDir
            try {
                writer?.flush()
            } catch (_: IOException) {
                closeLocked()
            }
            val logs = dir.listFiles()
                ?.filter {
                    it.isFile &&
                            it.name.startsWith("noodoe-") &&
                            (it.name.endsWith(".txt") || it.name.endsWith(".log"))
                }
                ?.sortedBy { it.name }
                ?: return null
            if (logs.isEmpty()) return null

            val name = "noodoe-logs-" +
                    SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) +
                    ".zip"
            val zip = File(dir, name)
            try {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
                    for (log in logs) {
                        out.putNextEntry(ZipEntry(log.name))
                        log.inputStream().use { input -> input.copyTo(out) }
                        out.closeEntry()
                    }
                }
                zipFile = zip
            } catch (_: IOException) {
                zip.delete()
                return null
            }
        }

        val file = zipFile ?: return null
        val uri = FileProvider.getUriForFile(
            appContext,
            appContext.packageName + ".fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun close() {
        synchronized(lock) {
            closeLocked()
        }
    }

    private fun closeLocked() {
        try {
            writer?.close()
        } catch (_: IOException) {
        }
        writer = null
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun versionName(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
