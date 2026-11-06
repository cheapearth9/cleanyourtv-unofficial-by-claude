# What changed from upstream cleanyourtv

The launcher in `launcher/` is upstream's launcher (MIT, © 2026 Gathin) with these edits so that it installs and runs on
Android 7.0 / 7.1 (API 24 / 25) instead of Android 8+:

| File | Change |
|---|---|
| `launcher/app/build.gradle` | `minSdk 26` -> `24`; v1 + v2 APK signing (Nougat installers); unit-test dependency removed |
| `UiFont.java` | `Resources.getFont` (API 26) -> `ResourcesCompat.getFont`, falls back to system sans |
| `MainActivity.java` | `PackageManager.getChangedPackages` (API 26): below Android 8 the tiles are simply rebuilt each time |
| `WallpaperPlayer.java` | `Bitmap.Config.HARDWARE` (API 26): below Android 8 the software bitmap is used |
| `WallpaperCaptions.java`, `GuardKeep.java`, `Order.java` | `Files.readAllLines` / `String.join` (API 26) replaced with `BufferedReader` / `TextUtils.join` |
| `MainActivity.java` (`tvInputManager()`) | Boxes that are not Android TV certified hand out a `TvInputManager` whose service is null, so `getTvInputList()` threw `NullPointerException` in `onResume`. Every use now goes through a helper that treats such a manager as "no inputs" |
| `SystemSettings.java`, `AndroidManifest.xml` | On boxes that carry both a phone-style Settings app and the AOSP TV one (non-certified Amlogic/Rockchip boxes), the standard Settings actions opened the phone-style app. The TV settings app (`com.android.tv.settings`) is now tried first, then the normal lookup |
| `res/values/config.xml` | `app_library`, `library_button`, `app_library_selection` are ON by default (upstream: off): apps that only declare the phone LAUNCHER category (ZArchiver, file managers, players) are not on Home, and the App library lists every launchable app with OK adding / removing its Home shortcut |
| `time/*.java` (new) and every file importing `java.time.*` | **`java.time` does not exist on Android 7** (it arrived in API 26): the launcher crashed at start with `NoClassDefFoundError: java.time.LocalDate`. A small stand-in in `com.gathin.tvhome.time` (only what the launcher calls) replaces it; `tools/jtest/TimeCompatTest.java` checks it against the real `java.time` (42,883 comparisons). Core-library desugaring is no longer used |

I compiled every launcher source against the API 24 platform jar (with the JDK's `java.*` taken out of the picture, which
an earlier check forgot to do): the only symbols missing there are the ones above plus calls that upstream already guards
with `Build.VERSION.SDK_INT` (TIRAMISU, P, Q, GET_SIGNING_CERTIFICATES, ...).

Known cosmetic difference on Android 7: layout XML uses `android:fontFamily="@string/font_*"` pointing at font resources,
which Android 8+ resolves natively. Android 7 ignores them for XML-declared text, so labels declared in layouts use the
system sans; text built in code (via `UiFont`) still uses the bundled Outfit font.

Not run on a real Android 7 device from here: see README "What is and is not verified".

The phone app (`phone/`) is new code. Its debloat logic is a port of upstream `tools/disable.sh`, `restore.sh`,
`theme.sh` and `launcher/build-and-install.sh` with the same safety checks, plus:
* works without `cmd package ...` sub-commands, `sed -E`, `grep -q`, `content write`, which Android 7's shell lacks
  (it reads `dumpsys package` and parses it on the phone);
* a cross-TV scan (packages that many of the 345 TV lists disable, limited to apps installed on this TV) so TVs
  that are in no list still get a sensible, reviewable suggestion;
* an undo record stored on the TV (`/data/local/tmp/cleanyourtv-record.txt`) so any phone can restore it.
* a real "did it survive" check for the launcher (`LaunchCheck`): `am start -W` reports success for an app that dies a
  moment later, so the app now clears the log, starts the launcher, and watches the screen and the log for about nine
  seconds; a crash is shown with its cause and the old launcher is left alone;
* a safety timer on the TV (`Watchdog`): before the old launcher is switched off, a small script is started on the TV that
  switches it back on (and presses Home) unless the phone confirms within three minutes, so a dropped connection can never
  leave a TV without a Home screen;
