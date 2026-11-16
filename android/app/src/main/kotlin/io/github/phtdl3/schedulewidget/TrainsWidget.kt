package io.github.phtdl3.schedulewidget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.phtdl3.schedule.trains.Direction
import io.github.phtdl3.schedule.trains.Station
import io.github.phtdl3.schedule.trains.Train
import io.github.phtdl3.schedule.trains.TrainText
import io.github.phtdl3.schedule.trains.TrainTimetable
import io.github.phtdl3.schedule.trains.Urgency
import java.time.Instant

class TrainsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrainsWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TrainsRefreshWorker.schedule(context)
        WidgetTicker.scheduleNext(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetClock.tick()
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetTicker.scheduleNext(context)
    }
}

/** Виджет электричек Горенки — Москва. Макет описан в README, «Виджет электричек». */
class TrainsWidget : GlanceAppWidget() {
    // Точный размер нужен, чтобы посчитать, сколько строк списка помещается.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = TrainsRepository.get(context)
        repository.ensureLoaded()
        WidgetClock.tick()

        provideContent {
            val timetable by repository.timetable.collectAsState()
            val direction by repository.direction.collectAsState()
            val now by WidgetClock.now.collectAsState()
            TrainsContent(timetable, direction, now)
        }
    }
}

private val directionKey = ActionParameters.Key<String>("direction")

/** Нажатие на переключатель «В Москву / Из Москвы». */
class SetDirectionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val direction = runCatching { Direction.valueOf(parameters[directionKey].orEmpty()) }.getOrNull() ?: return
        TrainsRepository.get(context).setDirection(direction)
        Widgets.refreshAll(context)
    }
}

/** Кнопка обновления: перерисовать сейчас и скачать свежее расписание электричек. */
class RefreshTrainsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetTicker.scheduleNext(context)
        Widgets.refreshAll(context)
        TrainsRefreshWorker.runNow(context)
    }
}

private object TrainColors {
    private fun pair(day: Long, night: Long) = ColorProvider(day = Color(day), night = Color(night))

    val background = pair(0xFFFFFFFF, 0xFF2C2C2A)
    val primary = pair(0xFF2C2C2A, 0xFFF1EFE8)
    val secondary = pair(0xFF444441, 0xFFD3D1C7)
    val muted = pair(0xFF888780, 0xFF888780)
    val track = pair(0xFFF1EFE8, 0xFF444441)
    val divider = pair(0xFFD3D1C7, 0xFF5F5E5A)

    fun stationFill(station: Station) = when (station) {
        Station.KURSKY -> pair(0xFFB5D4F4, 0xFF0C447C)
        Station.NIZHEGORODSKAYA -> pair(0xFF9FE1CB, 0xFF085041)
        Station.GORENKI -> pair(0xFFC0DD97, 0xFF27500A)
    }

    fun stationText(station: Station) = when (station) {
        Station.KURSKY -> pair(0xFF042C53, 0xFFB5D4F4)
        Station.NIZHEGORODSKAYA -> pair(0xFF04342C, 0xFF9FE1CB)
        Station.GORENKI -> pair(0xFF173404, 0xFFC0DD97)
    }

    // Отсчёт «горит» одинаково в обеих темах.
    fun countdownFill(urgency: Urgency) = when (urgency) {
        Urgency.FAR -> track
        Urgency.SOON -> pair(0xFFFAC775, 0xFFFAC775)
        Urgency.NOW -> pair(0xFFA32D2D, 0xFFA32D2D)
    }

    fun countdownText(urgency: Urgency) = when (urgency) {
        Urgency.FAR -> secondary
        Urgency.SOON -> pair(0xFF412402, 0xFF412402)
        Urgency.NOW -> pair(0xFFFCEBEB, 0xFFFCEBEB)
    }
}

private fun style(color: ColorProvider, size: Int = 13, bold: Boolean = false) = TextStyle(
    color = color,
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
)

/** Ширина плашки станции: одинаковая у всех, чтобы строки были симметричны. */
private val CHIP_WIDTH = 76.dp

/** Примерная высота всего, что выше списка, вместе с отступами виджета. */
private const val HEADER_HEIGHT_DP = 142f

/** Примерная высота одной строки списка. */
private const val ROW_HEIGHT_DP = 32f

/** Glance не разрешает больше 10 элементов в одном столбце. */
private const val MAX_ROWS = 10

