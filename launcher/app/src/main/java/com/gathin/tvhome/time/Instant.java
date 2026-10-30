package com.gathin.tvhome.time;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** The part of java.time.Instant the launcher uses (millisecond precision). */
public final class Instant implements Comparable<Instant> {
    private final long millis;

    private Instant(long millis) {
        this.millis = millis;
    }

    public static Instant ofEpochMilli(long ms) {
        return new Instant(ms);
    }

    public static Instant now() {
        return new Instant(System.currentTimeMillis());
    }

    /**
     * ISO-8601 UTC instants such as {@code 2026-03-15T04:00:00Z}, with optional fractional seconds. A numeric
     * offset ({@code +01:00}) is accepted too. Anything else throws {@link DateTimeParseException}.
     */
    public static Instant parse(CharSequence text) {
        String s = String.valueOf(text);
        String body;
        long offsetMs = 0;
        if (s.endsWith("Z")) {
            body = s.substring(0, s.length() - 1);
        } else {
            int n = s.length();
            if (n < 6 || (s.charAt(n - 6) != '+' && s.charAt(n - 6) != '-') || s.charAt(n - 3) != ':') {
                throw new DateTimeParseException("Text '" + s + "' could not be parsed", s);
            }
            try {
                int sign = s.charAt(n - 6) == '-' ? -1 : 1;
                offsetMs = sign * (Integer.parseInt(s.substring(n - 5, n - 3)) * 3_600_000L
                        + Integer.parseInt(s.substring(n - 2)) * 60_000L);
            } catch (NumberFormatException e) {
                throw new DateTimeParseException("Text '" + s + "' could not be parsed", s);
            }
            body = s.substring(0, n - 6);
        }
        long fractionMs = 0;
        int dot = body.indexOf('.');
        if (dot >= 0) {
            String frac = body.substring(dot + 1);
            if (frac.length() > 9 || !frac.matches("[0-9]*")) {
                throw new DateTimeParseException("Text '" + s + "' could not be parsed", s);
            }
            String ms = (frac + "000").substring(0, 3);   // an empty fraction ("00.Z") is accepted, as java.time does
            fractionMs = Integer.parseInt(ms);
            body = body.substring(0, dot);
        }
        if (!body.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")) {
            throw new DateTimeParseException("Text '" + s + "' could not be parsed", s);
        }
        LocalDate.parse(body.substring(0, 10)); // rejects 2026-02-30 and the like
        int hh = Integer.parseInt(body.substring(11, 13));
        int mm = Integer.parseInt(body.substring(14, 16));
        int ss = Integer.parseInt(body.substring(17, 19));
        long dayShift = 0;
        if (hh == 24 && mm == 0 && ss == 0) {   // ISO-8601 end of day: 24:00:00 is midnight of the next day
            hh = 0;
            dayShift = 86_400_000L;
            body = body.substring(0, 11) + "00" + body.substring(13);
        }
        if (hh > 23 || mm > 59 || ss > 59) {
            throw new DateTimeParseException("Text '" + s + "' could not be parsed", s);
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        f.setLenient(false);
        Date d = f.parse(body, new ParsePosition(0));
        if (d == null) throw new DateTimeParseException("Text '" + s + "' could not be parsed", s);
        return new Instant(d.getTime() + dayShift + fractionMs - offsetMs);
    }

    public long toEpochMilli() {
        return millis;
    }

    public Instant plus(Duration d) {
        return new Instant(millis + d.toMillis());
    }

    public Instant minus(Duration d) {
        return new Instant(millis - d.toMillis());
    }

    public boolean isBefore(Instant other) {
        return millis < other.millis;
    }

    public boolean isAfter(Instant other) {
        return millis > other.millis;
    }

    @Override
    public int compareTo(Instant o) {
        return Long.compare(millis, o.millis);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Instant && ((Instant) o).millis == millis;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(millis);
    }
}
