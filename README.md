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

Licence: MIT. Contains code from cleanyourtv (MIT, © 2026 Gathin), see `LICENSE-cleanyourtv-MIT`. The launcher font is OFL
(`launcher/FONT-LICENSE-OFL.txt`). Not affiliated with the cleanyourtv author.
