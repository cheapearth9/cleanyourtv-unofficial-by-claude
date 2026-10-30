package com.gathin.tvhome.time;

import java.util.Calendar;
import java.util.TimeZone;

/** The part of java.time.LocalDateTime the launcher uses: the device's local date and time of day, now. */
public final class LocalDateTime {
    private final LocalDate date;
    private final int hour;
    private final int minute;
    private final int second;

    private LocalDateTime(LocalDate date, int hour, int minute, int second) {
        this.date = date;
        this.hour = hour;
        this.minute = minute;
        this.second = second;
    }

    public static LocalDateTime now() {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        LocalDate day = LocalDate.of(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        return new LocalDateTime(day, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }

    public LocalDate toLocalDate() {
        return date;
    }

    public int getHour() {
        return hour;
    }

    public int getMinute() {
        return minute;
    }

    public int getSecond() {
        return second;
    }
}
