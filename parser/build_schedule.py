"""Собирает schedule.json из открытого API расписания РОСБИОТЕХ (система ММИС).

Запуск:
    python parser/build_schedule.py                      # скачать и собрать public/schedule.json
    python parser/build_schedule.py --input raw.json     # собрать из сохранённого ответа API

Только стандартная библиотека Python, сторонние пакеты не нужны.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

BASE_URL = "https://dekanat.rosbiotech.ru/api"
DEFAULT_GROUP = "26о-090303-ПИ/1"
DEFAULT_OUTPUT = Path("public") / "schedule.json"

SCHEMA_VERSION = 1
TIMEZONE_NAME = "Europe/Moscow"
# В Москве нет перехода на летнее время, поэтому смещение постоянное.
MOSCOW = timezone(timedelta(hours=3))
USER_AGENT = "schedule-widget-parser/1.0"

# Тип занятия стоит первым словом в названии: "лек Высшая математика".
LESSON_TYPES = {"лек": "lecture", "пр": "practice", "лаб": "lab"}
KNOWN_TYPES = set(LESSON_TYPES.values()) | {"other"}
# Подгруппа стоит в конце названия: "лаб Программирование, п/г 2".
SUBGROUP_RE = re.compile(r",\s*п/г\s*(\d+)\s*$")
TIME_RE = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")


class ScheduleError(Exception):
    """Данные не получены или не похожи на расписание."""


# --- Загрузка -----------------------------------------------------------------


def http_get_json(url: str, retries: int = 3, timeout: int = 30) -> dict:
    """GET-запрос с повторами: сайт вуза иногда отвечает не с первого раза."""
    request = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"}
    )
    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, ValueError) as error:
            last_error = error
            if attempt < retries:
                time.sleep(2 * attempt)
    raise ScheduleError(f"не удалось получить {url}: {last_error}")


def unwrap(response: dict, what: str):
    """API оборачивает ответ в {"data": ..., "state": 1, "msg": ...}."""
    if not isinstance(response, dict) or response.get("state") != 1:
        message = response.get("msg") if isinstance(response, dict) else response
        raise ScheduleError(f"API вернул ошибку ({what}): {message!r}")
    if response.get("data") is None:
        raise ScheduleError(f"API вернул пустой ответ ({what})")
    return response["data"]


def academic_years(today: date) -> list[str]:
    """Учебные годы, в которых стоит искать группу: текущий, затем предыдущий."""
    start = today.year if today.month >= 8 else today.year - 1
    # В августе список групп нового года может быть ещё не опубликован.
    return [f"{start}-{start + 1}", f"{start - 1}-{start}"]


def find_group_id(name: str, today: date) -> int:
    """Ищет код группы по названию: коды меняются от года к году."""
    for year in academic_years(today):
        url = f"{BASE_URL}/raspGrouplist?" + urllib.parse.urlencode({"year": year})
        groups = unwrap(http_get_json(url), "список групп")
        for group in groups:
            if group.get("name") == name:
                return int(group["id"])
    raise ScheduleError(f"группа {name!r} не найдена в списке групп")


def fetch_raw(group_id: int) -> dict:
    url = f"{BASE_URL}/Rasp?" + urllib.parse.urlencode({"idGroup": group_id})
    return http_get_json(url)


# --- Преобразование -------------------------------------------------------------


def split_title(title: str) -> tuple[str, str, int | None]:
    """'лаб Программирование, п/г 2' -> ('lab', 'Программирование', 2)."""
    title = " ".join(title.split())
    subgroup = None
    match = SUBGROUP_RE.search(title)
    if match:
        subgroup = int(match.group(1))
        title = title[: match.start()].strip()
    head, _, rest = title.partition(" ")
    if head in LESSON_TYPES and rest.strip():
        return LESSON_TYPES[head], rest.strip(), subgroup
    # Запись без известного типа ("Кураторский час") оставляем как есть.
    return "other", title, subgroup


def clean(value) -> str | None:
    text = " ".join(str(value).split()) if value is not None else ""
    return text or None


def convert_lesson(raw: dict) -> dict:
    start = datetime.fromisoformat(raw["датаНачала"])
    end = datetime.fromisoformat(raw["датаОкончания"])
    lesson_type, subject, subgroup = split_title(raw["дисциплина"])
    if subgroup is None and raw.get("номерПодгруппы"):
        subgroup = int(raw["номерПодгруппы"])

    # Расписание не циклическое, и в "типНедели" API кладёт номер учебной недели.
    week = raw.get("типНедели")
    week = int(week) if isinstance(week, int) and week > 0 else None
    parity = None if week is None else ("odd" if week % 2 else "even")

    return {
        "id": int(raw["код"]),
        "date": start.date().isoformat(),
        "weekday": start.isoweekday(),  # 1 = понедельник
        "slot": raw.get("номерЗанятия"),  # номер пары в сетке звонков
        "start": start.strftime("%H:%M"),
        "end": end.strftime("%H:%M"),
        "subject": subject,
        "type": lesson_type,
        "subgroup": subgroup,
        "room": clean(raw.get("аудитория")),
        "teacher": clean(raw.get("преподаватель")),
        "teacherShort": clean(raw.get("фиоПреподавателя")),
        "week": week,
        "weekParity": parity,
        "substitution": bool(raw.get("замена")),
    }


def sort_key(lesson: dict):
    return (lesson["date"], lesson["start"], lesson["subgroup"] or 0, lesson["id"])


def build_schedule(response: dict, generated_at: datetime) -> dict:
    """Превращает ответ /api/Rasp в структуру schedule.json."""
    data = unwrap(response, "расписание")
    raw_lessons = data.get("rasp")
    if not isinstance(raw_lessons, list):
        raise ScheduleError("в ответе API нет списка занятий")

    lessons = sorted((convert_lesson(raw) for raw in raw_lessons), key=sort_key)

    info = data.get("info") or {}
    group = info.get("group") or {}
    uploaded = info.get("dateUploadingRasp")
    source_updated = None
    if uploaded:
        source_updated = (
            datetime.fromisoformat(uploaded).replace(tzinfo=MOSCOW).isoformat()
        )

    return {
        "schemaVersion": SCHEMA_VERSION,
        "group": {"id": group.get("groupID"), "name": group.get("name")},
        "academicYear": info.get("year"),
        "timezone": TIMEZONE_NAME,
        "generatedAt": generated_at.astimezone(timezone.utc).isoformat(
            timespec="seconds"
        ),
        "sourceUpdatedAt": source_updated,
        "range": {
            "from": lessons[0]["date"] if lessons else None,
            "to": lessons[-1]["date"] if lessons else None,
        },
        "lessons": lessons,
    }


# --- Проверка -------------------------------------------------------------------


def validate(schedule: dict) -> list[str]:
    """Возвращает список проблем; пустой список значит, что файл корректен."""
    problems: list[str] = []
    lessons = schedule.get("lessons")
    if not isinstance(lessons, list):
        return ["lessons: нет списка занятий"]

    seen: set[int] = set()
    for index, lesson in enumerate(lessons):
        where = f"занятие #{index} (id={lesson.get('id')})"
        if lesson.get("id") in seen:
            problems.append(f"{where}: повторяющийся id")
        seen.add(lesson.get("id"))

        try:
            day = date.fromisoformat(lesson["date"])
        except (KeyError, TypeError, ValueError):
            problems.append(f"{where}: некорректная дата {lesson.get('date')!r}")
        else:
            if day.isoweekday() != lesson.get("weekday"):
                problems.append(f"{where}: день недели не соответствует дате")

        start, end = lesson.get("start"), lesson.get("end")
        if not (isinstance(start, str) and TIME_RE.match(start)):
            problems.append(f"{where}: некорректное время начала {start!r}")
        elif not (isinstance(end, str) and TIME_RE.match(end)):
            problems.append(f"{where}: некорректное время конца {end!r}")
        elif end <= start:
            problems.append(f"{where}: конец {end} не позже начала {start}")

        if not lesson.get("subject"):
            problems.append(f"{where}: пустое название предмета")
        if lesson.get("type") not in KNOWN_TYPES:
            problems.append(f"{where}: неизвестный тип {lesson.get('type')!r}")

    if lessons != sorted(lessons, key=sort_key):
        problems.append("lessons: занятия не отсортированы по дате и времени")
    if not schedule.get("group", {}).get("name"):
        problems.append("group: нет названия группы")
    return problems


# --- Командная строка ------------------------------------------------------------


def write_json(path: Path, payload: dict) -> None:
    """Пишет через временный файл, чтобы не оставить обрезанный JSON при сбое."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with open(temporary, "w", encoding="utf-8", newline="\n") as file:
        json.dump(payload, file, ensure_ascii=False, indent=2)
        file.write("\n")
    temporary.replace(path)


