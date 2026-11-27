#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Type arbitrary Unicode text (including Korean) into the focused Android text field over adb.

`adb shell input text` cannot send non-ASCII characters. This wrapper temporarily switches the
device to the Android Korean IME app, commits the text through its adb-only broadcast receiver,
and always switches back to the original IME.

Flow: optional device lock -> gate (one device, unlocked, awake) -> remember original IME ->
check the focused editor (package must match --pkg, password fields refused, foreground package
must match) -> `ime set` our IME -> re-check the editor session -> `am broadcast` (result code) ->
optional read-back via uiautomator -> finally: restore the original IME.

Safety model:
  * Text is read from stdin only (never argv, never logged, never stored).
  * Output JSON contains only the text length and a short hash.
  * The wrapper never sends/submits: no Enter key, no send button.
  * Password fields are refused; the foreground app must equal --pkg.
  * The original IME is restored in `finally` (success, error, timeout or signal).

Environment:
  ANDROID_SERIAL   device serial (optional if exactly one device is attached)
  ADB              path to the adb binary (default: adb)
  IME_LOCK_FILE    optional lock file path; if set, concurrent runs on the same file are refused
  IME_STATE_DIR    directory for the restore marker (default: system temp dir)
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import tempfile
import time

PACKAGE = "ai.wellsa.koreanime"
IME_ID = PACKAGE + "/.TextIme"
RECEIVER = PACKAGE + "/.InputReceiver"
ACTION = PACKAGE + ".COMMIT"
MAX_CHARS = 2000
ADB_TIMEOUT = 8.0
RESULT_OK = 10


class Stop(Exception):
    def __init__(self, stage, why):
        super().__init__(f"{stage}:{why}")
        self.stage, self.why = stage, why


def validate_text(text: str) -> str:
    """Strip one trailing newline; reject empty, too long, control characters and lone surrogates."""
    if text.endswith("\n"):
        text = text[:-1]
    if not (1 <= len(text) <= MAX_CHARS):
        raise Stop("text", "length")
    for ch in text:
        o = ord(ch)
        if (o < 0x20 and ch != "\n") or o == 0x7F or 0xD800 <= o <= 0xDFFF:
            raise Stop("text", "control_char")
    return text


class Adb:
    def __init__(self, serial=None, adb=None, timeout=ADB_TIMEOUT):
        self.serial = serial or os.environ.get("ANDROID_SERIAL") or None
        self.adb = adb or os.environ.get("ADB") or "adb"
        self.timeout = timeout

    def _base(self):
        return [self.adb] + (["-s", self.serial] if self.serial else [])

    def run(self, *args) -> str:
        r = subprocess.run(self._base() + list(args), stdin=subprocess.DEVNULL,
                           capture_output=True, text=True, timeout=self.timeout)
        if r.returncode != 0:
            raise Stop("adb", args[0])
        return r.stdout

    def shell(self, *a) -> str:
        return self.run("shell", *a)

    def devices(self) -> str:
        return subprocess.run([self.adb, "devices", "-l"], stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, timeout=self.timeout).stdout


def _num(v: str) -> int:
    return int(v, 16) if v.lower().startswith("0x") else int(v)


def parse_editor(dump: str):
    """Extract the *current* editor (package, fieldId, inputType) from `dumpsys input_method`.

    Preferred: the last StartInput history entry (`targetWin=... [pkg]` followed by an
    `inputType=... fieldId=...` line). Fallback: `packageName=... fieldId=...` on the same or the
    next line. Taking the first `packageName=` would pick up the IME package list, so it is avoided.
    Returns None if nothing is found."""
    entries = re.findall(
        r"targetWin=[^\n]*?\[([A-Za-z0-9_.]+)\][^\n]*\n[^\n]*\n?[^\n]*?"
        r"inputType=(0x[0-9a-fA-F]+|\d+)[^\n]*?fieldId=(0x[0-9a-fA-F]+|-?\d+)", dump)
    if entries:
        pkg, it, fid = entries[-1]
        return {"pkg": pkg, "field": _num(fid), "input_type": _num(it)}
    m = (re.search(r"packageName=([A-Za-z0-9_.]+)[^\n]*?fieldId=(0x[0-9a-fA-F]+|-?\d+)", dump)
         or re.search(r"packageName=([A-Za-z0-9_.]+)[^\n]*\n[^\n]*?fieldId=(0x[0-9a-fA-F]+|-?\d+)", dump))
    if not m:
        return None
    it = re.search(r"inputType=(0x[0-9a-fA-F]+|\d+)", dump[m.start():m.end() + 200])
    return {"pkg": m.group(1), "field": _num(m.group(2)), "input_type": _num(it.group(1)) if it else 0}


