package io.github.phtdl3.schedulewidget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Загрузка электричек в фоне: раз в 12 часов (12 запросов к API за раз)
 * и сразу — по кнопке обновления в виджете.
 */
class TrainsRefreshWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val result = TrainsRepository.get(applicationContext).refresh()
        Widgets.refreshAll(applicationContext)

        if (inputData.getBoolean(KEY_MANUAL, false)) {
            val text = when (result) {
                TrainsRepository.RefreshResult.Updated -> "Электрички обновлены"
                is TrainsRepository.RefreshResult.Failed ->
                    "Не удалось обновить: ${result.reason}. Показано сохранённое расписание"
            }
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
            }
            return Result.success()
        }
        return when (result) {
            TrainsRepository.RefreshResult.Updated -> Result.success()
            is TrainsRepository.RefreshResult.Failed -> Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "trains-refresh"
        private const val MANUAL_WORK_NAME = "trains-refresh-manual"
        private const val KEY_MANUAL = "manual"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrainsRefreshWorker>(12, TimeUnit.HOURS)
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

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<TrainsRefreshWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