* a connection that survives: an idle timeout instead of a fixed total one (a slow box that keeps printing is fine), automatic
  reconnect and re-send when the link drops, Wi-Fi and CPU wake locks and traffic pinned to Wi-Fi while connected;
* big `dumpsys` output is filtered on the TV with `grep -E` before it crosses Wi-Fi (the intent table, the per-package
  permission check), with a fallback to the full dump when the TV has no grep; a check that times out keeps the app
  instead of guessing it is safe to disable;
* Diagnostics: each part runs on its own and is shown as soon as it finishes, so one slow command cannot hide the crash log.
* an Apps screen: every installed app with a search box, "Show system apps" and "Show removed system apps" switches, per-app
  Uninstall (user apps), or Disable / Uninstall for this TV (system apps: `pm uninstall -k --user 0`, brought back with
  `pm install-existing`), with the same protections as the clean-up (core software, launchers, Play-services family);
* install an APK from the phone onto the TV: the file is streamed (no size limit from phone memory), checked to be a plain APK
  (bundles such as .xapk are refused with an explanation), installed with `pm install -r -g`, and failures are explained.
* a "Hide the on-screen buttons and the notification bar" switch for boxes that show a Home/Back button bar and a status bar
  (`SystemBars`): it sets `settings put global policy_control immersive.full=*:immersive.preconfirms=*`, which survives restarts.
  The switch starts in whatever state the TV is in; changing it and pressing Make it clean applies it, and turning it off
  restores the value the setting had before (kept in the undo record, which Undo / restore also honours). Android 11+ ignores
  the setting, so the switch is not shown there.
* Google Play (`com.android.vending`) can be uninstalled from the Apps screen, after a warning: replacing it with a newer APK
  often needs the old one removed first. Updated system apps also get "Remove updates (back to the built-in version)".
* an AirPlay switch ("AirPlay: get an AirPlay button on the launcher"): on, this phone downloads the open-source AirPlay
  Receiver (`io.github.jqssun.airplay`, jqssun/android-airplay-server, GPL-3.0) from that project's own GitHub releases page
  (`releases/latest/download/app-release.apk`; it is never bundled in this repo or app), checks it really is that app, installs
  it on the TV (`pm install -r -g`) and sets the global setting `cyt_airplay` to 1; an "AirPlay" pill then appears on the launcher
  left of Wi-Fi and opens the receiver. Off: the setting goes back to 0 and the pill disappears (the receiver app is left
  installed; remove it from the Apps screen). The launcher shows the pill only while the receiver is installed
  (`config.xml airplay_button`, theme key `airplay_button`, or the `cyt_airplay` setting), and Undo / restore clears the setting.
  The pill's icon is the Material Icons "airplay" glyph (Apache License 2.0), not Apple's logo.
* a "Clear all" pill on the launcher, left of the AirPlay / Wi-Fi pill (`ClearAll`, config.xml `clear_all_button`, on by default,
  theme key `clear_all_button`): it calls `ActivityManager.killBackgroundProcesses` for every app with a launcher entry (the
  `KILL_BACKGROUND_PROCESSES` permission is a normal one, granted at install) and then shows how much memory is free. Android
  only ends background processes this way, so an app that is showing, playing or running a foreground service (a video, music,
  an AirPlay session) is left alone; this launcher, the Home apps and the keyboard in use are skipped. Meant for small boxes.
  The pill's icon is the Material Icons "clear_all" glyph (Apache License 2.0).
* the "Clear all" texts (the pill and its two messages) are translated into all 28 languages the launcher ships, not only English;
  the name "AirPlay" stays as is. While the AirPlay pill is on, the AirPlay receiver's own tile leaves the app grid (it is
  reached from the top bar, like a `top_bar_apps` app); with the pill off it is an ordinary app tile again.
* the AirPlay pill now has an on/off switch to its right (`SwitchView`: a canvas-drawn pill, blue with the knob right = on, gray
  with the knob left = off; no bitmaps, animates only while it flips) and an "AirPlay settings" pill after that
  (`AirPlaySettingsDialog`: "Start with the TV", "Keep running in the background", "Open the AirPlay app"). The receiver has no
  stop command, so `AirPlayControl` drives it the only ways it offers: its `SettingsReceiver` broadcast (guarded by DUMP) sets
  `boot_auto_start`, `auto_start` and `run_in_background`; on = settings on plus its `BootReceiver` start broadcast; off =
  settings off, then the receiver is opened for about a second and the launcher brought back, which makes it stop itself.
  The launcher declares `android.permission.DUMP`, a development permission that only `pm grant` can give: the phone app grants it
  when it installs the launcher and when AirPlay is switched on. Without it the switch can turn AirPlay on but not off.
