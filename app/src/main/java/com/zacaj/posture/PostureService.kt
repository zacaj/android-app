package com.zacaj.posture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.zacaj.posture.core.CalibrationSample
import com.zacaj.posture.core.DetectorEvent
import com.zacaj.posture.core.Posture
import com.zacaj.posture.core.PostureDetector
import com.zacaj.posture.core.TraceEvent
import com.zacaj.posture.core.Vec3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class Status(
    val running: Boolean = false,
    val posture: Posture = Posture.UNKNOWN,
    val since: Long = 0,
    val raw: Posture? = null,
    val tiltDeg: Float = Float.NaN,
    val motionStd: Float = 0f,
    val inPocket: Boolean = true,
    /** Calibration progress text, null when idle. */
    val calibration: String? = null,
)

class PostureService : Service(), SensorEventListener {
    private lateinit var settings: Settings
    private lateinit var sensors: SensorManager
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var recorder: TraceRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var detector = PostureDetector()
    private var lastStatusAt = 0L
    private var started = false
    private var hasProximity = false
    private var proximityNear = true

    // Calibration (sensor thread only): armed -> pocket in -> 3s delay -> record 5s -> save
    private var calibTarget: Posture? = null
    private var calibSamples: MutableList<Vec3>? = null
    private val calibStart = Runnable { beginCalibrationRecording() }
    private val calibFinish = Runnable { finishCalibration() }

    /** sensor timestamps are elapsedRealtimeNanos; convert to epoch ms */
    private val bootEpochMs get() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        sensors = getSystemService(SensorManager::class.java)
        thread = HandlerThread("posture-sensors").apply { start() }
        handler = Handler(thread.looper)
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Any start must promote to foreground promptly.
        ServiceCompat.startForeground(
            this, NOTIF_ONGOING, ongoingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        when (intent?.action) {
            ACTION_STOP -> {
                Feedback.show(this, "Tracking stopped")
                stopSelf(); return START_NOT_STICKY
            }
            ACTION_CONFIGURE -> {
                intent.getStringExtra("lanUrl")?.let { settings.lanUrl = it }
                if (intent.hasExtra("notifyOnChange")) {
                    settings.notifyOnChange = intent.getBooleanExtra("notifyOnChange", false)
                }
                reloadConfig()
            }
            ACTION_RELOAD -> {
                reloadConfig()
                if (started) Feedback.show(this, "Settings applied to running tracker")
            }
            ACTION_LABEL -> intent.getStringExtra(EXTRA_LABEL)?.let { l ->
                runCatching { Posture.valueOf(l) }.getOrNull()?.let { p ->
                    handler.post { recorder?.write(TraceEvent.Label(System.currentTimeMillis(), p)) }
                    Feedback.show(this, if (recorder != null || !started) "Labeled ${p.name.lowercase()}"
                        else "Labeled ${p.name.lowercase()} (recording is off, label not saved)")
                }
            }
            ACTION_CALIBRATE -> {
                val target = intent.getStringExtra(EXTRA_LABEL)
                    ?.let { runCatching { Posture.valueOf(it) }.getOrNull() } ?: Posture.STANDING
                handler.post { armCalibration(target) }
            }
            ACTION_CALIBRATE_CANCEL -> handler.post { cancelCalibration("Calibration cancelled") }
            ACTION_FLUSH -> handler.post {
                val had = recorder != null
                recorder?.close()
                UploadWorker.runNow(this)
                Feedback.show(this, if (had) "Trace closed; uploading…" else "Uploading pending traces…")
            }
        }
        if (!started) start()
        return START_STICKY
    }

