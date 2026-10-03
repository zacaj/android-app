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
        if (!github && !lan) return@withContext Result.success()

        var failed = false
        for (f in ready) {
            val bytes = f.readBytes()
            val ok = (!github || uploadGitHub(s, f.name, bytes)) &&
                (!lan || Net.request("POST", "${s.lanUrl}/trace/${f.name}", bytes, "application/gzip") in 200..299)
            if (ok) f.renameTo(File(done, f.name)) else failed = true
        }
        // Keep a week of uploaded traces on-device.
        val cutoff = System.currentTimeMillis() - 7 * 24 * 3600_000L
        done.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        if (failed) Result.retry() else Result.success()
    }

    private fun uploadGitHub(s: Settings, name: String, bytes: ByteArray): Boolean {
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
            Log.w("UploadWorker", "github upload failed: $e"); return false
        }
        // 422 = file already exists (previous attempt succeeded but we didn't hear back)
        return code in 200..299 || code == 422
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
