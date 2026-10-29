package tv.clean.phone;

import java.io.IOException;

import tv.clean.phone.adb.AdbConnection;
import tv.clean.phone.core.TvShell;

/** Connects the logic layer to a live ADB connection. */
final class AdbTvShell implements TvShell {
    private final AdbConnection conn;

    AdbTvShell(AdbConnection conn) {
        this.conn = conn;
    }

    @Override
    public String run(String command, int timeoutMs) throws IOException {
        return conn.shellOutput(command, timeoutMs);
    }

    @Override
    public void push(byte[] data, String remotePath, int mode) throws IOException {
        conn.push(data, remotePath, mode, 180000);
    }
}
