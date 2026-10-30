package com.gathin.tvhome.time;

/** The part of java.time.Duration the launcher uses, in milliseconds. */
public final class Duration {
    private final long millis;

    private Duration(long millis) {
        this.millis = millis;
    }

    public static Duration ofMillis(long ms) {
        return new Duration(ms);
    }

    public static Duration ofMinutes(long m) {
        return new Duration(m * 60_000L);
    }

    public static Duration ofHours(long h) {
        return new Duration(h * 3_600_000L);
    }

    public static Duration between(Instant from, Instant to) {
        return new Duration(to.toEpochMilli() - from.toEpochMilli());
    }

    /** Whole seconds, rounded toward negative infinity, as java.time.Duration.getSeconds does. */
    public long getSeconds() {
        return Math.floorDiv(millis, 1000L);
    }

    public long toMillis() {
        return millis;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Duration && ((Duration) o).millis == millis;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(millis);
    }
}
