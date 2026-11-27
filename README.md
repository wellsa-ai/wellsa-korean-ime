# Wellsa Korean IME

**Android only.** Type any Unicode text, including Korean, into the focused text field of an Android phone over adb. `adb shell input text` cannot do this: it only handles ASCII.

It consists of a tiny keyboard-less input method (an APK with no permissions and no network) and a host-side Python wrapper that switches to it, commits the text, and always switches back to your original keyboard.

## Build

Requirements: Android 14 (API 34) or newer on the phone; to build, JDK 17+, Android SDK with `platforms/android-35` and `build-tools/35.0.0`. No Gradle.

```
export ANDROID_HOME=$HOME/Android/Sdk   # macOS Android Studio default: $HOME/Library/Android/sdk
android/test.sh        # device-free JVM unit tests of the decision logic
android/build.sh       # produces android/out/aligned.apk (unsigned)
```

To sign, create your own key once and pass it by environment variable (never commit it):

```
keytool -genkeypair -v -keystore my.jks -alias ime -keyalg RSA -keysize 4096 -validity 3650
KEYSTORE=$PWD/my.jks KS_ALIAS=ime KS_PASS_FILE=/path/to/password.txt android/build.sh
```

## Install and enable

```
adb install android/out/wellsa-korean-ime-0.3.2.apk
adb shell ime enable ai.wellsa.koreanime/.TextIme
```

Opening the app from the launcher shows a short explanation screen (what it is, the three setup steps, a button to Languages & input settings, the privacy policy and source links). It needs no permissions and no network.

Play Protect may warn about an unknown app; that is expected for a self-built APK.

## Use

```
printf '안녕하세요 hello' | python3 host/ime_input.py --pkg com.example.app
```

Focus the target text field first. `--pkg` must be the package of the app that owns the focused field. Output is one JSON line with `ok`, the text length and a 12-character hash; the text itself is never printed. Optional: `--field-res-id`, `--field-bounds`, `--no-verify`, `--deadline`. Environment: `ANDROID_SERIAL`, `ADB`, `IME_LOCK_FILE` (refuse concurrent runs), `IME_STATE_DIR`.

Tests for the host wrapper need no device: `python3 -m unittest discover -s tests -v`

## Security model

- On Android 14+ a non-exported receiver is not reached even by `adb shell am broadcast`. The receiver is therefore exported but protected by the signature-level permission `INJECT_EVENTS`, which ordinary apps cannot obtain (it is held by shell/system). When the platform reports the sender UID, the code additionally accepts only the shell UID (2000).
- The commit happens only while this IME is the active one and an input session exists.
- The request must name a package equal to the currently focused editor's package and the editor's `fieldId`, otherwise it is rejected. A late request cannot land in a different app or another field of the same app.
- The IME itself also refuses password fields (result code 4), independent of the host wrapper.
- The host wrapper refuses password fields, requires the foreground app to equal `--pkg`, never presses Enter or send, reads the text from stdin only (not argv, logs or files), prints only length and hash, and restores the original keyboard in `finally`, including on timeout or signal.
- Result is a numeric broadcast result code only: 0 rejected (sender), 1 target mismatch, 2 IME inactive or no input session, 3 invalid text, 4 password field, 10 success.
- Known limit: the base64 text is briefly visible in the device's process list while `am broadcast` runs.

## Limitations

- Web fields (browser pages) expose no field node, so read-back is impossible. The wrapper reports `readback: unverified_web`; verify with a screenshot.
- Web fields have `fieldId` -1 (`View.NO_ID`), so the binding is per app only: the request is tied to the app package, not to one specific field inside the page. The caller must confirm on screen that the text went into the intended field.
- Some apps hide field text from accessibility; use `--no-verify` there.
- Max 2000 characters per call. Device must be unlocked and awake. Requires Android 14+ (minSdk 34). Tested only on a few Android 15 devices.

## License

Apache License 2.0, see `LICENSE` and `NOTICE`. Copyright 2026 Wellsa AI Co., Ltd.

## 한국어 요약

Android 전용입니다. adb 로 한글을 포함한 모든 유니코드 문자를 안드로이드 입력칸에 넣는 도구입니다. `adb shell input text` 는 한글을 보낼 수 없어서 만들었습니다. 키보드 화면이 없는 작은 입력기 앱(권한 0, 네트워크 0)과 호스트용 파이썬 스크립트로 구성되며, 스크립트는 입력 뒤 원래 키보드로 항상 되돌립니다. 비밀번호 칸은 거부하고, 전송(엔터)은 하지 않으며, 본문은 표준입력으로만 받고 출력에는 길이와 해시만 남깁니다. 웹 입력칸은 읽어 볼 수 없으므로 화면 캡처로 확인하세요. 빌드와 사용법은 위 영문 설명을 따르면 됩니다.
