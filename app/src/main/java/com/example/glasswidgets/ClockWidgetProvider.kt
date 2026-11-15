package com.example.glasswidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.widget.RemoteViews

/**
 * Виджет 1 (2x1): время и краткая дата.
 *
 * Время и дату рисуют два TextClock прямо в разметке — система сама обновляет их
 * каждую минуту, поэтому ни фоновых сервисов, ни AlarmManager, ни расхода батареи нет.
 * Провайдеру остаётся только повесить действие на нажатие.
 */
class ClockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    companion object {

        private const val REQUEST_OPEN_CLOCK = 10

        /** Перерисовывает все размещённые виджеты часов. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ClockWidgetProvider::class.java))
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context))
            }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_clock)

            // Нажатие открывает будильники в приложении «Часы».
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_OPEN_CLOCK,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.clock_root, pendingIntent)
            return views
        }
    }
}
