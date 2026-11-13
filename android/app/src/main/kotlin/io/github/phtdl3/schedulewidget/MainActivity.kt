package io.github.phtdl3.schedulewidget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import io.github.phtdl3.schedule.ScheduleText
import io.github.phtdl3.schedule.overview
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RefreshWorker.schedule(this)
        WidgetTicker.scheduleNext(this)
        setContent {
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
            MaterialTheme(colorScheme = colors) { StatusScreen() }
        }
    }
}

private val stampFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

private fun stamp(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(stampFormat)

/** Экран состояния: что загружено и когда, кнопка ручного обновления. */
@Composable
private fun StatusScreen() {
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
        ScheduleWidget.refreshAll(context)
        busy = false
    }

    LaunchedEffect(Unit) {
        repository.ensureLoaded()
        if (repository.schedule.value == null) refresh()
    }

    Scaffold { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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

            Text(
                "Виджет добавляется с главного экрана: долгое нажатие на пустом месте, " +
                    "затем «Виджеты» и «Расписание».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
