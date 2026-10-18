# Disposable Android validation

Use the dedicated API 35 x86_64 emulator, never a phone or existing personal profile. The instrumentation refuses real devices and refuses to overwrite an existing identity or VLESS profile.

The software emulator has no KVM. Start with two virtual CPUs and 2560 MB RAM. Before installing/running tests, set the actual Android 15 watchdog key:

```sh
adb shell settings put global system_server_watchdog_timeout_ms 600000
adb shell settings get global system_server_watchdog_timeout_ms
```

`watchdog_timeout_millis` is not the Android 15 setting name. It has no effect. The framework constant `Settings.Global.WATCHDOG_TIMEOUT_MILLIS` maps to `system_server_watchdog_timeout_ms`. With the default 60-second timeout the CPU-bound software emulator may restart `system_server` during boot or APK installation. Logs must confirm the new timeout took effect; a completed boot property alone does not prove system services survived.

To reduce emulator overhead, disable its optional Safety Center (`device_config put privacy safety_center_is_enabled false`), Bluetooth package (`pm disable-user --user 0 com.google.android.bluetooth`), and animations. These settings are for this disposable machine only. Offline ADB package verification can be disabled for local test installation; release APK signatures must still be independently checked with apksigner and zipalign.

After installing the release APK and release instrumentation APK, clear only the disposable application's data and grant test app-ops:

```sh
adb shell pm clear ru.bluenet.vpn
adb shell appops set ru.bluenet.vpn ACTIVATE_VPN allow
adb shell appops set ru.bluenet.vpn SYSTEM_ALERT_WINDOW allow
adb shell pm grant ru.bluenet.vpn android.permission.POST_NOTIFICATIONS
adb shell am instrument -w ru.bluenet.vpn.test/ru.bluenet.vpn.RuntimeSmokeInstrumentation
```

Success requires all 28 `PASS` groups, `tests_passed=28`, no `failure=`, and `INSTRUMENTATION_CODE: -1`. ADB's process exit code alone is insufficient. Screenshots and profiles in this emulator are synthetic. Compare the installed APK byte-for-byte with the public artifact. Inspect the main screen, chat and expanded overlay captures before packaging. Shut the emulator down cleanly afterwards.

Real Radmin servers are not contacted by the instrumentation; the synthetic identity points to loopback. Two in-process chat engines exchange synthetic authenticated-source test packets and validate delivery ACKs; this does not replace a two-phone field test.

The 0.5.1 layout group also covers 320dp width, RU/EN, light/dark, 100/150/200% font sizes, 400dp/240dp chat height and all six offline release entries. Synthetic screenshots call TextView.onPreDraw before drawing, matching normal attached text rendering.