@Composable
private fun TrainsContent(timetable: TrainTimetable?, direction: Direction, now: Instant) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(TrainColors.background)
            .cornerRadius(24.dp)
            .padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        TopBar(direction)
        Spacer(GlanceModifier.height(6.dp))

        if (timetable == null) {
            Message("Электрички ещё не загружены", "Нажмите на стрелку, чтобы загрузить расписание")
            return@Column
        }
        val capacity = ((LocalSize.current.height.value - HEADER_HEIGHT_DP) / ROW_HEIGHT_DP).toInt()
        val trains = timetable.upcoming(direction, now, limit = 1 + capacity.coerceIn(0, MAX_ROWS))
        val next = trains.firstOrNull()
        if (next == null) {
            Message("В сохранённом расписании больше нет электричек", "Нажмите на стрелку, чтобы обновить его")
            return@Column
        }

        NextTrain(next, now)
        Spacer(GlanceModifier.height(10.dp))
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(TrainColors.divider)) {}
        Spacer(GlanceModifier.height(6.dp))
        Column(GlanceModifier.fillMaxWidth()) {
            trains.drop(1).forEach { train -> TrainRow(train) }
        }
    }
}

@Composable
private fun Message(title: String, hint: String) {
    Column {
        Text(title, style = style(TrainColors.primary, 16, bold = true))
        Spacer(GlanceModifier.height(4.dp))
        Text(hint, style = style(TrainColors.secondary))
    }
}

/** Переключатель направления слева, кнопка обновления справа. */
@Composable
private fun TopBar(direction: Direction) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(GlanceModifier.background(TrainColors.track).cornerRadius(14.dp).padding(2.dp)) {
            listOf(Direction.TO_MOSCOW, Direction.FROM_MOSCOW).forEach { option ->
                val selected = option == direction
                var modifier = GlanceModifier
                    .cornerRadius(12.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clickable(
                        actionRunCallback<SetDirectionAction>(actionParametersOf(directionKey to option.name)),
                    )
                if (selected) modifier = modifier.background(TrainColors.background)
                Box(modifier) {
                    Text(
                        TrainText.directionLabel(option),
                        style = style(if (selected) TrainColors.primary else TrainColors.secondary, 12, bold = selected),
                    )
                }
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Box(
            modifier = GlanceModifier
                .size(36.dp)
                .cornerRadius(18.dp)
                .clickable(actionRunCallback<RefreshTrainsAction>()),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Обновить",
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(TrainColors.secondary),
            )
        }
    }
}

@Composable
private fun StationChip(station: Station) {
    Box(
        modifier = GlanceModifier
            .width(CHIP_WIDTH)
            .background(TrainColors.stationFill(station))
            .cornerRadius(6.dp)
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(station.label, style = style(TrainColors.stationText(station), 12, bold = true), maxLines = 1)
    }
}

/**
 * Время поезда: отправление крупно и жирно, время в пути мелко и серо, прибытие средне.
 * В Glance нет выравнивания по базовой линии, поэтому мелкий текст приподнят отступом снизу.
 */
@Composable
private fun Times(train: Train, big: Boolean) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            train.departureTime.toString(),
            style = style(TrainColors.primary, if (big) 28 else 18, bold = true),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(7.dp))
        Text(
            TrainText.travelTime(train),
            style = style(TrainColors.muted, 11),
            modifier = GlanceModifier.padding(bottom = if (big) 5.dp else 2.dp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(7.dp))
        Text(
            train.arrivalTime.toString(),
            style = style(TrainColors.secondary, if (big) 17 else 14),
            modifier = GlanceModifier.padding(bottom = if (big) 3.dp else 1.dp),
            maxLines = 1,
        )
    }
}

/** Ближайшая электричка: та же строка, но крупнее, с отсчётом над временем отправления. */
@Composable
private fun NextTrain(train: Train, now: Instant) {
    val urgency = TrainText.urgency(train, now)
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Box(GlanceModifier.padding(bottom = 8.dp)) { StationChip(train.from) }
        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(horizontalAlignment = Alignment.Start) {
                Box(
                    modifier = GlanceModifier
                        .background(TrainColors.countdownFill(urgency))
                        .cornerRadius(6.dp)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        TrainText.countdown(train, now),
                        style = style(TrainColors.countdownText(urgency), 13, bold = true),
                        maxLines = 1,
                    )
                }
                Times(train, big = true)
            }
        }
        Box(GlanceModifier.padding(bottom = 8.dp)) { StationChip(train.to) }
    }
}

@Composable
private fun TrainRow(train: Train) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationChip(train.from)
        Row(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Times(train, big = false)
        }
        StationChip(train.to)
    }
}
