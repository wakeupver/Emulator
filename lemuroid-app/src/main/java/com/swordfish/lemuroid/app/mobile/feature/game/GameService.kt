package com.swordfish.lemuroid.app.mobile.feature.game

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.swordfish.lemuroid.app.mobile.shared.NotificationsManager
import dagger.android.DaggerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

class GameService : DaggerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            awaitTermination()
            withContext(Dispatchers.Main) {
                ServiceCompat.stopForeground(this@GameService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                // Reset the static state before exiting so a future session starts clean.
                resetState()
                exitProcess(0)
            }
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        displayNotification(intent)
        return START_NOT_STICKY
    }

    private fun displayNotification(intent: Intent) {
        val gameIntent =
            intent.getParcelableExtra<Intent>(EXTRA_GAME_ACTIVITY_INTENT) ?: return
        val notification =
            NotificationsManager(applicationContext).gameRunningNotification(gameIntent)
        ServiceCompat.startForeground(
            this,
            NotificationsManager.GAME_RUNNING_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun hideNotification() {
        NotificationManagerCompat.from(this).cancel(NotificationsManager.GAME_RUNNING_NOTIFICATION_ID)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        hideNotification()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_GAME_ACTIVITY_INTENT = "EXTRA_GAME_ACTIVITY_INTENT"

        private data class GameProcessTask(
            val task: suspend () -> Unit = {},
            val terminate: Boolean = false,
        )

        /**
         * Buffered channel that absorbs bursts of background-save tasks while keeping ordering
         * guarantees.  Capacity of 32 prevents [trySend] from failing under normal load; if it
         * does fill up (e.g. tests hammering it) we log and drop rather than crash.
         */
        private val tasks = Channel<GameProcessTask>(capacity = 32)

        /**
         * Guards [requestTermination] so only the first call enqueues the poison-pill.
         * Subsequent calls (double back-press, onDestroy racing with the finish path) are no-ops.
         */
        private val terminationRequested = AtomicBoolean(false)

        fun startService(context: Context, gameActivityIntent: Intent) {
            context.startService(
                Intent(context, GameService::class.java).apply {
                    putExtra(EXTRA_GAME_ACTIVITY_INTENT, gameActivityIntent)
                },
            )
        }

        /**
         * Enqueues a background task (e.g. auto-save) to be executed on the service's IO scope.
         * Safe to call from any thread; does not block.
         */
        fun schedule(task: suspend () -> Unit) {
            val result = tasks.trySend(GameProcessTask(task = task))
            if (!result.isSuccess) {
                Timber.w("GameService.schedule: channel full, task dropped (result=%s)", result)
            } else {
                Timber.i("GameService.schedule: task enqueued")
            }
        }

        /**
         * Signals the service to drain pending tasks and then terminate the process.
         * Idempotent — safe to call multiple times; only the first enqueues the termination signal.
         */
        fun requestTermination() {
            if (!terminationRequested.compareAndSet(false, true)) {
                Timber.d("GameService.requestTermination: already requested, ignoring")
                return
            }
            val result = tasks.trySend(GameProcessTask(terminate = true))
            Timber.i("GameService.requestTermination: sent=%s", result.isSuccess)
        }

        /** Resets static state so a new session can be started after process restart. */
        private fun resetState() {
            terminationRequested.set(false)
        }

        /**
         * Drains the task channel sequentially.  Stops as soon as a termination-flagged task
         * is consumed, regardless of how many tasks remain in the buffer.
         *
         * Each task is wrapped in [runCatching] so a failing save cannot prevent termination.
         */
        private suspend fun awaitTermination() {
            for (task in tasks) {
                if (!task.terminate) {
                    runCatching { task.task() }
                        .onFailure { Timber.e(it, "GameService: background task failed") }
                } else {
                    // Termination sentinel reached — exit the drain loop.
                    Timber.i("GameService: termination task received, stopping drain")
                    return
                }
            }
        }
    }
}
