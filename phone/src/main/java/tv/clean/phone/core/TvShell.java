package tv.clean.phone.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** What the cleaning logic needs from a connection to the TV. Lets the logic run against a fake in tests. */
public interface TvShell {
    /** Runs a shell command on the TV and returns its combined stdout+stderr. */
    String run(String command, int timeoutMs) throws IOException;

    /** Copies bytes to a file on the TV. */
    void push(byte[] data, String remotePath, int mode) throws IOException;

    /**
     * True when commands now run as root. Real connections also ask adbd to restart as root (the "adb root" step,
     * which only some boxes allow); the default only looks at who we already are.
     */
    default boolean ensureRoot() throws IOException {
        return run("id 2>&1", 15000).contains("uid=0");
    }

    /** Makes the system partition writable the way "adb remount" does (needs root); returns the answer, or "" when unsupported. */
    default String remountRw() throws IOException {
        return "";
    }

    /** Progress of a file push. */
    interface Progress {
        void onProgress(long sent, long total);
    }

    /** Copies a file from this phone to the TV. Real connections stream it; the default reads it whole (fine for tests). */
    default void pushFile(File local, String remotePath, int mode, Progress progress) throws IOException {
        byte[] all = new byte[(int) local.length()];
        try (FileInputStream in = new FileInputStream(local)) {
            int off = 0;
            while (off < all.length) {
                int r = in.read(all, off, all.length - off);
                if (r < 0) break;
                off += r;
            }
        }
        push(all, remotePath, mode);
        if (progress != null) progress.onProgress(all.length, all.length);
    }
}
