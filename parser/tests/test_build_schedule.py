"""Тесты парсера. Запуск: python -m unittest discover -s parser/tests -v"""

import copy
import json
import sys
import unittest
from datetime import date, datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import build_schedule as bs  # noqa: E402

FIXTURE = Path(__file__).parent / "fixtures" / "rasp_sample.json"
NOW = datetime(2026, 11, 13, 9, 0, tzinfo=timezone.utc)


def load_fixture() -> dict:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


class SplitTitleTest(unittest.TestCase):
    def test_lecture(self):
        self.assertEqual(
            bs.split_title("лек Высшая математика"),
            ("lecture", "Высшая математика", None),
        )

    def test_practice_with_subgroup(self):
        self.assertEqual(
            bs.split_title("пр Иностранный язык, п/г 2"),
            ("practice", "Иностранный язык", 2),
        )

    def test_lab_with_subgroup(self):
        self.assertEqual(
            bs.split_title("лаб Программирование, п/г 1"),
            ("lab", "Программирование", 1),
        )

    def test_title_without_type_is_kept_whole(self):
        self.assertEqual(
            bs.split_title("Кураторский час. Аттестация БИОСТАРТ"),
            ("other", "Кураторский час. Аттестация БИОСТАРТ", None),
        )

    def test_type_word_alone_is_not_stripped(self):
        self.assertEqual(bs.split_title("Лекция"), ("other", "Лекция", None))

    def test_subject_starting_with_type_letters_is_not_split(self):
        # "пр" — тип, только если это отдельное первое слово.
        self.assertEqual(
            bs.split_title("Правоведение"), ("other", "Правоведение", None)
        )


class BuildScheduleTest(unittest.TestCase):
    def setUp(self):
        self.schedule = bs.build_schedule(load_fixture(), NOW)
        self.by_id = {lesson["id"]: lesson for lesson in self.schedule["lessons"]}

    def test_header(self):
        self.assertEqual(self.schedule["schemaVersion"], 1)
        self.assertEqual(
            self.schedule["group"], {"id": 18521, "name": "26о-090303-ПИ/1"}
        )
        self.assertEqual(self.schedule["academicYear"], "2026-2027")
        self.assertEqual(self.schedule["timezone"], "Europe/Moscow")
        self.assertEqual(self.schedule["generatedAt"], "2026-11-13T09:00:00+00:00")
        self.assertEqual(self.schedule["sourceUpdatedAt"], "2026-11-12T18:01:29+03:00")
        self.assertEqual(
            self.schedule["range"], {"from": "2026-09-07", "to": "2026-11-06"}
        )

    def test_keeps_every_lesson(self):
        self.assertEqual(len(self.schedule["lessons"]), 9)

    def test_lecture_fields(self):
        self.assertEqual(
            self.by_id[17056778],
            {
                "id": 17056778,
                "date": "2026-09-07",
                "weekday": 1,
                "slot": 1,
                "start": "08:30",
                "end": "10:00",
                "subject": "Высшая математика",
                "type": "lecture",
                "subgroup": None,
                "room": "В-5-03",
                "teacher": "Санаева Татьяна Александровна",
                "teacherShort": "Санаева Т. А.",
                "week": 2,
                "weekParity": "even",
                "substitution": False,
            },
        )

    def test_subgroups_at_the_same_time(self):
        first, second = self.by_id[17056907], self.by_id[17060911]
        self.assertEqual((first["subgroup"], first["room"]), (1, "Б-3-01"))
        self.assertEqual((second["subgroup"], second["room"]), (2, "В-6-07"))
        self.assertEqual(first["start"], second["start"])
        self.assertEqual(first["subject"], "Программирование")
        self.assertEqual(first["type"], "lab")

    def test_substitution_flag(self):
        self.assertTrue(self.by_id[17053864]["substitution"])
        self.assertFalse(self.by_id[17056778]["substitution"])

    def test_week_parity(self):
        self.assertEqual(self.by_id[17053864]["week"], 3)
        self.assertEqual(self.by_id[17053864]["weekParity"], "odd")

    def test_sorted_by_date_time_subgroup(self):
        # В образце занятия намеренно идут в обратном порядке.
        order = [lesson["id"] for lesson in self.schedule["lessons"]]
        self.assertEqual(
            order,
            [
                17056778,
                17053864,
                17058549,
                17054095,
                17057057,
                17054711,
                17056907,
                17060911,
                17055727,
            ],
        )

    def test_result_is_valid(self):
        self.assertEqual(bs.validate(self.schedule), [])

    def test_api_error_is_reported(self):
        with self.assertRaises(bs.ScheduleError):
            bs.build_schedule({"data": None, "state": -1, "msg": "NotFound"}, NOW)

    def test_missing_lesson_list_is_reported(self):
        with self.assertRaises(bs.ScheduleError):
            bs.build_schedule({"data": {"info": {}}, "state": 1}, NOW)

    def test_empty_schedule_builds(self):
        response = load_fixture()
        response["data"]["rasp"] = []
        schedule = bs.build_schedule(response, NOW)
        self.assertEqual(schedule["lessons"], [])
        self.assertEqual(schedule["range"], {"from": None, "to": None})


class ValidateTest(unittest.TestCase):
    def setUp(self):
        self.schedule = bs.build_schedule(load_fixture(), NOW)

    def broken(self, **changes) -> list[str]:
        schedule = copy.deepcopy(self.schedule)
        schedule["lessons"][0].update(changes)
        return bs.validate(schedule)

    def test_duplicate_id(self):
        schedule = copy.deepcopy(self.schedule)
        schedule["lessons"][1]["id"] = schedule["lessons"][0]["id"]
        self.assertTrue(any("повторяющийся id" in p for p in bs.validate(schedule)))

    def test_end_before_start(self):
        self.assertTrue(any("не позже начала" in p for p in self.broken(end="08:00")))

    def test_bad_time_format(self):
        self.assertTrue(any("время начала" in p for p in self.broken(start="8:30")))

    def test_weekday_mismatch(self):
        self.assertTrue(any("день недели" in p for p in self.broken(weekday=2)))

    def test_unknown_type(self):
        self.assertTrue(any("неизвестный тип" in p for p in self.broken(type="лек")))

    def test_unsorted(self):
        schedule = copy.deepcopy(self.schedule)
        schedule["lessons"].reverse()
        self.assertTrue(any("не отсортированы" in p for p in bs.validate(schedule)))


class AcademicYearsTest(unittest.TestCase):
    def test_autumn(self):
        self.assertEqual(
            bs.academic_years(date(2026, 11, 13)), ["2026-2027", "2025-2026"]
        )

    def test_spring(self):
        self.assertEqual(
            bs.academic_years(date(2027, 3, 1)), ["2026-2027", "2025-2026"]
        )


if __name__ == "__main__":
    unittest.main()
