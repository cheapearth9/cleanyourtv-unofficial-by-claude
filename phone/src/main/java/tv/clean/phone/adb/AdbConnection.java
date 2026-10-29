package tv.clean.phone.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * A small ADB client over plain TCP (the "network ADB" port 5555 every Android TV exposes when
 * USB debugging is on). Speaks the CNXN/AUTH handshake, the legacy "shell:" service and sync SEND.
 * One stream at a time, synchronous. Not thread safe: call it from one worker thread.
 *
 * Android 7 (API 24/25) adbd speaks exactly this protocol, so no shell_v2 or TLS is needed.
 * Android 11+ "Wireless debugging" ports ask for TLS (STLS) and are not supported; those TVs
 * also listen on 5555 once "adb tcpip 5555" was run, or when the TV's ADB debugging toggle is on.
 */
public final class AdbConnection implements Closeable {
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;
    /** Version 1 (not 1.0.1): every adbd, old or new, still accepts it and checks the checksum we send. */
    static final int VERSION = 0x01000000;
    static final int HOST_MAX_PAYLOAD = 256 * 1024;

    public static final String RC_MARK = "__CYTRC__";

    /** Called with short human messages while connecting ("Allow USB debugging on the TV"). */
    public interface Status {
        void onStatus(String message);
    }

    /** Result of a shell command. */
    public static final class ShellResult {
        public final String output;
        public final int exitCode; // -1 when the shell did not report one

        ShellResult(String output, int exitCode) {
            this.output = output;
            this.exitCode = exitCode;
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final AdbKey key;
    private int maxData = 4096;
    private int nextLocalId = 1;
    private String banner = "";

    // state of the one open stream
    private int localId;
    private int remoteId;
    private boolean streamClosed;
    private boolean okayPending;
    private final ByteArrayOutputStream inbox = new ByteArrayOutputStream();

    private AdbConnection(Socket socket, AdbKey key) throws IOException {
        this.socket = socket;
        this.key = key;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    public static AdbConnection connect(String host, int port, AdbKey key, int authWaitMs, Status status)
            throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 6000);
        AdbConnection c = new AdbConnection(socket, key);
        try {
            c.handshake(authWaitMs, status);
        } catch (IOException | RuntimeException e) {
            c.close();
            throw e;
        }
        return c;
    }

    public String banner() {
        return banner;
    }

    public int maxData() {
        return maxData;
    }

    // ------------------------------------------------------------------ handshake

