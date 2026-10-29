package tv.clean.phone;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Finds devices on the phone's Wi-Fi /24 that accept connections on the ADB port. */
final class LanScanner {
    private LanScanner() {}

    static List<String> findAdbHosts(int port) {
        final List<String> found = Collections.synchronizedList(new ArrayList<String>());
        String prefix = localPrefix();
        if (prefix == null) return found;
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i < 255; i++) {
            final String host = prefix + i;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(host, port), 450);
                        found.add(host);
                    } catch (Exception ignored) {
                        // closed or unreachable
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(25, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        List<String> sorted = new ArrayList<>(found);
        Collections.sort(sorted);
        return sorted;
    }

    /** "192.168.1." for the first private IPv4 address of an up, non-loopback interface. */
    static String localPrefix() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        return ip.substring(0, ip.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (Exception ignored) {
            // no network
        }
        return null;
    }
}
