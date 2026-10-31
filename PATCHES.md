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
