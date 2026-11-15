package com.example.glasswidgets

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Одно событие календаря на сегодня. [begin] — начало в миллисекундах UTC. */
data class CalendarEvent(
    val title: String,
    val begin: Long,
    val allDay: Boolean
)

/** Чтение событий из системного календаря через CalendarContract. */
object CalendarUtils {

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun hasCalendarPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * До [limit] ближайших событий на сегодня: сначала события со временем
     * (ещё не закончившиеся, по возрастанию начала), затем события на весь день.
     * Без разрешения READ_CALENDAR возвращает пустой список.
     */
    fun todayEvents(context: Context, limit: Int): List<CalendarEvent> {
        if (!hasCalendarPermission(context)) return emptyList()

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val timed = ArrayList<CalendarEvent>()
        val allDay = ArrayList<CalendarEvent>()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )

        try {
            // Окно шире суток: события «на весь день» хранятся в UTC и в часовых поясах
            // со сдвигом могут начинаться чуть раньше/позже границ локального дня.
            val cursor = CalendarContract.Instances.query(
                context.contentResolver,
                projection,
                dayStart - DAY_MS,
                dayEnd + DAY_MS
            )
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0)?.trim().orEmpty()
                    val begin = c.getLong(1)
                    val end = c.getLong(2)
                    val isAllDay = c.getInt(3) == 1

                    if (isAllDay) {
                        // Даты all-day событий — календарные даты в UTC, end не включительно.
                        val startDate = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                        val endDate = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                        if (!today.isBefore(startDate) && today.isBefore(endDate)) {
                            allDay.add(CalendarEvent(title, begin, true))
                        }
                    } else if (end > now && end > dayStart && begin < dayEnd) {
                        timed.add(CalendarEvent(title, begin, false))
                    }
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        } catch (e: Exception) {
            // Провайдер календаря недоступен — показываем то, что успели собрать.
        }

        timed.sortBy { it.begin }
        return (timed + allDay).take(limit)
    }

    /** Строка для виджета: «14:30 Встреча» или просто название для событий на весь день. */
    fun describe(context: Context, event: CalendarEvent): String {
        val title = event.title.ifEmpty { context.getString(R.string.event_no_title) }
        if (event.allDay) return title
        val time = Instant.ofEpochMilli(event.begin)
            .atZone(ZoneId.systemDefault())
            .format(TIME_FORMAT)
        return "$time $title"
    }
}
