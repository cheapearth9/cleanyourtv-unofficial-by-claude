package com.gathin.tvhome.time;

import java.util.Calendar;
import java.util.TimeZone;

/** The part of java.time.ZoneId the launcher uses: the device's zone and its UTC offset on a day. */
public final class ZoneId {
    private final TimeZone zone;

    private ZoneId(TimeZone zone) {
        this.zone = zone;
    }

    public static ZoneId systemDefault() {
        return new ZoneId(TimeZone.getDefault());
    }

    public TimeZone timeZone() {
        return zone;
    }

    /** Minutes east of UTC at 12:00 local time on {@code date} (what Sun.riseAndSet needs). */
    public int offsetMinutesAtNoon(LocalDate date) {
        Calendar c = Calendar.getInstance(zone);
        c.clear();
        c.set(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth(), 12, 0, 0);
        return zone.getOffset(c.getTimeInMillis()) / 60_000;
    }
}
