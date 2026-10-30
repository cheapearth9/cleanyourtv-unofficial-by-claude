package tv.clean.phone.adb;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for a TV's adbd, strict where a real one is strict: it verifies the AUTH signature with the
 * public key exactly as adbd does (RSA_verify with SHA-1 over the 20-byte token), checks the mincrypt
 * public key blob (n0inv, rr), enforces max payload, and runs "shell:" commands with the host's /bin/sh.
 */
final class MockAdbd implements Runnable, AutoCloseable {
    final ServerSocket server;
    final int maxData;
    final List<BigInteger[]> trusted = Collections.synchronizedList(new ArrayList<>());
    final Map<String, byte[]> files = Collections.synchronizedMap(new HashMap<>());
    final List<String> problems = Collections.synchronizedList(new ArrayList<>());
    final List<Socket> clients = Collections.synchronizedList(new ArrayList<>());
    volatile int promptsShown;
    volatile int signatureLogins;

    MockAdbd(int maxData) throws IOException {
        this.maxData = maxData;
        this.server = new ServerSocket(0);
        Thread t = new Thread(this, "mock-adbd");
        t.setDaemon(true);
        t.start();
    }

    /** Cuts every open connection, like a Wi-Fi hiccup or adbd restarting. */
    void dropAll() {
        synchronized (clients) {
            for (Socket c : clients) {
                try {
                    c.close();
                } catch (IOException ignored) {
                    // already gone
                }
            }
            clients.clear();
        }
    }

    int port() {
        return server.getLocalPort();
    }

    @Override
    public void run() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                clients.add(s);
                Thread t = new Thread(() -> {
                    try {
                        handle(s);
                    } catch (java.io.EOFException | java.net.SocketException e) {
                        // the phone hung up (or the test cut the connection): a normal end of a session
                    } catch (Exception e) {
                        if (!s.isClosed()) problems.add("mock connection error: " + e);
                    }
                }, "mock-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private static final class Msg {
        int cmd;
        int a0;
        int a1;
        byte[] data;
    }

