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
 * Загрузка расписания в фоне: по расписанию раз в 6 часов (только при наличии сети)
 * и сразу — по кнопке обновления в виджете.
 */
class RefreshWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val result = ScheduleRepository.get(applicationContext).refresh()
        ScheduleWidget.refreshAll(applicationContext)

        if (inputData.getBoolean(KEY_MANUAL, false)) {
            // Запуск кнопкой: сообщаем, чем закончилось, и сами попытку не повторяем.
            toast(
                when (result) {
                    ScheduleRepository.RefreshResult.Updated -> "Расписание обновлено"
                    is ScheduleRepository.RefreshResult.Failed ->
                        "Не удалось обновить: ${result.reason}. Показано сохранённое расписание"
                },
            )
            return Result.success()
        }
        return when (result) {
            ScheduleRepository.RefreshResult.Updated -> Result.success()
            is ScheduleRepository.RefreshResult.Failed -> Result.retry()
        }
    }

    private fun toast(text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val WORK_NAME = "schedule-refresh"
        private const val MANUAL_WORK_NAME = "schedule-refresh-manual"
        private const val KEY_MANUAL = "manual"

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

        /** Обновить расписание прямо сейчас. Повторные нажатия, пока идёт загрузка, не копятся. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
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
