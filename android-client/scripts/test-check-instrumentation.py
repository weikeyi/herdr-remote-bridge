import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("checker", Path(__file__).with_name("check-instrumentation.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def success():
    lines = []
    for cls, test in sorted(checker.EXPECTED):
        for code in (1, 0):
            lines += [f"INSTRUMENTATION_STATUS: class={cls}",
                      f"INSTRUMENTATION_STATUS: test={test}",
                      "INSTRUMENTATION_STATUS: numtests=20",
                      f"INSTRUMENTATION_STATUS_CODE: {code}"]
    return "\n".join(lines + ["INSTRUMENTATION_RESULT: stream=", "OK (20 tests)", "INSTRUMENTATION_CODE: -1", ""])


class ResultTests(unittest.TestCase):
    def test_success(self):
        checker.validate(success())

    def test_screenshot_suite_is_separate_and_strict(self):
        cls, test = next(iter(checker.SCREENSHOTS))
        text = "\n".join([f"INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={test}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: {code}" for code in (1, 0)]) + "\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"
        checker.validate(text, checker.SCREENSHOTS)
        with self.assertRaises(ValueError): checker.validate(text)
        with self.assertRaises(ValueError): checker.validate(text.replace("STATUS_CODE: 0", "STATUS_CODE: -3"), checker.SCREENSHOTS)

    def test_crash(self):
        with self.assertRaises(ValueError):
            checker.validate(success() + "INSTRUMENTATION_RESULT: shortMsg=Process crashed\n")

    def test_skipped_or_failed(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(ValueError):
                checker.validate(success().replace("INSTRUMENTATION_STATUS_CODE: 0", f"INSTRUMENTATION_STATUS_CODE: {code}", 1))

    def test_missing_completion(self):
        with self.assertRaises(ValueError):
            checker.validate(success().replace("INSTRUMENTATION_CODE: -1", ""))

    def test_wrong_count_or_identity(self):
        for old, new in [("numtests=20", "numtests=1"), ("AndroidCryptoTest", "OtherTest"), ("OK (20 tests)", "OK (0 tests)")]:
            with self.subTest(old=old), self.assertRaises(ValueError):
                checker.validate(success().replace(old, new))

    def test_duplicate_or_truncated(self):
        for value in [success() + success(), success().split("INSTRUMENTATION_STATUS_CODE: 0")[0]]:
            with self.assertRaises(ValueError):
                checker.validate(value)


if __name__ == "__main__":
    unittest.main()
