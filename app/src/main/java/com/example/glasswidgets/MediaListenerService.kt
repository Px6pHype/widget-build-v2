package com.example.glasswidgets

import android.service.notification.NotificationListenerService
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Слушатель уведомлений — единственный легальный для стороннего приложения способ
 * узнавать об активных MediaSession (Spotify, Яндекс Музыка, YouTube Music и любые
 * другие плееры). Система сама подключает сервис после того, как пользователь выдаст
 * доступ к уведомлениям.
 *
 * Здесь мы подписываемся на все активные сессии, следим за метаданными и состоянием
 * воспроизведения и перерисовываем музыкальный виджет. Пока трек играет, прогресс
 * «докручивает» локальный тикер раз в секунду — виджет двигается плавно даже если
 * плеер не присылает промежуточных колбэков.
 *
 * Доступ выдаётся в Настройки → «Доступ к уведомлениям» (кнопка на главном экране).
 */
class MediaListenerService : NotificationListenerService() {

    /** Все колбэки сессий и тикер выполняются на главном потоке — без блокировок. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Активные сессии всех плееров. */
    private val controllers = mutableListOf<MediaController>()

    private var sessionManager: MediaSessionManager? = null

    /** Колбэки конкретной сессии: метаданные или состояние изменились — перерисовываем. */
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshWidget()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refreshWidget()
        }
    }

    /** Список активных сессий изменился (плеер запустился, закрылся или вытеснил другой). */
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            // SDK объявляет параметр @Nullable — подстраховываемся пустым списком.
            mainHandler.post { attach(controllers.orEmpty()) }
        }

    /** Тикер прогресса: живёт, только пока что-то реально играет. */
    private val ticker = object : Runnable {
        override fun run() {
            val controller = currentController
            if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                MusicWidgetProvider.pushProgress(this@MediaListenerService, controller)
                mainHandler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
    }

    // -------------------------------------------------------------- жизненный цикл

    override fun onListenerConnected() {
        val self = ComponentName(this, MediaListenerService::class.java)
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, self)
        attach(sessionManager?.getActiveSessions(self) ?: emptyList())
    }

    override fun onListenerDisconnected() {
        // Система может «усыпить» слушателя: просим переподключиться (API 28+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requestRebind(ComponentName(this, MediaListenerService::class.java))
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers.clear()
        currentController = null
        // Виджет сбрасываем в состояние «не играет», чтобы не висел stale-трек.
        MusicWidgetProvider.updateIdle(this)
        super.onDestroy()
    }

    // -------------------------------------------------------------- подписка на сессии

    /** Заменяет набор сессий: переподписывает колбэки и обновляет виджет. */
    private fun attach(newControllers: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers.clear()
        controllers.addAll(newControllers)
        controllers.forEach { it.registerCallback(controllerCallback, mainHandler) }
        refreshWidget()
    }

    /** Выбирает «главную» сессию: играющую, иначе последнюю живую. */
    private fun activeController(): MediaController? {
        val playing = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        return playing
            ?: controllers.firstOrNull { it.playbackState != null }
            ?: controllers.firstOrNull()
    }

    /** Полная перерисовка виджета по текущему состоянию и перезапуск тикера. */
    private fun refreshWidget() {
        currentController = activeController()
        val controller = currentController
        if (controller == null) {
            MusicWidgetProvider.updateIdle(this)
        } else {
            MusicWidgetProvider.updateAll(this, controller)
        }
        scheduleTicker()
    }

    /** Тикер нужен только для играющего трека; на паузе полоса и так стоит. */
    private fun scheduleTicker() {
        mainHandler.removeCallbacks(ticker)
        if (currentController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
            mainHandler.post(ticker)
        }
    }

    companion object {
        private const val TICK_INTERVAL_MS = 1000L

        /**
         * Сессия, которую виджет показывает сейчас. Провайдер читает её при нажатиях
         * кнопок (процессы совпадают, поэтому ссылке достаточно быть @Volatile).
         */
        @Volatile
        var currentController: MediaController? = null
            private set
    }
}
