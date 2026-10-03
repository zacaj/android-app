package com.zacaj.posture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User-visible result of actions: a toast plus a "last message" line shown in the UI. */
object Feedback {
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message
    private val main = Handler(Looper.getMainLooper())
    private val throttled = HashMap<String, Long>()

    fun show(ctx: Context, msg: String, error: Boolean = false) {
        if (error) Log.w("Posture", msg) else Log.i("Posture", msg)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _message.value = "$time ${if (error) "⚠ " else ""}$msg"
        val app = ctx.applicationContext
        main.post { Toast.makeText(app, msg, if (error) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }

    /** Like [show] but at most once per [intervalMs] per key, for repeating background failures. */
    fun showThrottled(ctx: Context, key: String, msg: String, intervalMs: Long = 5 * 60_000L) {
        val now = System.currentTimeMillis()
        synchronized(throttled) {
            if (now - (throttled[key] ?: 0L) < intervalMs) {
                Log.w("Posture", msg); return
            }
            throttled[key] = now
        }
        show(ctx, msg, error = true)
    }
}
