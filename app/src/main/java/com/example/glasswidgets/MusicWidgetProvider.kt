package com.example.glasswidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews

/**
 * Виджет 3 (4x1): музыкальный плеер.
 *
 * Данные приходят от MediaListenerService (NotificationListenerService), который
 * подписан на MediaSession всех плееров. Этот класс только рисует виджет и
 * отправляет команды плееру:
 *  - кнопки prev/play-pause/next → MediaController.TransportControls;
 *  - обложка и текстовый блок → sessionActivity активного плеера;
 *  - если плеера нет — открывается системный интент музыкального приложения.
 */
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val controller = MediaListenerService.currentController
        val views = buildViews(context, controller)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PREV -> sendCommand(context) { it.skipToPrevious() }
            ACTION_NEXT -> sendCommand(context) { it.skipToNext() }
            ACTION_PLAY_PAUSE -> {
                val controller = MediaListenerService.currentController ?: return
                val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                sendCommand(context) { if (playing) it.pause() else it.play() }
            }
        }
    }

    /** Отправляет команду активной сессии и сразу перерисовывает виджеты. */
    private fun sendCommand(
        context: Context,
        command: (MediaController.TransportControls) -> Unit
    ) {
        val controller = MediaListenerService.currentController
        if (controller == null) {
            updateIdle(context)
            return
        }
        try {
            command(controller.transportControls)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось отправить команду плееру", e)
        }
        // Колбэк от сессии может прийти с задержкой — обновляем немедленно.
        updateAll(context, controller)
    }

    companion object {

        private const val TAG = "MusicWidgetProvider"

        /** Прогресс-бар живёт в целых числах 0..1000 (как в задании). */
        private const val PROGRESS_MAX = 1000

        /** Явные action'ы: доставляются компонентно, поэтому работают на Android 8+. */
        const val ACTION_PREV = "com.example.glasswidgets.widget.music.ACTION_PREV"
        const val ACTION_PLAY_PAUSE = "com.example.glasswidgets.widget.music.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.glasswidgets.widget.music.ACTION_NEXT"

        private const val REQUEST_PREV = 1
        private const val REQUEST_PLAY_PAUSE = 2
        private const val REQUEST_NEXT = 3
        private const val REQUEST_OPEN_PLAYER = 4

        /** Размер обложки в dp и радиус скругления (48dp / 12dp из задания). */
        private const val COVER_SIZE_DP = 48f
        private const val COVER_RADIUS_DP = 12f

        // -------------------------------------------------- обновление всех виджетов

        /** Полная перерисовка по активной сессии (вызывает MediaListenerService). */
        fun updateAll(context: Context, controller: MediaController) {
            updateViews(context, buildViews(context, controller))
        }

        /** Сброс в состояние «не играет» (нет сессии, сервис отключён). */
        fun updateIdle(context: Context) {
            updateViews(context, buildViews(context, null))
        }

        private fun updateViews(context: Context, views: RemoteViews) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            for (id in ids) {
                manager.updateAppWidget(id, views)
            }
        }

        /** Лёгкое обновление одной только полосы прогресса — раз в секунду от тикера. */
        fun pushProgress(context: Context, controller: MediaController) {
            val fraction = progressFraction(controller) ?: return
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_music)
            views.setProgressBar(
                R.id.music_progress,
                PROGRESS_MAX,
                (fraction * PROGRESS_MAX).toInt(),
                false
            )
            // Частичное обновление: не трогаем обложку и текст, ничего не мерцает.
            manager.partiallyUpdateAppWidget(ids, views)
        }

        // -------------------------------------------------- сборка RemoteViews

        fun buildViews(context: Context, controller: MediaController?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_music)

            // Кнопки управления работают в любом состоянии.
            views.setOnClickPendingIntent(R.id.btn_prev, pendingAction(context, ACTION_PREV, REQUEST_PREV))
            views.setOnClickPendingIntent(
                R.id.btn_play_pause,
                pendingAction(context, ACTION_PLAY_PAUSE, REQUEST_PLAY_PAUSE)
            )
            views.setOnClickPendingIntent(R.id.btn_next, pendingAction(context, ACTION_NEXT, REQUEST_NEXT))

            val metadata = controller?.metadata
            if (controller == null || metadata == null) {
                // «Холодное» состояние: ничего не воспроизводится.
                views.setTextViewText(R.id.music_title, context.getString(R.string.music_not_playing))
                views.setTextViewText(R.id.music_artist, context.getString(R.string.music_turn_on))
                views.setImageViewResource(R.id.music_cover, R.drawable.ic_music_placeholder)
                views.setImageViewResource(R.id.btn_play_pause, R.drawable.ic_play)
                views.setProgressBar(R.id.music_progress, PROGRESS_MAX, 0, false)
                views.setOnClickPendingIntent(R.id.music_cover, pendingOpenPlayer(context, null))
                views.setOnClickPendingIntent(R.id.music_info, pendingOpenPlayer(context, null))
                return views
            }

            // Название и исполнитель (если поля нет — показываем имя плеера).
            val appLabel = appLabelOf(context, controller.packageName)
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: appLabel
                ?: context.getString(R.string.music_not_playing)
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: appLabel
                ?: context.getString(R.string.music_turn_on)
            views.setTextViewText(R.id.music_title, title)
            views.setTextViewText(R.id.music_artist, artist)

            // Обложка: растровую скругляем программно (12dp), нет растровой — заглушка.
            val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (art != null) {
                views.setImageViewBitmap(R.id.music_cover, roundedCover(context, art))
            } else {
                views.setImageViewResource(R.id.music_cover, R.drawable.ic_music_placeholder)
            }

            // Иконка пауза/воспроизведение по состоянию сессии.
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            views.setImageViewResource(
                R.id.btn_play_pause,
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )

            // Прогресс: доля позиции в длительности трека, масштабируем в 0..1000.
            val fraction = progressFraction(controller)
            views.setProgressBar(
                R.id.music_progress,
                PROGRESS_MAX,
                if (fraction != null) (fraction * PROGRESS_MAX).toInt() else 0,
                false
            )

            // Обложка и текстовый блок открывают окно активного плеера.
            val openPlayer = pendingOpenPlayer(context, controller)
            views.setOnClickPendingIntent(R.id.music_cover, openPlayer)
            views.setOnClickPendingIntent(R.id.music_info, openPlayer)
            return views
        }

        // -------------------------------------------------- pending intent'ы

        /** Кнопки управления: компонентный broadcast — легально на Android 8+. */
        private fun pendingAction(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MusicWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Открытие плеера: sessionActivity сессии, без сессии — музыкальное приложение. */
        private fun pendingOpenPlayer(context: Context, controller: MediaController?): PendingIntent {
            // getSessionActivity() возвращает готовый PendingIntent — используем его как есть.
            controller?.sessionActivity?.let { return it }
            // Запасной вариант: главная activity музыкального приложения по категории APP_MUSIC.
            val fallback = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_APP_MUSIC)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN_PLAYER,
                fallback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // -------------------------------------------------- прогресс и обложка

        /**
         * Доля проигранного трека 0..1. Позицию экстраполируем по скорости
         * воспроизведения, чтобы полоса двигалась между колбэками сессии.
         */
        private fun progressFraction(controller: MediaController): Float? {
            val state = controller.playbackState ?: return null
            val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (duration <= 0L) return null

            val position = if (state.state == PlaybackState.STATE_PLAYING) {
                val elapsedMs =
                    ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed)
                        .coerceAtLeast(0f)
                state.position + elapsedMs
            } else {
                state.position.toFloat()
            }
            return (position / duration).coerceIn(0f, 1f)
        }

        /**
         * Обложка в Bitmap с идеально скруглёнными углами (12dp) на любом лаунчере:
         * квадратный center-crop исходника + отрисовка через BitmapShader.
         */
        private fun roundedCover(context: Context, source: Bitmap): Bitmap {
            val density = context.resources.displayMetrics.density
            val sizePx = (COVER_SIZE_DP * density).toInt()
            val radiusPx = COVER_RADIUS_DP * density
            if (sizePx <= 0 || source.isRecycled || source.width <= 0 || source.height <= 0) {
                return source
            }

            val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            // Квадрат из центра исходной картинки (аналог scaleType="centerCrop").
            val side = minOf(source.width, source.height).toFloat()
            val scale = sizePx.toFloat() / side
            val coverShader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val matrix = Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(
                sizePx / 2f - source.width * scale / 2f,
                sizePx / 2f - source.height * scale / 2f
            )
            coverShader.setLocalMatrix(matrix)

            // Без apply: локальная val затеняла бы свойство Paint.shader.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            paint.shader = coverShader
            canvas.drawRoundRect(
                RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()),
                radiusPx,
                radiusPx,
                paint
            )
            return output
        }

        /** Человекочитаемое имя плеера («Spotify») — запасной вариант текстов. */
        private fun appLabelOf(context: Context, packageName: String): String? = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            null
        }
    }
}
