package com.example.glasswidgets

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** Текущая погода: температура в °C, код WMO, день/ночь и время загрузки. */
data class WeatherInfo(
    val temperature: Int,
    val weatherCode: Int,
    val isDay: Boolean,
    val updatedAt: Long
)

/** Результат попытки обновить погоду. */
enum class WeatherStatus {
    /** Данные актуальны (загружены сейчас или лежат в свежем кэше). */
    OK,

    /** Нет разрешения ACCESS_COARSE_LOCATION. */
    NO_PERMISSION,

    /** Разрешение есть, но система не знает последних координат. */
    NO_LOCATION,

    /** Нет сети или Open-Meteo вернул ошибку. */
    FAILED
}

/**
 * Загрузка погоды из бесплатного API Open-Meteo (ключ не нужен).
 *
 * Координаты берутся из LocationManager (последняя известная позиция) и запоминаются.
 * Начиная с Android 10 фоновые приложения не получают позицию, поэтому, когда виджет
 * обновляется в фоне, используются координаты, сохранённые при последнем открытии
 * приложения (кнопка «Обновить виджеты»).
 */
object WeatherUtils {

    private const val PREFS = "glass_widgets_weather"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_TEMP = "temp"
    private const val KEY_CODE = "code"
    private const val KEY_IS_DAY = "is_day"
    private const val KEY_UPDATED = "updated_at"

    /** Кэш считается свежим 25 минут (виджет обновляется раз в 30). */
    private const val CACHE_TTL_MS = 25L * 60L * 1000L

    private const val TIMEOUT_MS = 8_000

    private val PROVIDERS = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
        "fused",
        LocationManager.GPS_PROVIDER
    )

    // ------------------------------------------------------------------ публичный API

    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Последняя сохранённая погода или null, если её ещё не загружали. */
    fun cached(context: Context): WeatherInfo? {
        val p = prefs(context)
        if (!p.contains(KEY_UPDATED)) return null
        return WeatherInfo(
            temperature = p.getInt(KEY_TEMP, 0),
            weatherCode = p.getInt(KEY_CODE, 3),
            isDay = p.getBoolean(KEY_IS_DAY, true),
            updatedAt = p.getLong(KEY_UPDATED, 0L)
        )
    }

    /** true, если кэш моложе 25 минут. */
    fun isFresh(context: Context): Boolean {
        val info = cached(context) ?: return false
        return System.currentTimeMillis() - info.updatedAt < CACHE_TTL_MS
    }

    /**
     * Обновляет кэш погоды. Выполняет сетевой запрос — вызывать только из фонового потока.
     * [force] = true игнорирует свежесть кэша.
     */
    fun refresh(context: Context, force: Boolean = false): WeatherStatus {
        if (!force && isFresh(context)) return WeatherStatus.OK

        val coordinates = resolveCoordinates(context)
        if (coordinates == null) {
            return if (hasLocationPermission(context)) {
                WeatherStatus.NO_LOCATION
            } else {
                WeatherStatus.NO_PERMISSION
            }
        }

        return try {
            val info = download(coordinates.first, coordinates.second)
            save(context, info)
            WeatherStatus.OK
        } catch (e: Exception) {
            WeatherStatus.FAILED
        }
    }

    /** Ресурс иконки по коду погоды WMO (https://open-meteo.com/en/docs). */
    fun iconRes(info: WeatherInfo?): Int {
        if (info == null) return R.drawable.ic_weather_cloudy
        return when (info.weatherCode) {
            0, 1 -> if (info.isDay) R.drawable.ic_weather_clear_day else R.drawable.ic_weather_clear_night
            2 -> if (info.isDay) R.drawable.ic_weather_partly_day else R.drawable.ic_weather_partly_night
            3 -> R.drawable.ic_weather_cloudy
            45, 48 -> R.drawable.ic_weather_fog
            in 51..67, in 80..82 -> R.drawable.ic_weather_rain
            in 71..77, 85, 86 -> R.drawable.ic_weather_snow
            95, 96, 99 -> R.drawable.ic_weather_storm
            else -> R.drawable.ic_weather_cloudy
        }
    }

    /** «3°», «-5°» или «--°», если данных ещё нет. */
    fun temperatureText(info: WeatherInfo?): String =
        if (info == null) "--°" else "${info.temperature}°"

    // ------------------------------------------------------------------ координаты

    private fun resolveCoordinates(context: Context): Pair<Double, Double>? {
        // Без разрешения ничего не используем, даже сохранённые координаты.
        if (!hasLocationPermission(context)) return null

        val p = prefs(context)
        val location = lastKnownLocation(context)
        if (location != null) {
            p.edit()
                .putString(KEY_LAT, location.latitude.toString())
                .putString(KEY_LON, location.longitude.toString())
                .apply()
            return location.latitude to location.longitude
        }

        // Фоновый режим (Android 10+): берём последние сохранённые координаты.
        val lat = p.getString(KEY_LAT, null)?.toDoubleOrNull()
        val lon = p.getString(KEY_LON, null)?.toDoubleOrNull()
        return if (lat != null && lon != null) lat to lon else null
    }

    private fun lastKnownLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        var best: Location? = null
        for (provider in PROVIDERS) {
            val location = safeLastKnown(manager, provider) ?: continue
            val current = best
            if (current == null || location.time > current.time) best = location
        }
        return best
    }

    @SuppressLint("MissingPermission") // разрешение проверено в resolveCoordinates()
    private fun safeLastKnown(manager: LocationManager, provider: String): Location? =
        try {
            manager.getLastKnownLocation(provider)
        } catch (e: Exception) {
            // SecurityException (GPS при одном COARSE), IllegalArgumentException (нет провайдера)
            null
        }

    // ------------------------------------------------------------------ сеть

    @Throws(IOException::class)
    private fun download(lat: Double, lon: Double): WeatherInfo {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,weather_code,is_day&timezone=auto",
                lat,
                lon
            )
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Open-Meteo: HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val current = JSONObject(body).getJSONObject("current")
            return WeatherInfo(
                temperature = current.getDouble("temperature_2m").roundToInt(),
                weatherCode = current.getInt("weather_code"),
                isDay = current.optInt("is_day", 1) == 1,
                updatedAt = System.currentTimeMillis()
            )
        } finally {
            connection.disconnect()
        }
    }

    // ------------------------------------------------------------------ хранилище

    private fun save(context: Context, info: WeatherInfo) {
        prefs(context).edit()
            .putInt(KEY_TEMP, info.temperature)
            .putInt(KEY_CODE, info.weatherCode)
            .putBoolean(KEY_IS_DAY, info.isDay)
            .putLong(KEY_UPDATED, info.updatedAt)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
