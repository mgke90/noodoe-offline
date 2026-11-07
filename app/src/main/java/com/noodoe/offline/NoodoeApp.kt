package com.noodoe.offline

import android.app.Application
import android.util.Log
import com.noodoe.offline.data.NoodoeController
import com.noodoe.offline.notification.PhoneStateWatcher
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoodoeApp : Application() {

    lateinit var controller: NoodoeController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger()
        controller = NoodoeController(this)
        PhoneStateWatcher.update(this)
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val dir = getExternalFilesDir("logs") ?: File(filesDir, "logs")
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
                File(dir, "noodoe-crash-$stamp.txt").bufferedWriter().use { writer ->
                    writer.write("# Noodoe Offline crash")
                    writer.newLine()
                    writer.write("# time $stamp")
                    writer.newLine()
                    writer.write("# thread ${thread.name}")
                    writer.newLine()
                    writer.newLine()
                    error.printStackTrace(PrintWriter(writer))
                }
            } catch (loggingError: Throwable) {
                Log.e("NoodoeOffline", "crash logging failed", loggingError)
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    companion object {
        lateinit var instance: NoodoeApp
            private set
    }
}
