# AirPlay receiver (cleanyourtv fork)

This folder is a **fork of [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)** ("AirPlay
Receiver for Android", GPL-3.0), taken at commit `a46cca0` ("decoder"). The original README is kept as
[`README-upstream.md`](README-upstream.md). **All the real work (the AirPlay protocol, the video and audio pipeline, the
TV interface) is jqssun's**, built on [UxPlay](https://github.com/FDH2/UxPlay) by the UxPlay authors; this fork only adds the
small changes below. Licence: **GNU GPL v3** (`LICENSE`), like the original. The rest of this repository (MIT) does not contain
this code: it installs this app as a separate APK and talks to it through Android broadcasts.

## What this fork changes

* **Own identity.** `applicationId` is `tv.clean.airplay` (the original's is `io.github.jqssun.airplay`), so it installs next to
  the original instead of trying to update it (an update would need the original author's signing key). The Kotlin package
  names are untouched, which keeps merging upstream changes easy. Version names end in `-cyt<N>`.
* **A control door for the launcher** (`SettingsReceiver`). Besides the original `SET_SETTING` / `LIST_SETTINGS`, it now
  answers `START`, `STOP`, `STATUS`, `LOGS` and `CLEAR_LOGS`, so the cleanyourtv launcher can switch the server on and off,
  read its state and show its log **without ever opening a screen** (the original had no way to stop the server from outside,
  which is why the launcher used to flash the app on screen). The door is protected by the permission
  `tv.clean.airplay.permission.CONTROL` (`signature|privileged|development`) instead of `android.permission.DUMP`; only
  `pm grant` over ADB can hand it to another app, which the cleanyourtv phone app does.
* **A log the service keeps itself** (`LogBuffer`): the original only collects log lines while its own screen is open.
* **Builds for the TV boxes this project targets:** arm64-v8a and armeabi-v7a only (no x86), signed with a fixed public key
  (`app/cyt-airplay.keystore`, a sideload key, not a secret) so each build installs over the last one.

Everything else is unchanged, including the app's own Settings and Logs screens.

## Building

The native code needs the four git submodules (UxPlay, libplist, OpenSSL-cmake, FFmpeg), pinned in `/.gitmodules`:

    git submodule update --init --recursive
    cd airplay && ./gradlew assembleRelease     # JDK 21, Android SDK 37, NDK 27.0.12077973, CMake 3.22.1

The **build-airplay** workflow does exactly this and publishes `airplay.apk` as the rolling release `airplay-latest`.

## Control door (for other apps)

Send an explicit ordered broadcast to `tv.clean.airplay/io.github.jqssun.airplay.SettingsReceiver` (the caller needs
`tv.clean.airplay.permission.CONTROL`). The answer is the result code (`-1` = ok, `0` = refused) and the result data:

| action (`io.github.jqssun.airplay.` + ...) | extras | answer |
|---|---|---|
| `SET_SETTING` | `key`, `value` (text) | `key=value`, or why it was refused |
| `LIST_SETTINGS` | | JSON `{"key": {"value": ..., "default": ...}, ...}` |
| `START` / `STOP` | | `started` / `stopped` |
| `STATUS` | | JSON `{"running": bool, "connections": n}` |
| `LOGS` | | the newest log lines (at most 60 000 characters) |
| `CLEAR_LOGS` | | `cleared` |

## Credits

jqssun (the receiver), the UxPlay authors (the AirPlay protocol implementation), the libplist, OpenSSL, FFmpeg and Oboe
projects, and the [cleanyourtv](https://github.com/Gathin23/cleanyourtv) author (the launcher this repository is built around).
