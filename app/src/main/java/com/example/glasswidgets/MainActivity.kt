package com.example.glasswidgets

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * Минималистичный экран: выдача двух разрешений одной кнопкой и ручное обновление виджетов.
 * Работает на «голом» android.app.Activity — без AppCompat и других библиотек.
 */
class MainActivity : Activity() {

    private lateinit var statusCalendar: TextView
    private lateinit var statusLocation: TextView
    private lateinit var statusMedia: TextView
    private lateinit var updateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusCalendar = findViewById(R.id.status_calendar)
        statusLocation = findViewById(R.id.status_location)
        statusMedia = findViewById(R.id.status_media)
        updateButton = findViewById(R.id.btn_update)

        findViewById<Button>(R.id.btn_grant).setOnClickListener { requestMissingPermissions() }
        findViewById<Button>(R.id.btn_grant_media)
            .setOnClickListener { openNotificationListenerSettings() }
        updateButton.setOnClickListener { refreshWidgets(showResult = true) }
    }

    override fun onResume() {
        super.onResume()
        updateStatuses()
    }

    // -------------------------------------------------------------- разрешения

    /** Один клик — один системный запрос сразу на оба недостающих разрешения. */
    private fun requestMissingPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter { !isGranted(it) }
        if (missing.isEmpty()) {
            Toast.makeText(this, R.string.toast_all_granted, Toast.LENGTH_SHORT).show()
            return
        }
        requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        updateStatuses()

        // Отказ с «больше не спрашивать» (или повторный отказ): системный диалог уже не покажется.
        val blocked = permissions.indices.any { i ->
            grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(permissions[i])
        }
        if (blocked) {
            Toast.makeText(this, R.string.toast_open_settings, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }

        // Приложение на экране — самое время сохранить координаты и подтянуть погоду.
        refreshWidgets(showResult = !blocked)
    }

    private fun isGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun updateStatuses() {
        bindStatus(statusCalendar, isGranted(Manifest.permission.READ_CALENDAR))
        bindStatus(statusLocation, isGranted(Manifest.permission.ACCESS_COARSE_LOCATION))
        bindStatus(statusMedia, isNotificationListenerGranted())
    }

    // -------------------------------------------------------------- музыкальный виджет

    /** Доступ к уведомлениям — не runtime-разрешение, его выдают в системных настройках. */
    private fun isNotificationListenerGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return enabled.split(":").any { it.contains(packageName) }
    }

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun bindStatus(view: TextView, granted: Boolean) {
        view.setText(if (granted) R.string.status_granted else R.string.status_denied)
        view.setTextColor(getColor(if (granted) R.color.status_granted else R.color.status_denied))
    }

    // -------------------------------------------------------------- обновление виджетов

    private fun refreshWidgets(showResult: Boolean) {
        updateButton.isEnabled = false
        updateButton.setText(R.string.btn_updating)

        val appContext = applicationContext
        Thread {
            // Сеть и чтение координат — вне главного потока. force = true: игнорируем кэш.
            val status = try {
                WeatherUtils.refresh(appContext, force = true)
            } catch (e: Exception) {
                WeatherStatus.FAILED
            }
            ClockWidgetProvider.updateAll(appContext)
            CalendarWidgetProvider.updateAll(appContext)
            MusicWidgetProvider.updateIdle(appContext)

            runOnUiThread {
                updateButton.isEnabled = true
                updateButton.setText(R.string.btn_update_widgets)
                if (showResult) {
                    Toast.makeText(appContext, messageFor(status), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun messageFor(status: WeatherStatus): Int = when (status) {
        WeatherStatus.OK -> R.string.toast_updated
        WeatherStatus.NO_PERMISSION -> R.string.toast_updated_no_location
        WeatherStatus.NO_LOCATION -> R.string.toast_updated_no_fix
        WeatherStatus.FAILED -> R.string.toast_updated_no_weather
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 100
        val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
