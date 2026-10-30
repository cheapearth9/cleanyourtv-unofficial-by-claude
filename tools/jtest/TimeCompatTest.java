import com.gathin.tvhome.time.DateTimeFormatter;
import com.gathin.tvhome.time.DateTimeParseException;
import com.gathin.tvhome.time.Duration;
import com.gathin.tvhome.time.Instant;
import com.gathin.tvhome.time.LocalDate;
import com.gathin.tvhome.time.LocalDateTime;
import com.gathin.tvhome.time.ZoneId;

import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/**
 * Differential test: the launcher's java.time stand-in (com.gathin.tvhome.time) against the real java.time, on the
 * desktop JVM. Run by tools/run-tests.sh.
 */
public final class TimeCompatTest {
    static int failures;
    static int checks;

    static void same(Object real, Object mine, String what) {
        checks++;
        if (!String.valueOf(real).equals(String.valueOf(mine))) {
            failures++;
            System.out.println("FAIL  " + what + ": java.time=" + real + " shim=" + mine);
        }
    }

    static String tryReal(java.util.function.Supplier<Object> s) {
        try { return String.valueOf(s.get()); } catch (java.time.DateTimeException e) { return "REJECTED"; }
    }

    static String tryMine(java.util.function.Supplier<Object> s) {
        try { return String.valueOf(s.get()); } catch (DateTimeParseException e) { return "REJECTED"; }
    }

