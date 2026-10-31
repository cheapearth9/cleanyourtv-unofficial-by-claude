package tv.clean.phone;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketTimeoutException;

import tv.clean.phone.adb.AdbConnection;
import tv.clean.phone.adb.AdbKey;
import tv.clean.phone.core.TvShell;

/**
 * Connects the logic layer to a live ADB connection, and keeps it alive: a connection that dropped (Wi-Fi hiccup,
 * the TV's adbd restarting) is replaced with a new one, and the command that was in flight is sent once more.
 * A command that timed out is NOT re-sent (the TV is busy, a second heavy command would only make it worse); the
 * next command gets a fresh connection instead. Call from one worker thread.
 */
public final class AdbTvShell implements TvShell, Closeable {
    /** Short human notes ("Connection dropped, reconnecting...") for the run log. */
    public interface Notes {
        void note(String message);
    }

    private final String host;
    private final int port;
    private final AdbKey key;
    private final AdbConnection.Status status;
    private final Notes notes;
    private AdbConnection conn;
    private int reconnects;

    public AdbTvShell(String host, int port, AdbKey key, AdbConnection.Status status, Notes notes) {
        this.host = host;
        this.port = port;
        this.key = key;
        this.status = status;
        this.notes = notes;
    }

    /** First connection: waits long enough for the person to reach the remote and press OK on the TV. */
    public void open(int authWaitMs) throws IOException {
        conn = AdbConnection.connect(host, port, key, authWaitMs, status);
    }

    public String banner() {
        return conn == null ? "" : conn.banner();
    }

    public int reconnects() {
        return reconnects;
    }

    @Override
    public synchronized String run(String command, int timeoutMs) throws IOException {
        AdbConnection c = live();
        try {
            return c.shellOutput(command, timeoutMs);
        } catch (SocketTimeoutException e) {
            drop();
            throw e;
        } catch (IOException e) {
            drop();
            note("Connection to the TV dropped (" + e.getMessage() + "); reconnecting...");
            return reconnect().shellOutput(command, timeoutMs);
        }
    }

    @Override
    public synchronized void push(byte[] data, String remotePath, int mode) throws IOException {
        AdbConnection c = live();
        try {
            c.push(data, remotePath, mode, 180000);
        } catch (SocketTimeoutException e) {
            drop();
            throw e;
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("The TV refused the file")) throw e; // a real answer, not a drop
            drop();
            note("Connection to the TV dropped (" + e.getMessage() + "); reconnecting...");
            reconnect().push(data, remotePath, mode, 180000);
        }
    }

    @Override
    public synchronized void pushFile(java.io.File local, String remotePath, int mode, final Progress progress) throws IOException {
        AdbConnection.Progress adapter = progress == null ? null : new AdbConnection.Progress() {
            @Override
            public void onProgress(long sent, long total) {
                progress.onProgress(sent, total);
            }
        };
        AdbConnection c = live();
        try {
            streamTo(c, local, remotePath, mode, adapter);
        } catch (SocketTimeoutException e) {
            drop();
            throw e;
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("The TV refused the file")) throw e;
            drop();
            note("Connection to the TV dropped (" + e.getMessage() + "); reconnecting and sending the file again...");
            streamTo(reconnect(), local, remotePath, mode, adapter);
        }
    }

    private static void streamTo(AdbConnection c, java.io.File local, String remotePath, int mode, AdbConnection.Progress p) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(local)) {
            c.pushStream(in, local.length(), remotePath, mode, p);
        }
    }

    private AdbConnection live() throws IOException {
        if (conn == null || conn.isBroken()) {
            note("Reconnecting to the TV...");
            return reconnect();
        }
        return conn;
    }

    private AdbConnection reconnect() throws IOException {
        drop();
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                conn = AdbConnection.connect(host, port, key, 60000, status);
                reconnects++;
                return conn;
            } catch (IOException e) {
                last = e;
                if (attempt < 3) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new IOException("Could not reconnect to the TV: " + (last == null ? "interrupted" : last.getMessage()), last);
    }

    private void drop() {
        if (conn != null) {
            conn.close();
            conn = null;
        }
    }

    private void note(String m) {
        if (notes != null) notes.note(m);
    }

    @Override
    public synchronized void close() {
        drop();
    }
}