def is_password(input_type: int) -> bool:
    """True for text/web/visible password variations and numeric password."""
    cls, var = input_type & 0xF, input_type & 0xFF0
    return (cls == 1 and var in (0x80, 0x90, 0xE0)) or (cls == 2 and var == 0x10)


def parse_result_code(out: str):
    m = re.search(r"result=(-?\d+)", out)
    return int(m.group(1)) if m else None


def _unescape(s: str) -> str:
    return (s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", '"').replace("&#10;", "\n"))


def focused_node(xml: str):
    """Return the first focused uiautomator node as a dict, or None."""
    m = re.search(r"<node[^>]*\bfocused=\"true\"[^>]*>", xml)
    if not m:
        return None
    n = m.group(0)

    def g(k):
        mm = re.search(r"\b" + k + r"=\"([^\"]*)\"", n)
        return mm.group(1) if mm else ""
    return {"res_id": g("resource-id"), "bounds": g("bounds"), "cls": g("class"), "text": _unescape(g("text"))}


class Runner:
    def __init__(self, adb, state_dir=None, clock=time.monotonic, sleep=time.sleep, deadline_s=60.0,
                 lock_file=None):
        self.adb, self.clock, self.sleep, self.deadline_s = adb, clock, sleep, deadline_s
        self.state_dir = state_dir or os.environ.get("IME_STATE_DIR") or os.path.join(
            tempfile.gettempdir(), f"android-korean-ime-{os.getuid()}")
        self.lock_file = lock_file or os.environ.get("IME_LOCK_FILE")
        self.cancel = False
        self.res = {"ok": False, "io_ok": False, "stages": []}
        self.deadline = 0.0

    def foreground(self) -> str:
        m = re.search(r"mCurrentFocus=Window\{[^}]*?\s([A-Za-z0-9_.]+)/", self.adb.shell("dumpsys", "window"))
        return m.group(1) if m else ""

    def check(self, stage):
        self.res["stages"].append(stage)
        if self.cancel:
            raise Stop(stage, "cancelled")
        if self.clock() > self.deadline:
            raise Stop(stage, "timeout")

    def _pick_serial(self):
        rows = [l.split() for l in self.adb.devices().splitlines()[1:] if l.strip()]
        ready = [r[0] for r in rows if len(r) > 1 and r[1] == "device"]
        if self.adb.serial:
            if self.adb.serial not in ready:
                raise Stop("gate", "device")
        elif len(ready) == 1:
            self.adb.serial = ready[0]
        else:
            raise Stop("gate", "device")

    def _dump_xml(self) -> str:
        d = f"/data/local/tmp/ime-{os.urandom(4).hex()}"
        try:
            self.adb.shell(f"mkdir -m 700 {d}")
            self.adb.shell("uiautomator", "dump", f"{d}/ui.xml")
            return self.adb.shell("cat", f"{d}/ui.xml")
        finally:
            try:
                self.adb.shell("rm", "-rf", d)
            except Stop:
                pass

    def _lock(self):
        if not self.lock_file:
            return None
        import fcntl
        fd = os.open(self.lock_file, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            os.close(fd)
            raise Stop("lock", "busy")
        return fd

    def run(self, expect_pkg: str, text: str, field_res_id: str = "", field_bounds: str = "",
            verify: bool = True) -> dict:
        text = validate_text(text)
        self.res.update({"expect_pkg": expect_pkg, "text_len": len(text),
                         "text_sha12": hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]})
        self.deadline = self.clock() + self.deadline_s
        os.makedirs(self.state_dir, mode=0o700, exist_ok=True)
        orig = None
        marker = os.path.join(self.state_dir, "ime-restore.json")
        lock_fd = None
        try:
            lock_fd = self._lock()
            self.check("gate")
            self._pick_serial()
            if "mDreamingLockscreen=false" not in self.adb.shell("dumpsys", "window"):
                raise Stop("gate", "keyguard")
            if "mWakefulness=Awake" not in self.adb.shell("dumpsys", "power"):
                raise Stop("gate", "asleep")
            orig = self.adb.shell("settings", "get", "secure", "default_input_method").strip()
            if not orig or orig == IME_ID:
                raise Stop("gate", "orig_ime")
            self.check("target")
            ed = parse_editor(self.adb.shell("dumpsys", "input_method"))
            if ed is None:
                raise Stop("target", "no_editor")
            if ed["pkg"] != expect_pkg:
                raise Stop("target", "pkg_mismatch")
            if is_password(ed["input_type"]):
                raise Stop("target", "password_field")
            if self.foreground() != expect_pkg:
                raise Stop("target", "foreground_mismatch")
            ff = focused_node(self._dump_xml())
            web = ff is None or (ff["res_id"] == "" and ff["cls"] != "" and not ff["cls"].endswith("EditText"))
            if web:
                # Web views expose no field node: read-back is impossible, the caller must verify visually.
                ff = None
                self.res["web_field"] = True
            if field_res_id and (ff is None or ff["res_id"] != field_res_id):
                raise Stop("target", "field_res_id_mismatch")
            if field_bounds and (ff is None or ff["bounds"] != field_bounds):
                raise Stop("target", "field_bounds_mismatch")
            if ff is not None and ff["text"] != "":
                self.res["field_had_text_or_hint"] = True
            self.check("switch")
            # Write the restore marker BEFORE switching so a crash after `ime set` can still be undone.
            fdm = os.open(marker, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            os.write(fdm, json.dumps({"orig_ime": orig, "at": time.time()}).encode())
            os.close(fdm)
            self.adb.shell("ime", "set", IME_ID)
            self.sleep(1.0)
            if self.adb.shell("settings", "get", "secure", "default_input_method").strip() != IME_ID:
                raise Stop("switch", "not_active")
            ed2 = parse_editor(self.adb.shell("dumpsys", "input_method"))
            if ed2 is None or ed2["pkg"] != ed["pkg"] or ed2["field"] != ed["field"]:
                raise Stop("switch", "focus_changed")
            self.check("commit")
            b64 = base64.b64encode(text.encode("utf-8")).decode("ascii")
            out = self.adb.shell("am", "broadcast", "-n", RECEIVER, "-a", ACTION,
                                 "--es", "pkg", ed["pkg"], "--es", "field", str(ed["field"]), "--es", "b64", b64)
            code = parse_result_code(out)
            self.res["result_code"] = code
            if code != RESULT_OK:
                raise Stop("commit", f"code_{code}")
            if web or not verify:
                self.res["readback"] = "unverified_web" if web else "skipped"
                self.res["io_ok"] = True
                return self.res
            self.check("readback")
            self.sleep(0.8)
            got = focused_node(self._dump_xml())
            match = got is not None and text in got["text"]
            self.res["readback_match"] = int(match)
            if not match:
                raise Stop("readback", "mismatch" if got is not None else "no_focused_text")
            self.res["io_ok"] = True
            return self.res
        except Stop as e:
            self.res.update({"stop_stage": e.stage, "stop_why": e.why})
            return self.res
        finally:
            restored = None
            if orig:
                try:
                    cur = self.adb.shell("settings", "get", "secure", "default_input_method").strip()
                    if cur != orig:
                        self.adb.shell("ime", "set", orig)
                        self.sleep(0.8)
                        cur = self.adb.shell("settings", "get", "secure", "default_input_method").strip()
                    restored = cur == orig
                except Exception:
                    restored = False
                self.res["ime_restored"] = restored
                if restored:
                    try:
                        os.unlink(marker)
                    except FileNotFoundError:
                        pass
                else:
                    self.res["restore_marker"] = marker  # kept so a later run or a human can restore
            self.res["ok"] = bool(self.res.get("io_ok")) and bool(self.res.get("ime_restored"))
            if lock_fd is not None:
                os.close(lock_fd)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Type text from stdin into the focused Android text field.")
    ap.add_argument("--pkg", required=True, help="package name of the app that must own the focused field")
    ap.add_argument("--field-res-id", default="", help="optional: require this focused resource-id")
    ap.add_argument("--field-bounds", default="", help="optional: require these focused bounds, e.g. [94,1198][846,1287]")
    ap.add_argument("--no-verify", action="store_true", help="skip read-back (apps whose fields hide their text)")
    ap.add_argument("--deadline", type=float, default=60.0, help="overall time limit in seconds")
    a = ap.parse_args(argv)
    r = Runner(Adb(), deadline_s=a.deadline)

    def on_sig(s, f):
        r.cancel = True
    signal.signal(signal.SIGINT, on_sig)
    signal.signal(signal.SIGTERM, on_sig)
    try:
        text = sys.stdin.buffer.read().decode("utf-8")
        res = r.run(a.pkg, text, field_res_id=a.field_res_id, field_bounds=a.field_bounds, verify=not a.no_verify)
    except Stop as e:
        res = {"ok": False, "stop_stage": e.stage, "stop_why": e.why}
    except UnicodeDecodeError:
        res = {"ok": False, "stop_stage": "text", "stop_why": "not_utf8"}
    print(json.dumps(res, ensure_ascii=False))
    return 0 if res.get("ok") else 2


if __name__ == "__main__":
    sys.exit(main())
