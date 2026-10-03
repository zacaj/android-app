package com.zacaj.posture

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.zacaj.posture.core.DetectorConfig
import com.zacaj.posture.core.Posture
import com.zacaj.posture.core.Vec3

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Base URL of the LAN listener, e.g. http://192.168.1.10:8765 (blank = off). */
    var lanUrl: String
        get() = p.getString("lanUrl", "")!!
        set(v) = p.edit { putString("lanUrl", v.trim().trimEnd('/')) }

    var notifyOnChange: Boolean
        get() = p.getBoolean("notifyOnChange", false)
        set(v) = p.edit { putBoolean("notifyOnChange", v) }

    /** Too-long thresholds in minutes, 0 = off. */
    fun limitMin(posture: Posture): Int =
        p.getInt("limit_$posture", if (posture == Posture.SITTING) 45 else 0)
    fun setLimitMin(posture: Posture, v: Int) = p.edit { putInt("limit_$posture", v) }

    var repeatMin: Int
        get() = p.getInt("repeatMin", 15)
        set(v) = p.edit { putInt("repeatMin", v) }

    var recordTraces: Boolean
        get() = p.getBoolean("recordTraces", true)
        set(v) = p.edit { putBoolean("recordTraces", v) }

    /** Upload finished traces to GitHub (contents API) — blank token = off. */
    var githubRepo: String
        get() = p.getString("githubRepo", "zacaj/android-app")!!
        set(v) = p.edit { putString("githubRepo", v.trim()) }
    var githubBranch: String
        get() = p.getString("githubBranch", "traces")!!
        set(v) = p.edit { putString("githubBranch", v.trim()) }
    var githubToken: String
        get() = p.getString("githubToken", "")!!
        set(v) = p.edit { putString("githubToken", v.trim()) }

    /** Also POST finished traces to the LAN listener. */
    var lanTraceUpload: Boolean
        get() = p.getBoolean("lanTraceUpload", false)
        set(v) = p.edit { putBoolean("lanTraceUpload", v) }

    var referenceAxis: Vec3?
        get() = if (!p.contains("refX")) null
        else Vec3(p.getFloat("refX", 0f), p.getFloat("refY", 1f), p.getFloat("refZ", 0f))
        set(v) = p.edit {
            if (v == null) { remove("refX"); remove("refY"); remove("refZ") }
            else { putFloat("refX", v.x); putFloat("refY", v.y); putFloat("refZ", v.z) }
        }

    val deviceName: String get() = Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun detectorConfig(): DetectorConfig {
        val base = DetectorConfig()
        return base.copy(
            referenceAxis = referenceAxis ?: base.referenceAxis,
            tooLongMs = Posture.entries.mapNotNull { s ->
                limitMin(s).takeIf { it > 0 }?.let { s to it * 60_000L }
            }.toMap(),
            tooLongRepeatMs = repeatMin.coerceAtLeast(1) * 60_000L,
        )
    }
}
