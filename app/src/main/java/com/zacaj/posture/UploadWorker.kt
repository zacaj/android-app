package com.zacaj.posture

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Uploads finished traces from `ready/` to GitHub and/or the LAN listener. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = Settings(applicationContext)
        val root = traceRoot(applicationContext)
        val ready = File(root, "ready").listFiles()?.sortedBy { it.name } ?: emptyList()
        val done = File(root, "uploaded").apply { mkdirs() }
        val github = s.githubToken.isNotBlank() && s.githubRepo.isNotBlank()
        val lan = s.lanTraceUpload && s.lanUrl.isNotBlank()
        val ctx = applicationContext
        if (!github && !lan) {
            if (ready.isNotEmpty()) Feedback.show(ctx, "${ready.size} trace(s) waiting; no upload target configured", error = true)
            return@withContext Result.success()
        }
        if (ready.isEmpty()) {
            Feedback.show(ctx, "Nothing to upload")
            return@withContext Result.success()
        }

        var failed = false
        var uploaded = 0
        var lastError = ""
        for (f in ready) {
            val bytes = f.readBytes()
            val err = (if (github) uploadGitHub(s, f.name, bytes) else null)
                ?: (if (lan) uploadLan(s, f.name, bytes) else null)
            if (err == null) {
                f.renameTo(File(done, f.name)); uploaded++
            } else {
                failed = true; lastError = err
            }
        }
        if (failed) Feedback.show(ctx, "Uploaded $uploaded/${ready.size} traces; failed: $lastError", error = true)
        else Feedback.show(ctx, "Uploaded $uploaded trace(s)")
        // Keep a week of uploaded traces on-device.
        val cutoff = System.currentTimeMillis() - 7 * 24 * 3600_000L
        done.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        if (failed) Result.retry() else Result.success()
    }

    /** Returns null on success, else an error description. */
    private fun uploadLan(s: Settings, name: String, bytes: ByteArray): String? = try {
        val code = Net.request("POST", "${s.lanUrl}/trace/$name", bytes, "application/gzip")
        if (code in 200..299) null else "LAN HTTP $code"
    } catch (e: Exception) {
        "LAN ${e.javaClass.simpleName}: ${e.message}"
    }

    /** Returns null on success, else an error description. */
    private fun uploadGitHub(s: Settings, name: String, bytes: ByteArray): String? {
        val path = "traces/${s.deviceName}/$name"
        val body = JSONObject()
            .put("message", "trace $name")
            .put("content", Base64.getEncoder().encodeToString(bytes))
            .put("branch", s.githubBranch)
            .toString().toByteArray()
        val code = try {
            Net.request(
                "PUT", "https://api.github.com/repos/${s.githubRepo}/contents/$path", body, "application/json",
                mapOf("Authorization" to "Bearer ${s.githubToken}", "Accept" to "application/vnd.github+json"),
            )
        } catch (e: Exception) {
            Log.w("UploadWorker", "github upload failed: $e")
            return "GitHub ${e.javaClass.simpleName}: ${e.message}"
        }
        // 422 = file already exists (previous attempt succeeded but we didn't hear back)
        return when (code) {
            in 200..299, 422 -> null
            401 -> "GitHub 401: bad token"
            403 -> "GitHub 403: token lacks Contents write on ${s.githubRepo}"
            404 -> "GitHub 404: repo or branch '${s.githubBranch}' not found (or token can't see it)"
            else -> "GitHub HTTP $code"
        }
    }

    companion object {
        fun traceRoot(ctx: Context) = File(ctx.filesDir, "traces")

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UploadWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.UNMETERED))
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("upload", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("upload-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
