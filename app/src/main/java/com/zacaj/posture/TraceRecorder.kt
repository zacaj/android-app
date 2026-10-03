package com.zacaj.posture

import com.zacaj.posture.core.TraceEvent
import com.zacaj.posture.core.TraceIO
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Writes gzipped trace CSVs into `active/`, rotating every [rotateMs]; closed files are moved
 * to `ready/` for [UploadWorker].
 */
class TraceRecorder(root: File, private val rotateMs: Long = 30 * 60_000L) {
    val activeDir = File(root, "active").apply { mkdirs() }
    val readyDir = File(root, "ready").apply { mkdirs() }

    private var file: File? = null
    private var out: Writer? = null
    private var openedAt = 0L

    init {
        // Anything left in active/ is from a crash; gzip may be truncated but the reader copes.
        activeDir.listFiles()?.forEach { it.renameTo(File(readyDir, it.name)) }
    }

    @Synchronized
    fun write(e: TraceEvent) {
        if (out == null || e.tMs - openedAt >= rotateMs) {
            close()
            open(e.tMs)
        }
        out!!.apply {
            write(TraceIO.format(e))
            write("\n")
        }
    }

    private fun open(tMs: Long) {
        val name = "trace-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(tMs)) + ".csv.gz"
        val f = File(activeDir, name)
        file = f
        openedAt = tMs
        out = GZIPOutputStream(f.outputStream().buffered(64 * 1024)).bufferedWriter().apply {
            write(TraceIO.HEADER)
        }
    }

    @Synchronized
    fun close() {
        out?.close()
        file?.renameTo(File(readyDir, file!!.name))
        out = null
        file = null
    }
}
