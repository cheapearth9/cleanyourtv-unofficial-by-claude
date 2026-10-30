package com.gathin.tvhome.time;

import java.util.Calendar;
import java.util.TimeZone;

/** The part of java.time.LocalDate the launcher uses (ISO calendar). */
public final class LocalDate implements Comparable<LocalDate> {
    private final int year;
    private final int month; // 1-12
    private final int day;   // 1-31

    private LocalDate(int year, int month, int day) {
        this.year = year;
        this.month = month;
        this.day = day;
    }

    public static LocalDate now() {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        return new LocalDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static LocalDate of(int y, int m, int d) {
        return new LocalDate(y, m, d);
    }

    /** {@code yyyy-MM-dd}, four-digit year, zero-padded, a real calendar date; otherwise {@link DateTimeParseException}. */
    public static LocalDate parse(CharSequence text) {
        String s = String.valueOf(text);
        if (!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new DateTimeParseException("Text '" + s + "' could not be parsed at index 0", s);
        }
        int y = Integer.parseInt(s.substring(0, 4));
        int m = Integer.parseInt(s.substring(5, 7));
        int d = Integer.parseInt(s.substring(8, 10));
        if (m < 1 || m > 12 || d < 1 || d > lengthOfMonth(y, m)) {
            throw new DateTimeParseException("Text '" + s + "' could not be parsed: invalid date", s);
        }
        return new LocalDate(y, m, d);
    }

    private static boolean leap(int y) {
        return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
    }

    private static int lengthOfMonth(int y, int m) {
        switch (m) {
            case 2: return leap(y) ? 29 : 28;
            case 4: case 6: case 9: case 11: return 30;
            default: return 31;
        }
    }

    public int getYear() {
        return year;
    }

    public int getMonthValue() {
        return month;
    }

    public int getDayOfMonth() {
        return day;
    }

    /** 1 for 1 January. */
    public int getDayOfYear() {
        int n = day;
        for (int m = 1; m < month; m++) n += lengthOfMonth(year, m);
        return n;
    }

    public boolean isAfter(LocalDate o) {
        return compareTo(o) > 0;
    }

    public boolean isBefore(LocalDate o) {
        return compareTo(o) < 0;
    }

    @Override
    public int compareTo(LocalDate o) {
        if (year != o.year) return Integer.compare(year, o.year);
        if (month != o.month) return Integer.compare(month, o.month);
        return Integer.compare(day, o.day);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LocalDate && compareTo((LocalDate) o) == 0;
    }

    @Override
    public int hashCode() {
        return (year << 9) ^ (month << 5) ^ day;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", year, month, day);
    }
}
