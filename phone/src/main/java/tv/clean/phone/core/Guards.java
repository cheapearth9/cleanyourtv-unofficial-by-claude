package tv.clean.phone.core;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** The safety rules of upstream tools/disable.sh, kept the same on purpose. */
public final class Guards {
    private Guards() {}

    /** Core Android and TV middleware: with one of these off the TV may not boot again (upstream #884, #1578). */
    private static final Pattern CORE = Pattern.compile(
        "^(android|android\\.ext\\.(services|shared)|com\\.android\\.systemui|com\\.android\\.shell|com\\.android\\.phone"
        + "|com\\.android\\.settings|com\\.android\\.tv\\.settings"
        + "|com\\.(google\\.)?android\\.(permissioncontroller|packageinstaller|ext\\.services|ext\\.shared|modulemetadata)"
        + "|com\\.google\\.android\\.overlay\\.modules.*|com\\.android\\.providers\\..*|com\\.cltv\\.mal"
        + "|com\\.mediatek\\.(tis|tv\\.service|tv\\.agent))$");
    private static final Pattern CORE_OK = Pattern.compile(
        "^com\\.android\\.providers\\.(calendar|contacts|userdictionary|blockednumber)$");

    /**
     * Never offered as clean-up candidates even if a TV's list names them: apps that stores, accounts,
     * keyboards, remote-control and the Play Store depend on. Phone-side addition to upstream's rules.
     */
    private static final List<String> NEVER_SUGGEST = Arrays.asList(
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher",
        "com.google.android.inputmethod.latin", "com.google.android.tv.remote.service",
        "com.google.android.webview", "com.android.webview", "com.google.android.trichromelibrary",
        "com.google.android.tv.bugreportsender", "com.android.keychain", "com.android.certinstaller",
        "com.android.inputdevices", "com.android.bluetooth", "com.android.tv", "com.android.tv.settings",
        "com.google.android.syncadapters.contacts");

    /**
     * Features people may use (Cast receiver, voice search, TTS, accessibility, streaming apps). Upstream lists
     * disable some of them for specific TVs; they are never pre-ticked from the cross-TV scan.
     */
    private static final List<String> SOFT = Arrays.asList(
        "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.tts",
        "com.google.android.marvin.talkback", "com.google.android.katniss", "com.google.android.apps.mediashell",
        "com.google.android.tv.assistant", "com.google.android.youtube.tv", "com.google.android.youtube.tvmusic",
        "com.google.android.play.games", "com.google.android.videos", "com.google.android.apps.tv.dreamx",
        "com.android.printspooler", "com.android.providers.calendar", "com.google.android.syncadapters.calendar");

    public static boolean isSoft(String pkg) {
        return SOFT.contains(pkg);
    }

    public static final Pattern PKG = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");
    public static final String SETUPWRAITH = "com.google.android.tungsten.setupwraith";
    public static final String OUR_LAUNCHER = "com.gathin.tvhome";
    public static final List<String> KNOWN_STOCK_LAUNCHERS = Arrays.asList(
        "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher", "com.mitv.tvhome");

    public static boolean isValidName(String pkg) {
        return pkg != null && PKG.matcher(pkg).matches();
    }

    public static boolean isCore(String pkg) {
        return CORE.matcher(pkg).matches() && !CORE_OK.matcher(pkg).matches();
    }

    public static boolean neverSuggest(String pkg) {
        return NEVER_SUGGEST.contains(pkg) || isCore(pkg) || pkg.equals(OUR_LAUNCHER);
    }

    /** "pkg" from "pkg/.Activity", or the string itself. */
    public static String pkgOf(String component) {
        int slash = component.indexOf('/');
        return slash > 0 ? component.substring(0, slash) : component;
    }
}
