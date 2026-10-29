# What changed from upstream cleanyourtv

The launcher in `launcher/` is upstream's launcher (MIT, © 2026 Gathin) with these edits so that it installs and runs on
Android 7.0 / 7.1 (API 24 / 25) instead of Android 8+:

| File | Change |
|---|---|
| `launcher/app/build.gradle` | `minSdk 26` -> `24`; v1 + v2 APK signing (Nougat installers); unit-test dependency removed |
| `UiFont.java` | `Resources.getFont` (API 26) -> `ResourcesCompat.getFont`, falls back to system sans |
| `MainActivity.java` | `PackageManager.getChangedPackages` (API 26): below Android 8 the tiles are simply rebuilt each time |
| `WallpaperPlayer.java` | `Bitmap.Config.HARDWARE` (API 26): below Android 8 the software bitmap is used |

I compiled every launcher source against the API 24 platform jar: the only symbols missing there are the three above
plus calls that upstream already guards with `Build.VERSION.SDK_INT` (TIRAMISU, P, Q, GET_SIGNING_CERTIFICATES, ...).

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
