package com.gathin.tvhome.time;

/** Stand-in for java.time.format.DateTimeParseException (java.time needs Android 8; this launcher runs on 7). */
public final class DateTimeParseException extends RuntimeException {
    private final String parsed;

    public DateTimeParseException(String message, CharSequence parsedData) {
        super(message);
        this.parsed = String.valueOf(parsedData);
    }

    public String getParsedString() {
        return parsed;
    }
}
