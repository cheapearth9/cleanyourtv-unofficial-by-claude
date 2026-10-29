package tv.clean.phone.adb;

import java.io.File;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

/** Plain-Java test (no JUnit needed): java tv.clean.phone.adb.AdbSelfTest */
public final class AdbSelfTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("adbkey").toFile();
        AdbKey key = AdbKey.loadOrCreate(dir);
        AdbKey again = AdbKey.loadOrCreate(dir);
        check(Arrays.equals(key.publicKeyPayload(), again.publicKeyPayload()), "key persists across loads");

        for (int maxData : new int[] {4096, 256 * 1024}) {
            try (MockAdbd tv = new MockAdbd(maxData)) {
                final boolean[] prompted = {false};
                try (AdbConnection c = AdbConnection.connect("127.0.0.1", tv.port(), key, 5000, m -> prompted[0] = true)) {
                    check(prompted[0] && tv.promptsShown == 1, "[" + maxData + "] unknown key: public key offered, 'Allow' prompt shown");
                    check(c.banner().contains("ro.product.name=mock"), "[" + maxData + "] CNXN banner received");
                    check(c.maxData() == maxData, "[" + maxData + "] max payload negotiated");
                    AdbConnection.ShellResult r = c.shell("echo hello; echo oops 1>&2; sh -c 'exit 3'", 5000);
                    check(r.output.equals("hello\noops\n") && r.exitCode == 3, "[" + maxData + "] shell output + exit code (" + r.exitCode + ")");
                    String big = c.shellOutput("seq 1 60000", 20000);
                    check(big.startsWith("1\n2\n") && big.endsWith("60000\n") && big.split("\n").length == 60000, "[" + maxData + "] 340 KB of shell output reassembled");
                    byte[] payload = new byte[700_000];
                    new Random(7).nextBytes(payload);
                    c.push(payload, "/data/local/tmp/tvhome.apk", 0644, 30000);
                    byte[] got = tv.files.get("/data/local/tmp/tvhome.apk");
                    check(got != null && Arrays.equals(MessageDigest.getInstance("SHA-1").digest(got), MessageDigest.getInstance("SHA-1").digest(payload)), "[" + maxData + "] 700 KB push arrives byte-identical");
                    c.push(new byte[0], "/data/local/tmp/empty", 0644, 5000);
                    check(tv.files.get("/data/local/tmp/empty").length == 0, "[" + maxData + "] empty push");
                    try {
                        c.shell("sleep 5", 400);
                        check(false, "[" + maxData + "] timeout is raised");
                    } catch (SocketTimeoutException e) {
                        check(true, "[" + maxData + "] a stuck command times out instead of hanging");
                    }
                }
                try (AdbConnection c = AdbConnection.connect("127.0.0.1", tv.port(), key, 5000, m -> { throw new AssertionError("prompted twice"); })) {
                    check(tv.promptsShown == 1 && tv.signatureLogins == 1, "[" + maxData + "] known key logs in by signature, no second prompt");
                    check(c.shellOutput("echo again", 5000).equals("again\n"), "[" + maxData + "] second session works");
                }
                check(tv.problems.isEmpty(), "[" + maxData + "] mock saw no protocol violations " + tv.problems);
            }
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
