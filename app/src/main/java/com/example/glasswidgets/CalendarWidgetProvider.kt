package com.example.glasswidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.CalendarContract
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.YearMonth

/**
 * Виджет 2 (4x2): число/день недели, погода, события на сегодня и сетка месяца.
 *
 * Переключение месяцев: кнопки «<» и «>» отправляют broadcast этому же ресивер-классу
 * через PendingIntent; смещение месяца хранится в SharedPreferences для каждого виджета.
 * На нажатие кнопок перерисовывается только правая колонка (partiallyUpdateAppWidget).
 */
class CalendarWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PREV_MONTH, ACTION_NEXT_MONTH -> shiftMonth(context, intent)

            // Смена даты, времени, часового пояса или языка: возвращаемся к текущему месяцу.
            // Для TIME_SET константы в Intent нет — сравниваем по строке (как в манифесте).
            Intent.ACTION_DATE_CHANGED,
            "android.intent.action.TIME_SET",
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
                clearOffsets(context)
                updateAll(context)
            }

            // APPWIDGET_UPDATE, APPWIDGET_DELETED и т.д.
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 1. Мгновенно рисуем виджет из кэша погоды и актуальных событий.
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, id))
        }

        // 2. Если погода устарела — обновляем её в фоне и перерисовываем виджет.
        if (WeatherUtils.isFresh(context)) return
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                if (WeatherUtils.refresh(appContext) == WeatherStatus.OK) {
                    updateAll(appContext)
                }
            } catch (e: Exception) {
                // Погода — необязательная часть: при ошибке остаётся кэш.
            } finally {
                pending?.finish()
            }
        }.start()
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = prefs(context).edit()
        for (id in appWidgetIds) editor.remove(offsetKey(id))
        editor.apply()
    }

    private fun shiftMonth(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val delta = if (intent.action == ACTION_NEXT_MONTH) 1 else -1
        val offset = (getOffset(context, widgetId) + delta).coerceIn(-MAX_OFFSET, MAX_OFFSET)
        setOffset(context, widgetId, offset)

        val views = RemoteViews(context.packageName, R.layout.widget_calendar)
        bindMonth(context, views, widgetId, LocalDate.now())
        AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(widgetId, views)
    }

    companion object {

        const val ACTION_PREV_MONTH = "com.example.glasswidgets.action.PREV_MONTH"
        const val ACTION_NEXT_MONTH = "com.example.glasswidgets.action.NEXT_MONTH"

        private const val PREFS = "glass_widgets_calendar"
        private const val MAX_OFFSET = 600 // ±50 лет

        private const val REQUEST_OPEN_CALENDAR = 20
        private const val REQUEST_OPEN_APP = 21
        private const val REQUEST_NAV_BASE = 1000

        private const val MAX_EVENTS = 2

        /** 42 ячейки сетки: 6 недель по 7 дней, начиная с понедельника. */
        private val CELL_IDS = intArrayOf(
            R.id.cell_0, R.id.cell_1, R.id.cell_2, R.id.cell_3, R.id.cell_4, R.id.cell_5, R.id.cell_6,
            R.id.cell_7, R.id.cell_8, R.id.cell_9, R.id.cell_10, R.id.cell_11, R.id.cell_12, R.id.cell_13,
            R.id.cell_14, R.id.cell_15, R.id.cell_16, R.id.cell_17, R.id.cell_18, R.id.cell_19, R.id.cell_20,
            R.id.cell_21, R.id.cell_22, R.id.cell_23, R.id.cell_24, R.id.cell_25, R.id.cell_26, R.id.cell_27,
            R.id.cell_28, R.id.cell_29, R.id.cell_30, R.id.cell_31, R.id.cell_32, R.id.cell_33, R.id.cell_34,
            R.id.cell_35, R.id.cell_36, R.id.cell_37, R.id.cell_38, R.id.cell_39, R.id.cell_40, R.id.cell_41
        )

        /** Перерисовывает все размещённые виджеты календаря. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, CalendarWidgetProvider::class.java)
            )
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context, id))
            }
        }

        // -------------------------------------------------------------- сборка RemoteViews

        private fun buildViews(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_calendar)
            val today = LocalDate.now()
            bindToday(context, views, today)
            bindWeather(context, views)
            bindEvents(context, views)
            bindMonth(context, views, widgetId, today)
            return views
        }

        /** Левая колонка, верх: «15 · воскресенье / Ноябрь». */
        private fun bindToday(context: Context, views: RemoteViews, today: LocalDate) {
            views.setTextViewText(R.id.day_number, today.dayOfMonth.toString())
            views.setTextViewText(R.id.day_of_week, RuDates.weekday(today.dayOfWeek))
            views.setTextViewText(R.id.day_month, RuDates.month(today.monthValue))

            // Нажатие на левую колонку открывает приложение «Календарь».
            views.setOnClickPendingIntent(R.id.left_column, calendarAppIntent(context))
        }

        /** Левая колонка, середина: иконка погоды и температура из кэша. */
        private fun bindWeather(context: Context, views: RemoteViews) {
            val weather = WeatherUtils.cached(context)
            views.setImageViewResource(R.id.weather_icon, WeatherUtils.iconRes(weather))
            views.setTextViewText(R.id.weather_temp, WeatherUtils.temperatureText(weather))
        }

        /** Левая колонка, низ: до двух ближайших событий или «Ближайших событий нет». */
        private fun bindEvents(context: Context, views: RemoteViews) {
            val permitted = CalendarUtils.hasCalendarPermission(context)
            val events = if (permitted) CalendarUtils.todayEvents(context, MAX_EVENTS) else emptyList()
            val eventViews = intArrayOf(R.id.event_1, R.id.event_2)

            for (i in eventViews.indices) {
                val event = events.getOrNull(i)
                if (event != null) {
                    views.setTextViewText(eventViews[i], CalendarUtils.describe(context, event))
                    views.setViewVisibility(eventViews[i], View.VISIBLE)
                } else {
                    views.setViewVisibility(eventViews[i], View.GONE)
                }
            }

            if (events.isEmpty()) {
                views.setTextViewText(
                    R.id.events_empty,
                    context.getString(if (permitted) R.string.no_events else R.string.no_calendar_access)
                )
                views.setViewVisibility(R.id.events_empty, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.events_empty, View.GONE)
            }

            // Без доступа к календарю нажатие ведёт на экран выдачи разрешений.
            views.setOnClickPendingIntent(
                R.id.events_empty,
                if (permitted) calendarAppIntent(context) else openAppIntent(context)
            )
        }

        /** Правая колонка: название месяца, кнопки < > и сетка 6x7. */
        private fun bindMonth(
            context: Context,
            views: RemoteViews,
            widgetId: Int,
            today: LocalDate
        ) {
            val shown = YearMonth.from(today).plusMonths(getOffset(context, widgetId).toLong())

            // Год показываем только когда он отличается от текущего.
            val title = if (shown.year == today.year) {
                RuDates.month(shown.monthValue)
            } else {
                "${RuDates.month(shown.monthValue)} ${shown.year}"
            }
            views.setTextViewText(R.id.month_title, title)

            // Неделя начинается с понедельника (dayOfWeek.value: пн = 1 ... вс = 7).
            val first = shown.atDay(1)
            val gridStart = first.minusDays((first.dayOfWeek.value - 1).toLong())

            val colorPrimary = context.getColor(R.color.widget_text_primary)
            val colorOtherMonth = context.getColor(R.color.widget_other_month)

            for (i in CELL_IDS.indices) {
                val date = gridStart.plusDays(i.toLong())
                val cell = CELL_IDS[i]
                val isToday = date == today
                val inShownMonth = YearMonth.from(date) == shown

                views.setTextViewText(cell, date.dayOfMonth.toString())
                views.setTextColor(cell, if (isToday || inShownMonth) colorPrimary else colorOtherMonth)
                // Подложка только у сегодняшнего дня; 0 снимает фон с остальных ячеек.
                views.setInt(cell, "setBackgroundResource", if (isToday) R.drawable.bg_today else 0)
            }

            views.setOnClickPendingIntent(
                R.id.btn_prev,
                navIntent(context, widgetId, ACTION_PREV_MONTH, 0)
            )
            views.setOnClickPendingIntent(
                R.id.btn_next,
                navIntent(context, widgetId, ACTION_NEXT_MONTH, 1)
            )
        }

        // -------------------------------------------------------------- PendingIntent

        private fun navIntent(
            context: Context,
            widgetId: Int,
            action: String,
            direction: Int
        ): PendingIntent {
            val intent = Intent(context, CalendarWidgetProvider::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_NAV_BASE + widgetId * 2 + direction, // свой requestCode у каждой кнопки
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun calendarAppIntent(context: Context): PendingIntent {
            val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN_CALENDAR,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN_APP,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // -------------------------------------------------------------- смещение месяца

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun offsetKey(widgetId: Int) = "offset_$widgetId"

        private fun getOffset(context: Context, widgetId: Int): Int =
            prefs(context).getInt(offsetKey(widgetId), 0)

        private fun setOffset(context: Context, widgetId: Int, offset: Int) {
            prefs(context).edit().putInt(offsetKey(widgetId), offset).apply()
        }

        private fun clearOffsets(context: Context) {
            prefs(context).edit().clear().apply()
        }
    }
}