def summary(schedule: dict) -> str:
    lessons = schedule["lessons"]
    types = Counter(lesson["type"] for lesson in lessons)
    lines = [
        f"Группа: {schedule['group']['name']} (id {schedule['group']['id']})",
        f"Занятий: {len(lessons)}, "
        f"с {schedule['range']['from']} по {schedule['range']['to']}",
        "По типам: " + ", ".join(f"{name} {count}" for name, count in sorted(types.items())),
        f"Замен: {sum(lesson['substitution'] for lesson in lessons)}",
        f"Расписание на сайте обновлено: {schedule['sourceUpdatedAt']}",
    ]
    return "\n".join(lines)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--group", default=DEFAULT_GROUP, help="название группы")
    parser.add_argument("--group-id", type=int, help="код группы (вместо поиска по названию)")
    parser.add_argument("--input", type=Path, help="сохранённый ответ API вместо скачивания")
    parser.add_argument("--save-raw", type=Path, help="куда сохранить исходный ответ API")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUTPUT, help="куда писать schedule.json")
    parser.add_argument(
        "--allow-empty",
        action="store_true",
        help="не считать ошибкой расписание без занятий (каникулы)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="replace")
    args = parse_args(argv)
    now = datetime.now(timezone.utc)

    try:
        if args.input:
            response = json.loads(args.input.read_text(encoding="utf-8"))
        else:
            group_id = args.group_id or find_group_id(
                args.group, now.astimezone(MOSCOW).date()
            )
            response = fetch_raw(group_id)
            if args.save_raw:
                write_json(args.save_raw, response)
        schedule = build_schedule(response, now)
    except (ScheduleError, OSError, KeyError, ValueError) as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 1

    problems = validate(schedule)
    # Пустой ответ чаще означает сбой на сайте, чем каникулы: старый файл не трогаем.
    if not schedule["lessons"] and not args.allow_empty:
        problems.append("в расписании нет ни одного занятия (см. --allow-empty)")
    if problems:
        print("schedule.json не записан, найдены проблемы:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    write_json(args.out, schedule)
    print(summary(schedule))
    print(f"Записано: {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
