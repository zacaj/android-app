package com.zacaj.posture

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object Net {
    private const val TAG = "PostureNet"
    private val executor = Executors.newSingleThreadExecutor()

    /** Fire-and-forget JSON POST. */
    fun postAsync(url: String, json: String) {
        executor.execute {
            try {
                val code = request("POST", url, json.toByteArray(), "application/json")
                Log.i(TAG, "POST $url -> $code")
            } catch (e: Exception) {
                Log.w(TAG, "POST $url failed: $e")
            }
        }
    }

    /** Blocking request; returns HTTP status. */
    fun request(
        method: String,
        url: String,
        body: ByteArray?,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 5000
            c.readTimeout = 30000
            c.setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code >= 400) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
                Log.w(TAG, "$method $url -> $code $err")
            }
            return code
        } finally {
            c.disconnect()
        }
    }
}
