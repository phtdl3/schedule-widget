package io.github.phtdl3.schedulewidget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Фоновое обновление расписания: раз в 6 часов, только при наличии сети. */
class RefreshWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val result = ScheduleRepository.get(applicationContext).refresh()
        ScheduleWidget.refreshAll(applicationContext)
        return when (result) {
            ScheduleRepository.RefreshResult.Updated -> Result.success()
            is ScheduleRepository.RefreshResult.Failed -> Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "schedule-refresh"

        /** Безопасно вызывать много раз: уже запланированная задача сохраняется. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
