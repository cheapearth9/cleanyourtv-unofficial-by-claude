package tv.clean.phone.core;

import java.io.IOException;

/** What the cleaning logic needs from a connection to the TV. Lets the logic run against a fake in tests. */
public interface TvShell {
    /** Runs a shell command on the TV and returns its combined stdout+stderr. */
    String run(String command, int timeoutMs) throws IOException;

    /** Copies bytes to a file on the TV. */
    void push(byte[] data, String remotePath, int mode) throws IOException;
}