    private void handshake(int authWaitMs, Status status) throws IOException {
        send(A_CNXN, VERSION, HOST_MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.US_ASCII));
        boolean sentSignature = false;
        boolean sentKey = false;
        long deadline = System.currentTimeMillis() + 15000;
        while (true) {
            Message m = read(Math.max(1000, (int) (deadline - System.currentTimeMillis())));
            if (m.command == A_CNXN) {
                maxData = m.arg1 > 0 ? Math.min(m.arg1, HOST_MAX_PAYLOAD) : 4096;
                banner = new String(m.data, StandardCharsets.UTF_8).trim();
                return;
            }
            if (m.command == A_AUTH && m.arg0 == AUTH_TOKEN) {
                if (!sentSignature) {
                    send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
                    sentSignature = true;
                } else if (!sentKey) {
                    if (status != null) {
                        status.onStatus("Look at the TV: tick \"Always allow from this computer\" and press OK.");
                    }
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload());
                    sentKey = true;
                    deadline = System.currentTimeMillis() + authWaitMs; // the person has to reach the remote
                } else {
                    throw new IOException("The TV refused this phone (USB debugging authorisation denied).");
                }
                continue;
            }
            if (m.command == A_CLSE) {
                throw new IOException("The TV closed the connection during the handshake.");
            }
            // anything else before CNXN: ignore and keep waiting
        }
    }

    // ------------------------------------------------------------------ shell

    /** Runs one shell command and returns its combined output and exit status. */
    public ShellResult shell(String command, int timeoutMs) throws IOException {
        String wrapped = command + "\necho \"" + RC_MARK + "$?\"";
        openStream("shell:" + wrapped);
        byte[] raw = readUntilClose(timeoutMs);
        String text = new String(raw, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int exit = -1;
        int at = text.lastIndexOf(RC_MARK);
        if (at >= 0) {
            String tail = text.substring(at + RC_MARK.length()).trim();
            try {
                exit = Integer.parseInt(tail);
            } catch (NumberFormatException ignored) {
                // leave -1
            }
            text = text.substring(0, at);
        }
        return new ShellResult(text, exit);
    }

    public String shellOutput(String command, int timeoutMs) throws IOException {
        return shell(command, timeoutMs).output;
    }

    // ------------------------------------------------------------------ sync push

    /** Copies {@code data} to {@code remotePath} on the TV with the given file mode (e.g. 0644). */
    public void push(byte[] data, String remotePath, int mode, int timeoutMs) throws IOException {
        openStream("sync:");
        long deadline = System.currentTimeMillis() + timeoutMs;
        String spec = remotePath + "," + (0100000 | (mode & 0777));
        byte[] specBytes = spec.getBytes(StandardCharsets.UTF_8);
        writeStream(concat("SEND".getBytes(StandardCharsets.US_ASCII), le32(specBytes.length), specBytes));
        int chunk = Math.min(64 * 1024, maxData - 8);
        for (int off = 0; off < data.length; off += chunk) {
            int n = Math.min(chunk, data.length - off);
            byte[] msg = new byte[8 + n];
            System.arraycopy("DATA".getBytes(StandardCharsets.US_ASCII), 0, msg, 0, 4);
            System.arraycopy(le32(n), 0, msg, 4, 4);
            System.arraycopy(data, off, msg, 8, n);
            writeStream(msg);
        }
        writeStream(concat("DONE".getBytes(StandardCharsets.US_ASCII), le32((int) (System.currentTimeMillis() / 1000))));
        // answer: "OKAY" + u32 0, or "FAIL" + u32 len + message
        while (inbox.size() < 8 && !streamClosed) {
            pump(remaining(deadline));
        }
        byte[] reply = inbox.toByteArray();
        if (reply.length < 8) {
            throw new IOException("Push to " + remotePath + " ended without an answer from the TV.");
        }
        String id = new String(reply, 0, 4, StandardCharsets.US_ASCII);
        if (id.equals("FAIL")) {
            int len = (reply[4] & 0xff) | ((reply[5] & 0xff) << 8) | ((reply[6] & 0xff) << 16) | ((reply[7] & 0xff) << 24);
            while (inbox.size() < 8 + len && !streamClosed) {
                pump(remaining(deadline));
            }
            byte[] full = inbox.toByteArray();
            String msg = new String(full, 8, Math.min(len, Math.max(0, full.length - 8)), StandardCharsets.UTF_8);
            closeStream();
            throw new IOException("The TV refused the file " + remotePath + ": " + msg);
        }
        if (!id.equals("OKAY")) {
            closeStream();
            throw new IOException("Unexpected answer to the push: " + id);
        }
        writeStream(concat("QUIT".getBytes(StandardCharsets.US_ASCII), le32(0)));
        closeStream();
    }

    // ------------------------------------------------------------------ streams

    private void openStream(String service) throws IOException {
        localId = nextLocalId++;
        remoteId = 0;
        streamClosed = false;
        okayPending = false;
        inbox.reset();
        send(A_OPEN, localId, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 15000;
        while (remoteId == 0) {
            Message m = read(remaining(deadline));
            if (m.command == A_OKAY && m.arg1 == localId) {
                remoteId = m.arg0;
            } else if (m.command == A_CLSE && m.arg1 == localId) {
                throw new IOException("The TV refused the service: " + service.split("\n")[0]);
            }
        }
    }

    private void writeStream(byte[] data) throws IOException {
        int off = 0;
        while (off < data.length) {
            int n = Math.min(maxData, data.length - off);
            byte[] part = new byte[n];
            System.arraycopy(data, off, part, 0, n);
            okayPending = true;
            send(A_WRTE, localId, remoteId, part);
            long deadline = System.currentTimeMillis() + 30000;
            while (okayPending) {
                if (streamClosed) throw new IOException("The TV closed the stream while data was being sent.");
                pump(remaining(deadline));
            }
            off += n;
        }
    }

    private byte[] readUntilClose(int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!streamClosed) {
            pump(remaining(deadline));
        }
        byte[] result = inbox.toByteArray();
        inbox.reset();
        return result;
    }

    private void closeStream() throws IOException {
        if (!streamClosed) {
            send(A_CLSE, localId, remoteId, new byte[0]);
            streamClosed = true;
        }
    }

    /** Reads one message and applies it to the open stream. */
    private void pump(int timeoutMs) throws IOException {
        Message m = read(timeoutMs);
        if (m.command == A_WRTE && m.arg1 == localId) {
            inbox.write(m.data, 0, m.data.length);
            send(A_OKAY, localId, remoteId, new byte[0]);
        } else if (m.command == A_OKAY && m.arg1 == localId) {
            okayPending = false;
        } else if (m.command == A_CLSE && m.arg1 == localId) {
            streamClosed = true;
            okayPending = false;
        }
        // CNXN/AUTH here (adbd restarting) or a stale stream id: ignore
    }

    private static int remaining(long deadline) throws IOException {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) throw new SocketTimeoutException("Timed out waiting for the TV.");
        return (int) Math.min(left, Integer.MAX_VALUE);
    }

    // ------------------------------------------------------------------ wire

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        long sum = 0;
        for (byte b : data) sum += b & 0xff;
        byte[] header = new byte[24];
        putLe(header, 0, command);
        putLe(header, 4, arg0);
        putLe(header, 8, arg1);
        putLe(header, 12, data.length);
        putLe(header, 16, (int) sum);
        putLe(header, 20, command ^ 0xffffffff);
        out.write(header);
        if (data.length > 0) out.write(data);
        out.flush();
    }

    private Message read(int timeoutMs) throws IOException {
        socket.setSoTimeout(Math.max(1, timeoutMs));
        byte[] h = new byte[24];
        try {
            in.readFully(h);
        } catch (java.io.EOFException e) {
            throw new IOException("The TV closed the ADB connection.", e);
        }
        Message m = new Message();
        m.command = getLe(h, 0);
        m.arg0 = getLe(h, 4);
        m.arg1 = getLe(h, 8);
        int len = getLe(h, 12);
        if (m.command != (getLe(h, 20) ^ 0xffffffff)) {
            throw new IOException("Not an ADB peer (bad magic). Wrong port?");
        }
        if (len < 0 || len > 1024 * 1024) throw new IOException("Bad ADB packet length " + len);
        m.data = new byte[len];
        if (len > 0) {
            socket.setSoTimeout(Math.max(timeoutMs, 10000));
            in.readFully(m.data);
        }
        return m;
    }

    private static void putLe(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static int getLe(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static byte[] le32(int v) {
        byte[] b = new byte[4];
        putLe(b, 0, v);
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, r, o, p.length);
            o += p.length;
        }
        return r;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /** Test hook. */
    InputStream rawIn() {
        return in;
    }
}