    private Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        Msg m = new Msg();
        m.cmd = le(h, 0);
        m.a0 = le(h, 4);
        m.a1 = le(h, 8);
        int len = le(h, 12);
        int crc = le(h, 16);
        if (le(h, 20) != (m.cmd ^ 0xffffffff)) problems.add("bad magic from host");
        if (len > maxData) problems.add("host sent " + len + " bytes, more than maxdata " + maxData);
        m.data = new byte[len];
        in.readFully(m.data);
        long sum = 0;
        for (byte b : m.data) sum += b & 0xff;
        if (crc != (int) sum) problems.add("bad data checksum from host");
        return m;
    }

    private void send(OutputStream out, int cmd, int a0, int a1, byte[] data) throws IOException {
        byte[] h = new byte[24];
        long sum = 0;
        for (byte b : data) sum += b & 0xff;
        put(h, 0, cmd);
        put(h, 4, a0);
        put(h, 8, a1);
        put(h, 12, data.length);
        put(h, 16, (int) sum);
        put(h, 20, cmd ^ 0xffffffff);
        out.write(h);
        out.write(data);
        out.flush();
    }

    private void handle(Socket s) throws Exception {
        DataInputStream in = new DataInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();
        Msg m = read(in);
        if (m.cmd != AdbConnection.A_CNXN) throw new IOException("expected CNXN");
        byte[] token = new byte[20];
        new SecureRandom().nextBytes(token);
        send(out, AdbConnection.A_AUTH, AdbConnection.AUTH_TOKEN, 0, token);
        while (true) {
            m = read(in);
            if (m.cmd != AdbConnection.A_AUTH) throw new IOException("expected AUTH");
            if (m.a0 == AdbConnection.AUTH_SIGNATURE) {
                if (verify(token, m.data)) {
                    signatureLogins++;
                    break;
                }
                new SecureRandom().nextBytes(token);
                send(out, AdbConnection.A_AUTH, AdbConnection.AUTH_TOKEN, 0, token);
            } else if (m.a0 == AdbConnection.AUTH_RSAPUBLICKEY) {
                promptsShown++; // a real TV would show "Allow USB debugging?" here
                trusted.add(parseKey(m.data));
                break;
            }
        }
        send(out, AdbConnection.A_CNXN, 0x01000000, maxData, "device::ro.product.name=mock;\0".getBytes(StandardCharsets.US_ASCII));
        int nextRemote = 100;
        while (true) {
            m = read(in);
            if (m.cmd == AdbConnection.A_OPEN) {
                int local = m.a0;
                int remote = nextRemote++;
                String svc = new String(m.data, StandardCharsets.UTF_8);
                svc = svc.substring(0, svc.indexOf('\0'));
                send(out, AdbConnection.A_OKAY, remote, local, new byte[0]);
                if (svc.startsWith("shell:")) {
                    shell(in, out, svc.substring(6), local, remote);
                    send(out, AdbConnection.A_CLSE, remote, local, new byte[0]);
                } else if (svc.equals("sync:")) {
                    sync(in, out, local, remote);
                } else {
                    send(out, AdbConnection.A_CLSE, 0, local, new byte[0]);
                }
            } else if (m.cmd == AdbConnection.A_CLSE) {
                // host closed; fine
            }
        }
    }

    private void shell(DataInputStream in, OutputStream out, String cmd, int local, int remote) throws Exception {
        Process p = new ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        InputStream pin = p.getInputStream();
        byte[] buf = new byte[maxData];
        int n;
        while ((n = pin.read(buf)) > 0) {
            byte[] part = new byte[n];
            System.arraycopy(buf, 0, part, 0, n);
            send(out, AdbConnection.A_WRTE, remote, local, part);
            Msg ack = read(in);
            if (ack.cmd != AdbConnection.A_OKAY) problems.add("expected OKAY after WRTE, got " + Integer.toHexString(ack.cmd));
        }
        p.waitFor();
    }

    private void sync(DataInputStream in, OutputStream out, int local, int remote) throws Exception {
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        String path = null;
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        while (true) {
            Msg m = read(in);
            if (m.cmd == AdbConnection.A_CLSE) return;
            if (m.cmd != AdbConnection.A_WRTE) continue;
            send(out, AdbConnection.A_OKAY, remote, local, new byte[0]);
            pending.write(m.data);
            while (true) {
                byte[] b = pending.toByteArray();
                if (b.length < 8) break;
                String id = new String(b, 0, 4, StandardCharsets.US_ASCII);
                int len = le(b, 4);
                if (id.equals("SEND")) {
                    if (b.length < 8 + len) break;
                    path = new String(b, 8, len, StandardCharsets.UTF_8);
                    if (!path.matches(".*,\\d+")) problems.add("bad SEND spec " + path);
                    path = path.substring(0, path.lastIndexOf(','));
                    consume(pending, b, 8 + len);
                } else if (id.equals("DATA")) {
                    if (b.length < 8 + len) break;
                    if (len > 65536) problems.add("DATA chunk over 64 KiB");
                    file.write(b, 8, len);
                    consume(pending, b, 8 + len);
                } else if (id.equals("DONE")) {
                    files.put(path, file.toByteArray());
                    consume(pending, b, 8);
                    byte[] ok = new byte[8];
                    System.arraycopy("OKAY".getBytes(StandardCharsets.US_ASCII), 0, ok, 0, 4);
                    send(out, AdbConnection.A_WRTE, remote, local, ok);
                    Msg ack = read(in);
                    if (ack.cmd != AdbConnection.A_OKAY) problems.add("expected OKAY after sync reply");
                } else if (id.equals("QUIT")) {
                    consume(pending, b, 8);
                } else {
                    problems.add("unknown sync id " + id);
                    return;
                }
            }
        }
    }

    private static void consume(ByteArrayOutputStream pending, byte[] all, int used) {
        pending.reset();
        pending.write(all, used, all.length - used);
    }

    // ---- RSA, the way adbd does it ----

    private BigInteger[] parseKey(byte[] payload) {
        String s = new String(payload, StandardCharsets.US_ASCII);
        s = s.substring(0, s.indexOf(' '));
        byte[] blob = Base64.getDecoder().decode(s);
        if (blob.length != 524) problems.add("public key blob is " + blob.length + " bytes, expected 524");
        int words = le(blob, 0);
        if (words != 64) problems.add("key length words " + words);
        long n0inv = le(blob, 4) & 0xffffffffL;
        BigInteger n = fromLe(blob, 8, 256);
        BigInteger rr = fromLe(blob, 8 + 256, 256);
        long e = le(blob, 8 + 512) & 0xffffffffL;
        long n0 = n.mod(BigInteger.ONE.shiftLeft(32)).longValue();
        if (((n0 * n0inv) & 0xffffffffL) != 0xffffffffL) problems.add("n0inv is not -1/n[0] mod 2^32");
        if (!rr.equals(BigInteger.ONE.shiftLeft(4096).mod(n))) problems.add("rr is not R^2 mod n");
        if (n.bitLength() != 2048) problems.add("modulus is not 2048 bits");
        return new BigInteger[] {n, BigInteger.valueOf(e)};
    }

    private boolean verify(byte[] token, byte[] sig) {
        byte[] info = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        byte[] expected = new byte[256];
        java.util.Arrays.fill(expected, (byte) 0xff);
        expected[0] = 0;
        expected[1] = 1;
        int tail = info.length + token.length;
        expected[256 - tail - 1] = 0;
        System.arraycopy(info, 0, expected, 256 - tail, info.length);
        System.arraycopy(token, 0, expected, 256 - token.length, token.length);
        synchronized (trusted) {
            for (BigInteger[] k : trusted) {
                byte[] got = new BigInteger(1, sig).modPow(k[1], k[0]).toByteArray();
                byte[] padded = new byte[256];
                if (got.length > 256 && !(got.length == 257 && got[0] == 0)) continue;
                int start = got.length == 257 ? 1 : 0;
                System.arraycopy(got, start, padded, 256 - (got.length - start), got.length - start);
                if (java.util.Arrays.equals(padded, expected)) return true;
            }
        }
        return false;
    }

    private static BigInteger fromLe(byte[] b, int off, int len) {
        byte[] be = new byte[len];
        for (int i = 0; i < len; i++) be[len - 1 - i] = b[off + i];
        return new BigInteger(1, be);
    }

    private static int le(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static void put(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
