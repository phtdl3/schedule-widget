package io.github.phtdl3.schedulewidget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.phtdl3.schedule.ScheduleText
import io.github.phtdl3.schedule.overview
import io.github.phtdl3.schedule.trains.Direction
import io.github.phtdl3.schedule.trains.TrainText
import io.github.phtdl3.schedule.trains.YandexRasp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RefreshWorker.schedule(this)
        TrainsRefreshWorker.schedule(this)
        WidgetTicker.scheduleNext(this)
        setContent {
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
            MaterialTheme(colorScheme = colors) {
                Scaffold { insets ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(insets)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LessonsSection()
                        Spacer(Modifier.height(12.dp))
                        TrainsSection()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Виджеты добавляются с главного экрана: долгое нажатие на пустом месте, " +
                                "затем «Виджеты» и «Расписание».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private val stampFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

private fun stamp(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(stampFormat)

/** Расписание пар: что загружено и когда, кнопка ручного обновления. */
@Composable
private fun LessonsSection() {
    val context = LocalContext.current
    val repository = remember { ScheduleRepository.get(context) }
    val schedule by repository.schedule.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        busy = true
        message = when (val result = repository.refresh()) {
            ScheduleRepository.RefreshResult.Updated -> "Расписание обновлено"
            is ScheduleRepository.RefreshResult.Failed -> "Не удалось обновить: ${result.reason}"
        }
        Widgets.refreshAll(context)
        busy = false
    }

    LaunchedEffect(Unit) {
        repository.ensureLoaded()
        if (repository.schedule.value == null) refresh()
    }

    Text("Расписание пар", style = MaterialTheme.typography.headlineMedium)

    val current = schedule
    if (current == null) {
        Text(if (busy) "Загружаю расписание…" else "Расписание ещё не загружено")
    } else {
        val overview = current.overview(Instant.now(), SUBGROUP)
        Text("Группа ${current.group.name ?: "не указана"}, подгруппа $SUBGROUP")
        Text("Пар в расписании: ${current.lessons.size}")
        repository.downloadedAt?.let { Text("Загружено на телефон: ${stamp(it)}") }
        val next = overview.next
        Text(
            if (next == null) {
                "Следующая пара: в расписании больше нет пар"
            } else {
                "Следующая пара: ${next.subject}, ${ScheduleText.nextStart(overview)}"
            },
        )
    }

    Button(onClick = { scope.launch { refresh() } }, enabled = !busy) {
        Text(if (busy) "Обновляю…" else "Обновить расписание")
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

/** Электрички: что загружено и когда, кнопка ручного обновления, указание источника. */
@Composable
private fun TrainsSection() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val repository = remember { TrainsRepository.get(context) }
    val timetable by repository.timetable.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        busy = true
        message = when (val result = repository.refresh()) {
            TrainsRepository.RefreshResult.Updated -> "Электрички обновлены"
            is TrainsRepository.RefreshResult.Failed -> "Не удалось обновить: ${result.reason}"
        }
        Widgets.refreshAll(context)
        busy = false
    }

    LaunchedEffect(Unit) {
        repository.ensureLoaded()
        if (repository.timetable.value == null) refresh()
    }

    Text("Электрички", style = MaterialTheme.typography.headlineMedium)

    val current = timetable
    if (current == null) {
        Text(if (busy) "Загружаю электрички…" else "Электрички ещё не загружены")
    } else {
        val now = Instant.now()
        Text("Горенки — Курский вокзал и Нижегородская")
        Text("Загружено на телефон: ${stamp(current.fetchedAtEpoch * 1000)}")
        for (direction in listOf(Direction.TO_MOSCOW, Direction.FROM_MOSCOW)) {
            val next = current.upcoming(direction, now, limit = 1).firstOrNull()
            val label = TrainText.directionLabel(direction)
            Text(
                if (next == null) {
                    "$label: в сохранённом расписании больше нет электричек"
                } else {
                    "$label: ${next.from.label} ${next.departureTime}, ${TrainText.countdown(next, now)}"
                },
            )
        }
    }

    Button(onClick = { scope.launch { refresh() } }, enabled = !busy) {
        Text(if (busy) "Обновляю…" else "Обновить электрички")
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

    Text(
        YandexRasp.ATTRIBUTION,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable { uriHandler.openUri(YandexRasp.ATTRIBUTION_URL) },
    )
}
