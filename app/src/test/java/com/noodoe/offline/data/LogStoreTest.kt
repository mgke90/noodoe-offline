package com.noodoe.offline.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LogStoreTest {

    @Test
    fun zipContainsEveryLogAndClearRemovesZips() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = context.getExternalFilesDir("logs")!!
        val existing = dir.listFiles()!!.count {
            it.isFile && it.name.startsWith("noodoe-") &&
                    (it.name.endsWith(".txt") || it.name.endsWith(".log"))
        }

        val store = LogStore(context)
        store.append("first")
        store.startNewLog()
        store.append("second")
        store.startNewLog()
        store.append("third")

        val logsBefore = dir.listFiles()!!
            .filter {
                it.isFile && it.name.startsWith("noodoe-") &&
                        (it.name.endsWith(".txt") || it.name.endsWith(".log"))
            }
            .map { it.name }
            .toSet()
        assertEquals(existing + 3, logsBefore.size)

        val intent = store.shareAllLogsIntent()
        assertNotNull(intent)
        assertEquals("application/zip", intent!!.type)

        val zipFile = dir.listFiles()!!.first {
            it.isFile && it.name.startsWith("noodoe-logs-") && it.name.endsWith(".zip")
        }
        val zippedNames = mutableSetOf<String>()
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                zippedNames += entries.nextElement().name
            }
        }
        assertEquals(logsBefore, zippedNames)

        val matchingBeforeClear = dir.listFiles()!!.count {
            it.isFile && it.name.startsWith("noodoe-") &&
                    (it.name.endsWith(".txt") || it.name.endsWith(".log") ||
                            it.name.endsWith(".zip"))
        }
        assertEquals(matchingBeforeClear, store.clearLogs())

        val remaining = dir.listFiles()!!.filter {
            it.isFile && it.name.startsWith("noodoe-")
        }
        assertEquals(1, remaining.size)
        assertTrue(remaining[0].name.endsWith(".txt"))
        store.close()
    }
}