    private fun start() {
        started = true
        Log.i(TAG, "starting")
        if (settings.recordTraces) recorder = TraceRecorder(UploadWorker.traceRoot(this))
        reloadConfig()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "posture:sensors").apply { acquire() }
        val periodUs = 40_000 // 25 Hz
        val batchUs = 2_000_000
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, periodUs, batchUs, handler)
        }
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensors.registerListener(this, it, periodUs, batchUs, handler)
        }
        // On-change sensor; prefer the wake-up variant so pocket changes aren't delayed by batching.
        val prox = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        hasProximity = prox != null
        if (prox != null) sensors.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL, 0, handler)
        else Feedback.show(this, "No proximity sensor; assuming always in pocket", error = true)
        UploadWorker.schedule(this)
        _status.value = _status.value.copy(running = true)
        Feedback.show(this, "Tracking started")
    }

    private fun reloadConfig() {
        handler.post {
            val old = detector
            detector = PostureDetector(settings.detectorConfig()).apply {
                stateMachine.restore(old.state, old.stateMachine.stateSince)
                onPocket(System.currentTimeMillis(), proximityNear)
            }
            Log.i(TAG, "config ${detector.config}")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "stopping")
        sensors.unregisterListener(this)
        handler.post {
            recorder?.close()
            thread.quitSafely()
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        _status.value = Status()
        super.onDestroy()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val t = bootEpochMs + e.timestamp / 1_000_000
        val v = if (e.values.size >= 3) Vec3(e.values[0], e.values[1], e.values[2]) else Vec3.ZERO
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                recorder?.write(TraceEvent.Accel(t, v))
                calibSamples?.add(v)
                val d = detector
                d.onAccel(t, v).forEach(::handle)
                if (t - lastStatusAt > 500) {
                    lastStatusAt = t
                    _status.value = _status.value.copy(
                        posture = d.state,
                        since = d.stateMachine.stateSince,
                        raw = d.lastRaw,
                        tiltDeg = d.classifier.tiltDeg,
                        motionStd = d.classifier.magnitudeStd,
                        inPocket = d.inPocket,
                    )
                }
            }
            Sensor.TYPE_GYROSCOPE -> recorder?.write(TraceEvent.Gyro(t, v))
            Sensor.TYPE_PROXIMITY -> {
                val near = e.values[0] < e.sensor.maximumRange
                if (near != proximityNear) {
                    proximityNear = near
                    Log.i(TAG, "proximity ${if (near) "near" else "far"}")
                    recorder?.write(TraceEvent.Pocket(t, near))
                    detector.onPocket(t, near).forEach(::handle)
                    onCalibrationProximity(near)
                }
            }
        }
    }

    // --- calibration ---

    private fun armCalibration(target: Posture) {
        cancelCalibration(null)
        calibTarget = target
        val what = target.name.lowercase()
        if (hasProximity && !proximityNear) {
            setCalibStatus("Armed ($what): put the phone in your pocket")
            Feedback.show(this, "Calibration armed: pocket the phone and $what still")
        } else {
            // Already covered (or no sensor): give a few seconds to get into position.
            setCalibStatus("Armed ($what): starting in 8s")
            Feedback.show(this, "Calibration starts in 8s — $what still")
            handler.postDelayed(calibStart, 8000)
        }
    }

    private fun onCalibrationProximity(near: Boolean) {
        val target = calibTarget ?: return
        val what = target.name.lowercase()
        if (near) {
            if (calibSamples == null) {
                handler.removeCallbacks(calibStart)
                handler.postDelayed(calibStart, 3000)
                setCalibStatus("In pocket: recording in 3s ($what still)")
            }
        } else {
            handler.removeCallbacks(calibStart)
            if (calibSamples != null) {
                handler.removeCallbacks(calibFinish)
                calibSamples = null
                Haptics.failure(this)
            }
            setCalibStatus("Armed ($what): put the phone in your pocket")
        }
    }

    private fun beginCalibrationRecording() {
        calibTarget ?: return
        calibSamples = ArrayList()
        Haptics.start(this)
        setCalibStatus("Recording ${calibTarget!!.name.lowercase()}… hold still")
        handler.postDelayed(calibFinish, 5000)
    }

    private fun finishCalibration() {
        val target = calibTarget ?: return
        val sample = CalibrationSample.of(calibSamples ?: emptyList())
        calibSamples = null
        calibTarget = null
        val g = sample.gravity
        val now = System.currentTimeMillis()
        if (!sample.ok) {
            Haptics.failure(this)
            recorder?.write(TraceEvent.Note(now, "calibration $target failed std=${sample.magnitudeStd} n=${sample.count}"))
            setCalibStatus(null)
            Feedback.show(this, "Calibration failed: moved too much (motion %.2f), try again".format(sample.magnitudeStd), error = true)
            return
        }
        when (target) {
            Posture.SITTING -> settings.sittingAxis = g
            else -> settings.referenceAxis = g
        }
        recorder?.write(TraceEvent.Note(now, "calibrated $target ${g.x} ${g.y} ${g.z}"))
        recorder?.write(TraceEvent.Label(now, target))
        Haptics.success(this)
        setCalibStatus(null)
        Feedback.show(this, "Calibrated ${target.name.lowercase()}: (%.1f, %.1f, %.1f)".format(g.x, g.y, g.z))
        reloadConfig()
    }

    private fun cancelCalibration(msg: String?) {
        handler.removeCallbacks(calibStart)
        handler.removeCallbacks(calibFinish)
        val wasActive = calibTarget != null
        calibTarget = null
        calibSamples = null
        setCalibStatus(null)
        if (msg != null && wasActive) Feedback.show(this, msg)
    }

    private fun setCalibStatus(s: String?) {
        _status.value = _status.value.copy(calibration = s)
    }

    private fun handle(ev: DetectorEvent) {
        Log.i(TAG, "event $ev")
        val json = JSONObject().put("t", ev.tMs).put("device", settings.deviceName)
        when (ev) {
            is DetectorEvent.StateChanged -> {
                recorder?.write(TraceEvent.State(ev.tMs, ev.to))
                json.put("type", "state").put("from", ev.from.name).put("to", ev.to.name).put("since", ev.since)
                getSystemService(NotificationManager::class.java).notify(NOTIF_ONGOING, ongoingNotification(ev.to))
                if (settings.notifyOnChange && ev.from != Posture.UNKNOWN) {
                    alert("Now ${ev.to.name.lowercase()}", "was ${ev.from.name.lowercase()}")
                }
            }
            is DetectorEvent.TooLong -> {
                json.put("type", "too_long").put("state", ev.state.name).put("durationMs", ev.durationMs)
                alert("${ev.state.name.lowercase().replaceFirstChar { it.uppercase() }} for ${ev.durationMs / 60_000} min", "Time to change it up")
            }
        }
        if (settings.lanUrl.isNotBlank()) Net.postAsync(this, "${settings.lanUrl}/event", json.toString())
    }

    private fun alert(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_posture)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n)
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun labelAction(p: Posture) = NotificationCompat.Action(
        0, p.name.lowercase(),
        PendingIntent.getService(
            this, p.ordinal + 1, intent(this, ACTION_LABEL).putExtra(EXTRA_LABEL, p.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    private fun ongoingNotification(p: Posture = detector.state): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_posture)
            .setContentTitle("Posture: ${p.name.lowercase()}")
            .setContentText("Tap a button to label what you're actually doing")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(labelAction(Posture.SITTING))
            .addAction(labelAction(Posture.STANDING))
            .addAction(labelAction(Posture.WALKING))
            .build()

    companion object {
        const val TAG = "Posture"
        const val ACTION_START = "com.zacaj.posture.START"
        const val ACTION_STOP = "com.zacaj.posture.STOP"
        const val ACTION_CONFIGURE = "com.zacaj.posture.CONFIGURE"
        const val ACTION_RELOAD = "com.zacaj.posture.RELOAD"
        const val ACTION_LABEL = "com.zacaj.posture.LABEL"
        const val ACTION_CALIBRATE = "com.zacaj.posture.CALIBRATE"
        const val ACTION_FLUSH = "com.zacaj.posture.FLUSH"
        const val ACTION_CALIBRATE_CANCEL = "com.zacaj.posture.CALIBRATE_CANCEL"
        const val EXTRA_LABEL = "label"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_ALERT = 2

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status

        fun intent(ctx: Context, action: String) = Intent(ctx, PostureService::class.java).setAction(action)

        fun send(ctx: Context, action: String, extras: Intent.() -> Unit = {}) {
            ctx.startForegroundService(intent(ctx, action).apply(extras))
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Status", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
