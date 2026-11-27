"""Device-free tests: python3 -m unittest discover -s tests -v"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "host"))
import ime_input as m  # noqa: E402

EDITOR_DUMP = ("  StartInput history:\n    targetWin=aaa u0 com.example.app/com.example.Main [com.example.app]\n"
               "      inputType=0x1 imeOptions=0x0 fieldId=0x7f0a0001 fieldName=null\n")
PW_DUMP = EDITOR_DUMP.replace("inputType=0x1 ", "inputType=0x81 ")
FG = "mCurrentFocus=Window{abc u0 com.example.app/com.example.Main}\nmDreamingLockscreen=false\n"
NODE = '<hierarchy><node focused="true" resource-id="com.example.app:id/edit" class="android.widget.EditText" text="{t}" bounds="[0,0][9,9]"/></hierarchy>'


class FakeAdb:
    def __init__(self, dump=EDITOR_DUMP, code=10, readback=True, commit_ok=True):
        self.serial = "FAKE1"
        self.ime = "com.original/.Kb"
        self.dump, self.code, self.readback = dump, code, readback
        self.log = []
        self.typed = None

    def devices(self):
        return "List of devices attached\nFAKE1 device usb:1\n"

    def shell(self, *a):
        self.log.append(a)
        if a[:2] == ("dumpsys", "window"):
            return FG
        if a[:2] == ("dumpsys", "power"):
            return "mWakefulness=Awake"
        if a[:3] == ("settings", "get", "secure"):
            return self.ime + "\n"
        if a[:2] == ("ime", "set"):
            self.ime = a[2]
            return ""
        if a[:2] == ("dumpsys", "input_method"):
            return self.dump
        if a[0] == "am":
            import base64
            self.typed = base64.b64decode(a[-1]).decode()
            return f"Broadcast completed: result={self.code}\n"
        if a[0] == "cat":
            return NODE.format(t=(self.typed or "") if self.readback else "x")
        return ""


def run(fake, text="안녕 hello", **kw):
    d = tempfile.mkdtemp()
    r = m.Runner(fake, state_dir=d, sleep=lambda s: None)
    return r.run("com.example.app", text, **kw), d


class Tests(unittest.TestCase):
    def test_parse_editor(self):
        self.assertEqual(m.parse_editor(EDITOR_DUMP), {"pkg": "com.example.app", "field": 0x7F0A0001, "input_type": 1})
        self.assertIsNone(m.parse_editor("nothing"))

    def test_web_field_id(self):
        d = EDITOR_DUMP.replace("fieldId=0x7f0a0001", "fieldId=0xffffffff")
        self.assertEqual(m.parse_editor(d)["field"], 4294967295)
        d = EDITOR_DUMP.replace("fieldId=0x7f0a0001", "fieldId=-1")
        self.assertEqual(m.parse_editor(d)["field"], -1)

    def test_package_constants(self):
        self.assertEqual(m.IME_ID, "ai.wellsa.koreanime/.TextIme")

    def test_password_types(self):
        for t in (0x81, 0x91, 0xE1, 0x12):
            self.assertTrue(m.is_password(t), hex(t))
        self.assertFalse(m.is_password(0x1))

    def test_validate(self):
        self.assertEqual(m.validate_text("가나다\n"), "가나다")
        for bad in ("", "a\x07b", "x" * 2001):
            with self.assertRaises(m.Stop):
                m.validate_text(bad)

    def test_result_code(self):
        self.assertEqual(m.parse_result_code("Broadcast completed: result=10"), 10)
        self.assertIsNone(m.parse_result_code("x"))

    def test_success_restores_ime_and_hides_text(self):
        f = FakeAdb()
        res, d = run(f)
        self.assertTrue(res["ok"], res)
        self.assertEqual(f.typed, "안녕 hello")
        self.assertEqual(f.ime, "com.original/.Kb")
        self.assertNotIn("안녕", json.dumps(res, ensure_ascii=False))
        self.assertEqual(res["text_len"], 8)
        self.assertFalse(os.path.exists(os.path.join(d, "ime-restore.json")))

    def test_password_refused_without_switching(self):
        f = FakeAdb(dump=PW_DUMP)
        res, _ = run(f)
        self.assertFalse(res["ok"])
        self.assertEqual(res["stop_why"], "password_field")
        self.assertFalse(any(a[:2] == ("ime", "set") and a[2] == m.IME_ID for a in f.log))

    def test_package_mismatch(self):
        res, _ = m.Runner(FakeAdb(), state_dir=tempfile.mkdtemp(), sleep=lambda s: None).run("other.app", "x"), None
        self.assertEqual(res["stop_why"], "pkg_mismatch")

    def test_restore_after_failed_commit(self):
        f = FakeAdb(code=1)
        res, _ = run(f)
        self.assertFalse(res["ok"])
        self.assertEqual(res["stop_why"], "code_1")
        self.assertTrue(res["ime_restored"])
        self.assertEqual(f.ime, "com.original/.Kb")

    def test_readback_mismatch_fails_but_restores(self):
        f = FakeAdb(readback=False)
        res, _ = run(f)
        self.assertFalse(res["ok"])
        self.assertEqual(f.ime, "com.original/.Kb")

    def test_never_sends(self):
        f = FakeAdb()
        run(f)
        self.assertFalse(any(a[0] == "input" for a in f.log))


if __name__ == "__main__":
    unittest.main()
