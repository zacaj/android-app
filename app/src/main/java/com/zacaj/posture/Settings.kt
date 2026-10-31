package com.zacaj.posture

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.zacaj.posture.core.Calibration
import com.zacaj.posture.core.DetectorConfig
import kotlinx.coroutines.flow.MutableStateFlow
import com.zacaj.posture.core.Posture
import com.zacaj.posture.core.Vec3

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Base URL of the LAN listener, e.g. http://192.168.1.10:8765 (blank = off). */
    var lanUrl: String
        get() = p.getString("lanUrl", "")!!
        set(v) = p.edit { putString("lanUrl", v.trim().trimEnd('/')) }

    /** Only POST to the LAN listener while on Wi-Fi/Ethernet. */
    var lanWifiOnly: Boolean
        get() = p.getBoolean("lanWifiOnly", true)
        set(v) = p.edit { putBoolean("lanWifiOnly", v) }

    /** Tracking was running when last seen; boot/app-update restarts it only if so. */
    var trackingEnabled: Boolean
        get() = p.getBoolean("trackingEnabled", false)
        set(v) = p.edit { putBoolean("trackingEnabled", v) }

    var notifyOnChange: Boolean
        get() = p.getBoolean("notifyOnChange", false)
        set(v) = p.edit { putBoolean("notifyOnChange", v) }

    /** Load limits in minutes (see LoadTracker), 0 = off. */
    fun limitMin(posture: Posture): Int =
        p.getInt("limit_$posture", if (posture == Posture.SITTING || posture == Posture.STANDING) 45 else 0)
    fun setLimitMin(posture: Posture, v: Int) = p.edit { putInt("limit_$posture", v) }

    /** Minutes between heartbeat POSTs to the LAN listener; 0 disables. */
    var heartbeatMin: Int
        get() = p.getInt("heartbeatMin", 5)
        set(v) = p.edit { putInt("heartbeatMin", v) }

    /** Alert after this many minutes of continuous phone use (out of pocket, screen on); 0 disables. */
    var phoneUseLimitMin: Int
        get() = p.getInt("phoneUseLimitMin", 30)
        set(v) = p.edit { putInt("phoneUseLimitMin", v) }

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

    var sittingAxis: Vec3?
        get() = if (!p.contains("sitX")) null
        else Vec3(p.getFloat("sitX", 0f), p.getFloat("sitY", 0f), p.getFloat("sitZ", 1f))
        set(v) = p.edit {
            if (v == null) { remove("sitX"); remove("sitY"); remove("sitZ") }
            else { putFloat("sitX", v.x); putFloat("sitY", v.y); putFloat("sitZ", v.z) }
        }

    /**
     * All calibrated orientations, oldest first. Stored as "POSTURE:t:x,y,z;…"; first read migrates
     * the old single standing/sitting axes.
     */
    var calibrations: List<Calibration>
        get() {
            val raw = p.getString("calibrations", null)
                ?: return listOfNotNull(
                    referenceAxis?.let { Calibration(Posture.STANDING, it, 0) },
                    sittingAxis?.let { Calibration(Posture.SITTING, it, 0) },
                )
            return raw.split(';').filter { it.isNotBlank() }.mapNotNull { e ->
                runCatching {
                    val (ps, ts, vs) = e.split(':')
                    val (x, y, z) = vs.split(',').map { it.toFloat() }
                    Calibration(Posture.valueOf(ps), Vec3(x, y, z), ts.toLong())
                }.getOrNull()
            }
        }
        set(v) {
            p.edit {
                putString("calibrations", v.joinToString(";") { "${it.posture}:${it.tMs}:${it.gravity.x},${it.gravity.y},${it.gravity.z}" })
            }
            calibrationsFlow.value = v
        }

    val deviceName: String get() = Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun detectorConfig(): DetectorConfig {
        val base = DetectorConfig()
        return base.copy(
            calibrations = calibrations,
            tooLongMs = Posture.entries.mapNotNull { s ->
                limitMin(s).takeIf { it > 0 }?.let { s to it * 60_000L }
            }.toMap(),
            tooLongRepeatMs = repeatMin.coerceAtLeast(1) * 60_000L,
        )
    }

    companion object {
        /** Latest calibrations for the UI; null until first written this process. */
        val calibrationsFlow = MutableStateFlow<List<Calibration>?>(null)
        /** Opposite-posture calibrations closer than this get flagged. */
        const val TOO_CLOSE_DEG = 20f
    }
}
