package com.gathin.tvhome.time;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** The part of java.time.format.DateTimeFormatter the launcher uses: a pattern, a locale, a zone, format(Instant). */
public final class DateTimeFormatter {
    private final String pattern;
    private final Locale locale;
    private final TimeZone zone;

    private DateTimeFormatter(String pattern, Locale locale, TimeZone zone) {
        this.pattern = pattern;
        this.locale = locale;
        this.zone = zone;
    }

    public static DateTimeFormatter ofPattern(String pattern, Locale locale) {
        new SimpleDateFormat(pattern, locale); // throws IllegalArgumentException for a bad pattern, as java.time does
        return new DateTimeFormatter(pattern, locale, TimeZone.getDefault());
    }

    public DateTimeFormatter withZone(ZoneId zone) {
        return new DateTimeFormatter(pattern, locale, zone.timeZone());
    }

    public String format(Instant instant) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, locale);
        f.setTimeZone(zone);
        return f.format(new Date(instant.toEpochMilli()));
    }
}