    public static void main(String[] args) throws Exception {
        // ---- LocalDate.parse: accepted and rejected inputs, and the parsed day
        String[] dates = {"2026-10-30", "2024-02-29", "2025-02-29", "2026-02-30", "2026-13-01", "2026-00-10", "2026-04-31",
            "2026-1-1", "26-01-01", "2026-01-01 ", " 2026-01-01", "", "2026/01/01", "2026-01-01T00:00", "+2026-01-01", "2100-02-29",
            "2000-02-29", "1900-02-29", "0001-01-01", "9999-12-31", "2026-01-32", "20260101", "2026-01-0a"};
        for (String d : dates) {
            same(tryReal(() -> java.time.LocalDate.parse(d)), tryMine(() -> LocalDate.parse(d)), "LocalDate.parse(\"" + d + "\")");
        }
        // every day of 2023..2028: day-of-year, ordering, equality
        java.time.LocalDate rd = java.time.LocalDate.of(2023, 1, 1);
        Random rnd = new Random(42);
        for (int i = 0; i < 365 * 6 + 2; i++, rd = rd.plusDays(1)) {
            LocalDate md = LocalDate.parse(rd.toString());
            same(rd.getDayOfYear(), md.getDayOfYear(), "getDayOfYear " + rd);
            same(rd, md, "toString " + rd);
            java.time.LocalDate other = rd.plusDays(rnd.nextInt(7) - 3);
            LocalDate mo = LocalDate.parse(other.toString());
            same(rd.isAfter(other), md.isAfter(mo), "isAfter " + rd + " " + other);
            same(rd.equals(other), md.equals(mo), "equals " + rd + " " + other);
            same(md.hashCode() == LocalDate.parse(rd.toString()).hashCode(), true, "hashCode stable");
        }

        // ---- the sunrise offset: every day of 2026 in zones with DST, half-hour and 45-minute offsets
        String[] zones = {"Asia/Ho_Chi_Minh", "America/New_York", "Europe/London", "Asia/Kolkata", "Australia/Lord_Howe",
            "Pacific/Apia", "Asia/Kathmandu", "America/St_Johns", "UTC", "Europe/Berlin"};
        for (String z : zones) {
            TimeZone.setDefault(TimeZone.getTimeZone(z));
            java.time.ZoneId realZone = java.time.ZoneId.systemDefault();
            ZoneId myZone = ZoneId.systemDefault();
            java.time.LocalDate day = java.time.LocalDate.of(2026, 1, 1);
            for (int i = 0; i < 365; i++, day = day.plusDays(1)) {
                int real = realZone.getRules().getOffset(day.atTime(12, 0)).getTotalSeconds() / 60;
                same(real, myZone.offsetMinutesAtNoon(LocalDate.parse(day.toString())), z + " offset at noon " + day);
            }
            // now() agrees with the real clock in that zone
            same(java.time.LocalDate.now().toString(), LocalDate.now().toString(), z + " LocalDate.now()");
            java.time.LocalDateTime rn = java.time.LocalDateTime.now();
            LocalDateTime mn = LocalDateTime.now();
            int realMinute = rn.getHour() * 60 + rn.getMinute();
            int myMinute = mn.getHour() * 60 + mn.getMinute();
            checks++;
            if (Math.abs(realMinute - myMinute) > 1 && Math.abs(realMinute - myMinute) != 1439) {
                failures++;
                System.out.println("FAIL  " + z + " LocalDateTime.now() minute-of-day " + realMinute + " vs " + myMinute);
            }
            same(rn.toLocalDate().toString(), mn.toLocalDate().toString(), z + " LocalDateTime.toLocalDate()");
        }
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // ---- Instant.parse
        String[] instants = {"2026-03-06T12:30:00Z", "2026-03-06T12:30:00.5Z", "2026-03-06T12:30:00.123Z", "2026-03-06T12:30:00.123456789Z",
            "2026-12-31T23:59:59Z", "2024-02-29T00:00:00Z", "2026-02-30T00:00:00Z", "2026-03-06T24:00:00Z", "2026-03-06T12:60:00Z",
            "2026-03-06T12:30:61Z", "2026-03-06 12:30:00Z", "2026-03-06T12:30:00", "garbage", "", "2026-03-06T12:30:00+01:00",
            "2026-03-06T12:30:00-05:30", "2026-03-06T12:30:00.Z", "2026-03-06T12:30:00.1234567890Z", "2026-3-6T12:30:00Z"};
        for (String s : instants) {
            same(tryReal(() -> java.time.Instant.parse(s).toEpochMilli()), tryMine(() -> Instant.parse(s).toEpochMilli()), "Instant.parse(\"" + s + "\")");
        }

        // ---- Instant / Duration arithmetic (what RaceWeekend does)
        for (int i = 0; i < 5000; i++) {
            long a = 1_700_000_000_000L + (long) (rnd.nextDouble() * 4e10);
            long b = a + (long) ((rnd.nextDouble() - 0.5) * 4e9);
            java.time.Instant ra = java.time.Instant.ofEpochMilli(a), rb = java.time.Instant.ofEpochMilli(b);
            Instant ma = Instant.ofEpochMilli(a), mb = Instant.ofEpochMilli(b);
            same(java.time.Duration.between(ra, rb).getSeconds(), Duration.between(ma, mb).getSeconds(), "Duration.between.getSeconds " + a + " " + b);
            same(ra.isBefore(rb), ma.isBefore(mb), "isBefore");
            same(ra.minus(java.time.Duration.ofHours(12)).toEpochMilli(), ma.minus(Duration.ofHours(12)).toEpochMilli(), "minus 12h");
            same(ra.plus(java.time.Duration.ofMinutes(45)).toEpochMilli(), ma.plus(Duration.ofMinutes(45)).toEpochMilli(), "plus 45m");
            long ms = (long) ((rnd.nextDouble() - 0.5) * 2e8);
            same(java.time.Duration.ofMillis(ms).getSeconds(), Duration.ofMillis(ms).getSeconds(), "Duration.ofMillis(" + ms + ").getSeconds");
        }
        same(java.time.Duration.ofMillis(-500).getSeconds(), Duration.ofMillis(-500).getSeconds(), "negative half second floors to -1");

        // ---- DateTimeFormatter (race weekend rows): both clock styles, several zones
        for (String z : new String[] {"Asia/Ho_Chi_Minh", "America/New_York", "Europe/London", "Asia/Kolkata"}) {
            TimeZone.setDefault(TimeZone.getTimeZone(z));
            for (String pat : new String[] {"EEE HH:mm", "EEE h:mm a"}) {
                java.time.format.DateTimeFormatter rf = java.time.format.DateTimeFormatter.ofPattern(pat, Locale.US).withZone(java.time.ZoneId.systemDefault());
                DateTimeFormatter mf = DateTimeFormatter.ofPattern(pat, Locale.US).withZone(ZoneId.systemDefault());
                for (int i = 0; i < 400; i++) {
                    long t = 1_760_000_000_000L + (long) (rnd.nextDouble() * 2.5e10);
                    same(rf.format(java.time.Instant.ofEpochMilli(t)), mf.format(Instant.ofEpochMilli(t)), z + " format \"" + pat + "\" at " + t);
                }
            }
        }
        System.out.println(failures == 0 ? "ALL PASSED (" + checks + " comparisons)" : failures + " FAILED of " + checks);
        System.exit(failures == 0 ? 0 : 1);
    }
}
