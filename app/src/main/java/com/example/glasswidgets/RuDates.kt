package com.example.glasswidgets

import java.time.DayOfWeek

/**
 * Русские названия месяцев и дней недели.
 * Зашиты в код, чтобы виджет выглядел одинаково независимо от языка системы
 * (java.time выдаёт «ноября» вместо «Ноябрь» без явных локалей).
 */
object RuDates {

    private val MONTHS = arrayOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
    )

    /** [month] — номер месяца от 1 до 12. Возвращает «Ноябрь». */
    fun month(month: Int): String = MONTHS[month - 1]

    /** Возвращает день недели строчными буквами: «пятница». */
    fun weekday(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "понедельник"
        DayOfWeek.TUESDAY -> "вторник"
        DayOfWeek.WEDNESDAY -> "среда"
        DayOfWeek.THURSDAY -> "четверг"
        DayOfWeek.FRIDAY -> "пятница"
        DayOfWeek.SATURDAY -> "суббота"
        DayOfWeek.SUNDAY -> "воскресенье"
    }
}
