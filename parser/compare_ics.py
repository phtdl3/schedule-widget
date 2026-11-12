"""Сверяет schedule.json с календарём .ics, выгруженным из личного кабинета.

Это независимая проверка парсера: .ics вуз формирует сам, другим путём.

Запуск:
    python parser/compare_ics.py calendar.ics public/schedule.json
"""

from __future__ import annotations

import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

from build_schedule import MOSCOW, split_title

TEACHER_RE = re.compile(r"Преподаватель\s+(.*?),\s*группа:")


def read_events(path: Path) -> list[dict]:
    """Минимальный разбор .ics: только события и их простые свойства."""
    text = path.read_text(encoding="utf-8-sig")
    # Длинные строки в .ics переносятся с пробелом в начале продолжения.
    text = re.sub(r"\r?\n[ \t]", "", text)
    events: list[dict] = []
    current: dict | None = None
    for line in text.splitlines():
        if line == "BEGIN:VEVENT":
            current = {}
        elif line == "END:VEVENT" and current is not None:
            events.append(current)
            current = None
        elif current is not None and ":" in line:
            name, _, value = line.partition(":")
            value = value.replace("\\,", ",").replace("\\;", ";").replace("\\n", " ")
            current[name.split(";")[0]] = value.strip()
    return events


def local(stamp: str) -> datetime:
    """'20261102T053000Z' (UTC) -> московское время."""
    moment = datetime.strptime(stamp, "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
    return moment.astimezone(MOSCOW)


def expected_from_event(event: dict) -> dict:
    start, end = local(event["DTSTART"]), local(event["DTEND"])
    lesson_type, subject, subgroup = split_title(event["SUMMARY"])
    teacher = TEACHER_RE.search(event.get("DESCRIPTION", ""))
    return {
        "date": start.date().isoformat(),
        "start": start.strftime("%H:%M"),
        "end": end.strftime("%H:%M"),
        "type": lesson_type,
        "subject": subject,
        "subgroup": subgroup,
        "room": event.get("LOCATION") or None,
        "teacher": teacher.group(1).strip() if teacher else None,
    }


def compare(events: list[dict], schedule: dict) -> list[str]:
    problems: list[str] = []
    lessons = {lesson["id"]: lesson for lesson in schedule["lessons"]}
    expected = {int(event["UID"]): expected_from_event(event) for event in events}

    for uid, want in expected.items():
        lesson = lessons.get(uid)
        if lesson is None:
            problems.append(f"{uid}: есть в .ics, нет в schedule.json ({want['date']} {want['start']})")
            continue
        for field, value in want.items():
            if lesson[field] != value:
                problems.append(
                    f"{uid}: поле {field}: в .ics {value!r}, в schedule.json {lesson[field]!r}"
                )

    # Обратная проверка: в тех же датах в schedule.json не должно быть лишних пар.
    if expected:
        first = min(item["date"] for item in expected.values())
        last = max(item["date"] for item in expected.values())
        for uid, lesson in lessons.items():
            if first <= lesson["date"] <= last and uid not in expected:
                problems.append(
                    f"{uid}: есть в schedule.json, нет в .ics ({lesson['date']} {lesson['start']})"
                )
    return problems


def main(argv: list[str]) -> int:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="replace")
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    events = read_events(Path(argv[0]))
    schedule = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
    problems = compare(events, schedule)
    if problems:
        print(f"Расхождений: {len(problems)}", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"Совпало: {len(events)} событий из .ics, по всем полям, лишних пар нет.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