* the bottom "Apps" button now opens an app drawer (`AppDrawerDialog`: one full-screen `GridView` of every launchable app,
  recycled cells, a 30-icon cache, list loaded off the main thread and everything released on close). The 9-dots button in the top
  bar still opens the app library. New texts are translated into all 28 languages.
* the AirPlay receiver is now this repository's own fork (`/airplay`, GPL-3.0, from jqssun/android-airplay-server at `a46cca0`;
  credited in README.md and airplay/README.md), application id `tv.clean.airplay`. It adds a control door (`SettingsReceiver`:
  START, STOP, STATUS, LOGS, CLEAR_LOGS besides the original SET_SETTING / LIST_SETTINGS), protected by its own development
  permission `tv.clean.airplay.permission.CONTROL`, so the launcher's AirPlay switch starts and stops the server in the
  background and **nothing flashes on screen any more** (this replaces the earlier "open the receiver for a second" trick and
  the `DUMP` permission). The launcher declares that permission; the phone app grants it with `pm grant` when it installs the
  launcher or switches AirPlay on, and removes the original `io.github.jqssun.airplay` receiver when it installs the fork.
  The phone app downloads the fork from this repo's rolling release `airplay-latest`, published by the `build-airplay` workflow
  (`.github/workflows/airplay.yml`, separate from `build-apk` because it is slow and needs JDK 21 / AGP 9 / NDK 27 and four git
  submodules listed in `.gitmodules`).
* "AirPlay settings" now lists every setting of the receiver's own Settings screen (Server, Connection, Display, Decode,
  Developer, in the same order, labelled with the receiver's own texts read from its resources by name, so they match the
  original app; the Developer rows appear when "Developer options" is on), and at the bottom "Open the AirPlay app" and "Logs"
  (the service's log, with Clear). The main switch shows whether the server really runs (STATUS).
* the AirPlay pill and its on/off switch are now ONE button (`SwitchDrawable` drawn as the pill's end icon): pressing it turns
  the AirPlay server on or off and never opens the receiver app. The app is reached only through a Home shortcut added from
  the 9-dots library; it is also left out of the bottom app drawer, and the "Open the AirPlay app" row is gone from AirPlay
  settings. The drawer's selection frame is now the GridView's own selector (its cells never took focus, so the old per-cell
  frame never showed) and the first app is selected when it opens.
* "Install a no ad YouTube" (phone app): a switch that, only while on, shows two choices. **SmartTube** ("Very lightweight, but
  doesn't have native YouTube UI"): the phone reads yuliskov/SmartTube's latest release and downloads the APK for the TV's
  processor (arm64-v8a, armeabi-v7a, x86, else universal), package `org.smarttube.stable`. **VacuumTube** ("Lightweight, exactly
  the same as normal YouTube but with more settings", the default): the owner's own build, `releases/download/android-latest/
  VacuumTube-AndroidTV.apk` of cheapearth9/vacuumtube-forked-by-claude, package `io.github.cheapearth9.vacuumtube`, Android 5+.
  The phone downloads, checks the file is that app, and installs it on the TV (`NoAdYoutube`); nothing is bundled and the TV
  needs no GitHub access. An installed app is not downloaded again; with the switch off nothing is shown or done.
* the AirPlay receiver's screen quits by itself (`finishAndRemoveTask`) the moment the last sender disconnects, so the TV goes
  straight back to the launcher instead of showing an empty window (`MainActivity.kt`, within about 0.2 s: the screen reads
  the connection count from the service that often). Opening it by hand with nobody connected does not close it.
* every cleanyourtv APK is signed with one fixed key, `cyt.keystore` at the repository root (committed on purpose; sideload
  key, not a secret): the phone app and the launcher (before, each CI run signed with a new random debug key, so a new build
  could not be installed over the old one). The phone app's versionCode is the CI run number, so every build is newer. The AirPlay
  receiver keeps its own fixed key (`airplay/app/cyt-airplay.keystore`) and VacuumTube has its own in its repository.
  Builds made before this change were signed with a random key: uninstall that one once, then updates just work.
