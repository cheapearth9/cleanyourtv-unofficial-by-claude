package tv.clean.phone.core;

/** A failure whose message can be shown to the person as is. */
public final class CleanException extends Exception {
    public CleanException(String message) {
        super(message);
    }
}
