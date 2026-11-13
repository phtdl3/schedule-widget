package io.github.phtdl3.schedulewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Перерисовывает виджет в начале каждой минуты, чтобы отсчёт до пары не отставал.
 *
 * Будильник одноразовый и каждый раз заводится заново на следующую минуту: повторяющиеся
 * будильники Android срабатывают неточно. Тип RTC (без WAKEUP) не будит спящий телефон:
 * пока экран выключен, обновлений нет и батарея не тратится, а при пробуждении
 * отложенный будильник срабатывает сразу.
 */
object WidgetTicker {
    private const val MINUTE_MS = 60_000L

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, TickReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Можно вызывать сколько угодно раз: новый будильник заменяет прежний. */
    fun scheduleNext(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val nextMinute = (System.currentTimeMillis() / MINUTE_MS + 1) * MINUTE_MS
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExact(AlarmManager.RTC, nextMinute, pendingIntent(context))
        } else {
            // Без разрешения на точные будильники система сама выберет момент, с опозданием.
            alarms.set(AlarmManager.RTC, nextMinute, pendingIntent(context))
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }
}

/**
 * Срабатывает раз в минуту, а также после перезагрузки телефона, обновления приложения
 * и смены времени или часового пояса: в этих случаях будильник пропадает или устаревает.
 */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val hasWidgets = GlanceAppWidgetManager(context)
                    .getGlanceIds(ScheduleWidget::class.java)
                    .isNotEmpty()
                // Виджет убрали с экрана — цепочка будильников обрывается.
                if (hasWidgets) {
                    WidgetTicker.scheduleNext(context)
                    ScheduleWidget.refreshAll(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
