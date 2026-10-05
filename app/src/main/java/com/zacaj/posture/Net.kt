package com.zacaj.posture

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object Net {
    private const val TAG = "PostureNet"
    private val executor = Executors.newSingleThreadExecutor()

    /** Last LAN POST error, null after a success. Shown in the ongoing notification instead of toasts. */
    @Volatile var lanError: String? = null
        private set
    /** Called (on the network thread) when [lanError] changes. */
    @Volatile var onLanErrorChanged: (() -> Unit)? = null

    private fun setLanError(e: String?) {
        if (e == lanError) return
        lanError = e
        onLanErrorChanged?.invoke()
    }

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Fire-and-forget JSON POST to the LAN listener. Skipped off Wi-Fi when [wifiOnly]; failures go to
     * [lanError] (first one also toasts once).
     */
    fun postAsync(ctx: Context, url: String, json: String, wifiOnly: Boolean = Settings(ctx).lanWifiOnly) {
        if (wifiOnly && !onWifi(ctx)) {
            Log.i(TAG, "off Wi-Fi, skipping POST $url")
            return
        }
        executor.execute {
            val err = try {
                val code = request("POST", url, json.toByteArray(), "application/json")
                Log.i(TAG, "POST $url -> $code")
                if (code in 200..299) null else "HTTP $code"
            } catch (e: Exception) {
                e.toString()
            }
            if (err != null && lanError == null) Feedback.show(ctx, "LAN post to $url failed: $err", error = true)
            setLanError(err)
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
