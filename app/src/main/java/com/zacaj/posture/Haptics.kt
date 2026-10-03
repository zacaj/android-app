package com.zacaj.posture

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager

/** Pocket-friendly cues for calibration: vibration plus a short tone. */
object Haptics {
    fun start(ctx: Context) = cue(ctx, longArrayOf(0, 150), ToneGenerator.TONE_PROP_BEEP)
    fun success(ctx: Context) = cue(ctx, longArrayOf(0, 120, 120, 120), ToneGenerator.TONE_PROP_ACK)
    fun failure(ctx: Context) = cue(ctx, longArrayOf(0, 700), ToneGenerator.TONE_PROP_NACK)

    private fun cue(ctx: Context, pattern: LongArray, tone: Int) {
        val vm = ctx.getSystemService(VibratorManager::class.java)
        if (vm != null) vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
            .vibrate(VibrationEffect.createWaveform(pattern, -1))
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(tone, 300)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 600)
        }
    }
}
