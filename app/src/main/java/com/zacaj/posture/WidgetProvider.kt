package com.zacaj.posture

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.zacaj.posture.core.Posture

/** 1x1 widget; the running service pushes updates, so no periodic refresh is configured. */
class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            val settings = Settings(ctx)
            val bmp = MiniCharts.widget(
                PostureService.status.value,
                settings.limitMin(Posture.SITTING), settings.limitMin(Posture.STANDING),
            )
            val views = RemoteViews(ctx.packageName, R.layout.widget).apply {
                setImageViewBitmap(R.id.widget_image, bmp)
                setOnClickPendingIntent(
                    R.id.widget_image,
                    PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
                )
            }
            mgr.updateAppWidget(ids, views)
        }
    }
}
