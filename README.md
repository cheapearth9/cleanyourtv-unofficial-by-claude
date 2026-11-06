# CleanYourTV Phone (unofficial)

An Android app that does what the [cleanyourtv](https://github.com/Gathin23/cleanyourtv) project does, from your phone:
connect to an Android TV / TV box over Wi-Fi ADB, disable its ad and tracker apps (nothing is uninstalled), and install a
clean launcher as the Home screen. It is built to run on **Android 7.0 / 7.1 (API 24 / 25)** TVs as well as newer ones,
and to work on **any** model: 345 per-TV lists are bundled, and TVs that are in none get a reviewable suggestion
based on what the other TVs disable.

## Build the APK

No Android SDK is needed on your computer if you use GitHub:

1. Create a GitHub repository and push this folder to it.
2. Open the **Actions** tab, run **build-apk** (it also runs on every push).
3. Download the artifact `cleanyourtv-phone-apk` and install the `.apk` on your phone (allow "install unknown apps").

Or locally with JDK 17 + Android SDK 34: `./gradlew :phone:assembleRelease`, the APK is in
`phone/build/outputs/apk/release/`. The build compiles the patched launcher first and embeds it in the phone app.

## Use it

1. On the TV: Settings > About > press OK on *Build* 7 times; Developer options > turn on **USB debugging**
   (and *ADB over network* if the TV has that toggle). Keep the TV on, same Wi-Fi as the phone.
2. Open the app, tap **Find TVs on this Wi-Fi** (or type the TV's IP), **Connect**. Accept *Allow USB debugging?* on the TV
   with *Always allow* ticked.
3. Review: matched TV list, launcher options, and the checklist of apps. Tick/untick, then **Start**.
4. When you are done, turn USB debugging off on the TV and reboot it so port 5555 closes.

**Undo:** *Undo / restore this TV* re-enables everything recorded, makes the old launcher Home again and optionally
removes the new launcher. The record lives on the TV, so any phone can do it. With no record there is an
"enable all disabled apps" fallback.

## AirPlay (optional)

Tick **AirPlay** in the phone app and the TV can receive screen mirroring, video and music from iPhones, iPads and Macs.
The launcher gets an **AirPlay** button, an **on/off switch** (knob right and blue = on, left and gray = off), an
**AirPlay settings** button (every setting of the receiver, plus its log at the bottom) and, at the bottom of the home
screen, an app drawer. Switching it on or off starts or stops the server in the background: no screen flashes.

The receiver is the app in [`airplay/`](airplay/): **a fork of
[jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) (GPL-3.0)**, which is itself built on
[UxPlay](https://github.com/FDH2/UxPlay) (GPL-3.0), [libplist](https://github.com/libimobiledevice/libplist),
[OpenSSL](https://www.openssl.org/), [FFmpeg](https://ffmpeg.org/) and [Oboe](https://github.com/google/oboe). All credit for
the receiver goes to **jqssun** and those projects; the fork only adds a control door so the launcher can start, stop and
configure it without opening it (see [`airplay/README.md`](airplay/README.md) for exactly what changed). It has its own
application id (`tv.clean.airplay`), so it replaces the original app on the TV instead of clashing with it.

It is built by its own workflow (**build-airplay**, slow: it compiles OpenSSL and FFmpeg) and published as the rolling release
`airplay-latest`, which the phone app downloads when AirPlay is ticked. The first time, run **build-airplay** once.

## Newer WebView (optional, needs root)

Old Android TV boxes ship an old WebView (Chrome 52 on Android 7), which breaks or slows some apps. In the phone app, Apps, "Update the TV's WebView" lets you pick a WebView APK from your phone (for Android 7, the generic `com.android.webview` build for API 24 works). The TV must allow `adb root`. The app saves the TV's own WebView first and puts it back if the new one does not work. Experimental: tested against a pretend TV only.

## Safety rules (same as upstream, plus a few)

Never disabled: Android core and TV middleware, HDMI/AV/tuner drivers (apps holding `TV_INPUT_HARDWARE`), the app that
opens channel links, Settings, the setup wizard when it owns your saved Wi-Fi, any launcher through the list. The new
launcher is started and checked *before* the old one is disabled; if Home does not move, the old one is switched
back on automatically.

## Limits

* Network ADB on plain port 5555 only. Android 11+ "Wireless debugging" ports (TLS) are not supported; most TVs expose 5555.
* Samsung (Tizen) and LG (webOS) have no ADB. Fire TV: clean-up only, Fire OS keeps its own Home.
* Some TVs refuse `pm disable-user` for certain packages; the app reports these instead of pretending.

## What is and is not verified

Verified here: the ADB client (handshake, RSA auth incl. public-key encoding, shell, file push) against a strict mock adbd;
the clean-up / hand-over / restore logic against fake Android 7 and Android 14 TVs; the whole phone app compiles against the
API 24 platform jar; the patched launcher has no unguarded Android 8+ API use. Run `tools/run-tests.sh`.

Not verified here (no Android SDK or real TV in the sandbox): the Gradle build itself, the UI on a physical phone, and the
launcher running on a physical Android 7 TV. Expect to fix small things on first real run; tell me what the log says.

Licence: MIT for the phone app and the launcher. Contains code from cleanyourtv (MIT, © 2026 Gathin), see
`LICENSE-cleanyourtv-MIT`. The launcher font is OFL (`launcher/FONT-LICENSE-OFL.txt`). The AirPlay receiver in `airplay/` is a
separate program under **GPL-3.0** (`airplay/LICENSE`, © jqssun and the UxPlay authors): the phone app and the launcher
only talk to it through Android broadcasts and install it as its own APK, they do not contain its code. Not affiliated with the
cleanyourtv author or with jqssun.
