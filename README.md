# Glass Widgets

Два виджета рабочего стола в стиле Glassmorphism (Kotlin, minSdk 26, targetSdk 35):

* **Часы и дата (2x1)** — два `TextClock`, обновляются системой без фоновых процессов.
* **Календарь + погода + события (4x2)** — число и день недели, погода Open-Meteo,
  события на сегодня из `CalendarContract`, сетка месяца 6x7 с переключением `<` `>`.

## Сборка
1. Откройте папку проекта в Android Studio (Ladybug или новее, JDK 17) — Gradle 8.9 подтянется по
   `gradle/wrapper/gradle-wrapper.properties`. Из консоли: `gradle wrapper` и затем `./gradlew assembleDebug`.
2. Запустите приложение, нажмите «Выдать разрешения», затем «Обновить виджеты».
3. Долгое нажатие на рабочем столе → «Виджеты» → «Glass Widgets».

## Структура
```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/glasswidgets/
│   ├── MainActivity.kt            экран разрешений и ручное обновление
│   ├── ClockWidgetProvider.kt     виджет 1
│   ├── CalendarWidgetProvider.kt  виджет 2 (рендер, навигация по месяцам)
│   ├── WeatherUtils.kt            координаты, Open-Meteo, кэш, иконки
│   ├── CalendarUtils.kt           события на сегодня
│   └── RuDates.kt                 русские названия месяцев и дней
└── res/ layout, drawable, xml (appwidget-provider), values, mipmap-anydpi-v26
```

## Важные детали
* Погода в фоне: с Android 10 фоновые приложения не читают геопозицию, поэтому координаты
  сохраняются при открытии приложения (кнопка «Обновить виджеты») и дальше используются виджетом.
* Календарь обновляется раз в 30 минут (`updatePeriodMillis`) и при смене даты/часового пояса.
* Дата в виджете часов (`TextClock`) использует язык системы; календарный виджет всегда на русском.
