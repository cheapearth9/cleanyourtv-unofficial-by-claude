package tv.clean.phone.adb;

import java.io.File;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import tv.clean.phone.AdbTvShell;

/** The phone must outlive a Wi-Fi hiccup and a slow TV: java tv.clean.phone.adb.ReconnectSelfTest */
public final class ReconnectSelfTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        AdbKey key = AdbKey.loadOrCreate(Files.createTempDirectory("rckey").toFile());
        try (MockAdbd tv = new MockAdbd(4096)) {
            List<String> notes = new ArrayList<>();
            AdbTvShell sh = new AdbTvShell("127.0.0.1", tv.port(), key, null, notes::add);
            sh.open(5000);
            check(sh.run("echo one", 5000).equals("one\n"), "plain command");

            long t0 = System.currentTimeMillis();
            String slow = sh.run("for i in 1 2 3 4; do echo $i; sleep 1; done", 2500);
            long took = System.currentTimeMillis() - t0;
            check(slow.equals("1\n2\n3\n4\n") && took > 3000, "a command that keeps printing outlives the idle timeout (" + took + " ms, idle limit 2500)");

            try {
                sh.run("sleep 6; echo late", 1500);
                check(false, "a silent command times out");
            } catch (SocketTimeoutException e) {
                check(e.getMessage().contains("nothing for 1 s") || e.getMessage().contains("did not answer"), "silent TV: clear timeout message (" + e.getMessage() + ")");
            }
            check(sh.run("echo two", 5000).equals("two\n") && notes.stream().anyMatch(n -> n.contains("Reconnecting")), "after a timeout the next command gets a fresh connection");

            tv.dropAll();
            Thread.sleep(200);
            String again = sh.run("echo three", 5000);
            check(again.equals("three\n") && notes.stream().anyMatch(n -> n.contains("dropped")), "connection cut: reconnects and re-sends the command (" + notes + ")");
            tv.dropAll();
            Thread.sleep(200);
            sh.push("hello".getBytes("UTF-8"), "/data/local/tmp/x", 0644);
            check(new String(tv.files.get("/data/local/tmp/x"), "UTF-8").equals("hello"), "connection cut: a push is retried too");
            File big = File.createTempFile("big", ".apk");
            byte[] blob = new byte[3_000_000];
            new java.util.Random(5).nextBytes(blob);
            Files.write(big.toPath(), blob);
            long[] last = {0};
            tv.dropAll();
            Thread.sleep(200);
            sh.pushFile(big, "/data/local/tmp/big.apk", 0644, (sent, total) -> last[0] = sent);
            check(java.util.Arrays.equals(tv.files.get("/data/local/tmp/big.apk"), blob) && last[0] == blob.length, "a 3 MB file streams across, byte-identical, with progress (even after a cut)");
            check(sh.reconnects() >= 3 && tv.problems.isEmpty(), "reconnected " + sh.reconnects() + " times, no protocol problems " + tv.problems);
            sh.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
