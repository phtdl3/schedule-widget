package io.github.phtdl3.schedulewidget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll

/** Общее для обоих виджетов приложения: пар и электричек. */
object Widgets {
    /** Перерисовать все виджеты с текущим временем. */
    suspend fun refreshAll(context: Context) {
        WidgetClock.tick()
        ScheduleWidget().updateAll(context)
        TrainsWidget().updateAll(context)
    }

    /** Есть ли на экране хотя бы один виджет приложения. */
    suspend fun anyPlaced(context: Context): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return manager.getGlanceIds(ScheduleWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(TrainsWidget::class.java).isNotEmpty()
    }
}
