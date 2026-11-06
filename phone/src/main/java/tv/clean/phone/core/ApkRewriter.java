package tv.clean.phone.core;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Makes a copy of an APK with another package name in its manifest, only the CPU types the TV needs, its native libraries
 * stored uncompressed and aligned to 4096 bytes (a system WebView is loaded straight out of the APK, and Android only
 * accepts that when they are), and a new signature. The ZIP is written by hand, entry by entry, so even a 200 MB file
 * never sits in memory.
 */
public final class ApkRewriter {
    private ApkRewriter() {}

    private static final String MANIFEST = "AndroidManifest.xml";

    /** The result of {@link #rewrite}. */
    public static final class Result {
        public Axml.Info before;
        public long bytes;
        public int entries;
        public int skippedAbiEntries;
    }

    private static final class Counting extends OutputStream {
        private final OutputStream out;
        long pos;

        Counting(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            pos++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            pos += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    private static final class Written {
        String name;
        int method;
        int flags;
        long crc;
        long csize;
        long usize;
        long offset;
    }

    private static boolean signatureFile(String name) {
        String n = name.toUpperCase(java.util.Locale.ROOT);
        return n.startsWith("META-INF/") && (n.equals("META-INF/MANIFEST.MF") || n.endsWith(".SF") || n.endsWith(".RSA")
            || n.endsWith(".DSA") || n.endsWith(".EC"));
    }

    /** The CPU folder of a native library entry ("lib/arm64-v8a/x.so" gives "arm64-v8a"), or null. */
    static String abiOf(String name) {
        if (!name.startsWith("lib/")) return null;
        int slash = name.indexOf('/', 4);
        return slash < 0 ? null : name.substring(4, slash);
    }

    /**
     * @param newPackage  the package name to put in the manifest, or null to keep it
     * @param keepAbis    CPU folders to keep (e.g. "armeabi-v7a"); null or no match with the APK keeps all of them
     */
    public static Result rewrite(File in, File out, String newPackage, Set<String> keepAbis, Log log) throws IOException {
        Result result = new Result();
        try (ZipFile zip = new ZipFile(in)) {
            ZipEntry manifestEntry = zip.getEntry(MANIFEST);
            if (manifestEntry == null) throw new IOException("This file has no AndroidManifest.xml: it is not an APK.");
            byte[] manifest = readAll(zip, manifestEntry);
            result.before = Axml.read(manifest);

            // which CPU folders does the APK have, and which do we keep
            List<String> present = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                String abi = abiOf(e.nextElement().getName());
                if (abi != null && !present.contains(abi)) present.add(abi);
            }
            Set<String> keep = null;
            if (keepAbis != null) {
                for (String abi : present) {
                    if (keepAbis.contains(abi)) {
                        keep = keepAbis;
                        break;
                    }
                }
            }

            Map<String, byte[]> digests = new LinkedHashMap<>();
            List<Written> written = new ArrayList<>();
            MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("This phone has no SHA-256.");
            }
            byte[] buf = new byte[64 * 1024];
            try (Counting o = new Counting(new BufferedOutputStream(new FileOutputStream(out), 256 * 1024))) {
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    ZipEntry ze = e.nextElement();
                    String name = ze.getName();
                    if (ze.isDirectory() || signatureFile(name)) continue;
                    String abi = abiOf(name);
                    if (keep != null && abi != null && !keep.contains(abi)) {
                        result.skippedAbiEntries++;
                        continue;
                    }
                    if (written.size() >= 60000) throw new IOException("This APK has too many files.");
                    boolean isManifest = name.equals(MANIFEST);
                    boolean library = name.endsWith(".so");
                    boolean stored = library || (ze.getMethod() == ZipEntry.STORED && !isManifest);

                    byte[] replacement = null;
                    if (isManifest) {
                        replacement = newPackage != null && !newPackage.equals(result.before.packageName)
                            ? Axml.renamePackage(manifest, result.before.packageName, newPackage) : manifest;
                    }
                    sha.reset();
                    Written w = new Written();
                    w.name = name;
                    w.offset = o.pos;
                    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
                    if (stored) {
                        long size = replacement != null ? replacement.length : ze.getSize();
                        long crc = ze.getCrc();
                        if (replacement != null) {
                            CRC32 c = new CRC32();
                            c.update(replacement, 0, replacement.length);
                            crc = c.getValue();
                        }
                        if (size < 0 || crc < 0) throw new IOException("Cannot read the size of " + name);
                        int align = library ? 4096 : 4;
                        long dataAt = o.pos + 30 + nameBytes.length;
                        int pad = (int) ((align - dataAt % align) % align);
                        if (pad > 0 && pad < 4) pad += align;
                        byte[] extra = new byte[pad];
                        if (pad > 0) {
                            extra[0] = (byte) 0x35;
                            extra[1] = (byte) 0xd9;
                            extra[2] = (byte) (pad - 4);
                            extra[3] = (byte) ((pad - 4) >> 8);
                        }
                        header(o, 0, 0, crc, size, size, nameBytes, extra);
                        w.method = 0;
                        w.flags = 0;
                        w.crc = crc;
                        w.csize = size;
                        w.usize = size;
                        CRC32 check = new CRC32();
                        long total = 0;
                        if (replacement != null) {
                            o.write(replacement, 0, replacement.length);
                            sha.update(replacement, 0, replacement.length);
                            total = replacement.length;
                            check.update(replacement, 0, replacement.length);
                        } else {
                            try (InputStream is = zip.getInputStream(ze)) {
                                int n;
                                while ((n = is.read(buf)) > 0) {
                                    o.write(buf, 0, n);
                                    sha.update(buf, 0, n);
                                    check.update(buf, 0, n);
                                    total += n;
                                }
                            }
                        }
                        if (total != size || check.getValue() != crc) throw new IOException(name + " is damaged (its checksum does not match).");
                    } else {
                        header(o, 8, 8, 0, 0, 0, nameBytes, new byte[0]);
                        Deflater d = new Deflater(6, true);
                        long start = o.pos;
                        CRC32 crc = new CRC32();
                        long total = 0;
                        DeflaterOutputStream dos = new DeflaterOutputStream(o, d, 32 * 1024);
                        if (replacement != null) {
                            dos.write(replacement, 0, replacement.length);
                            sha.update(replacement, 0, replacement.length);
                            crc.update(replacement, 0, replacement.length);
                            total = replacement.length;
                        } else {
                            try (InputStream is = zip.getInputStream(ze)) {
                                int n;
                                while ((n = is.read(buf)) > 0) {
                                    dos.write(buf, 0, n);
                                    sha.update(buf, 0, n);
                                    crc.update(buf, 0, n);
                                    total += n;
                                }
                            }
                        }
                        dos.finish();
                        d.end();
                        w.method = 8;
                        w.flags = 8;
                        w.crc = crc.getValue();
                        w.usize = total;
                        w.csize = o.pos - start;
                        le32(o, 0x08074b50);
                        le32(o, w.crc);
                        le32(o, w.csize);
                        le32(o, w.usize);
                    }
                    digests.put(name, sha.digest());
                    written.add(w);
                    if (log != null && written.size() % 200 == 0) log.log("  " + written.size() + " files...");
                }

                ApkSigner.Files sig = ApkSigner.sign(digests);
                addPlain(o, written, "META-INF/MANIFEST.MF", sig.manifest);
                addPlain(o, written, "META-INF/CERT.SF", sig.sf);
                addPlain(o, written, "META-INF/CERT.RSA", sig.rsa);

                long cdStart = o.pos;
                for (Written w : written) central(o, w);
                long cdSize = o.pos - cdStart;
                le32(o, 0x06054b50);
                le16(o, 0);
                le16(o, 0);
                le16(o, written.size());
                le16(o, written.size());
                le32(o, cdSize);
                le32(o, cdStart);
                le16(o, 0);
                o.flush();
                result.entries = written.size();
                result.bytes = o.pos;
            }
        }
        return result;
    }

    private static byte[] readAll(ZipFile zip, ZipEntry e) throws IOException {
        if (e.getSize() > 16 * 1024 * 1024) throw new IOException(e.getName() + " is far too big.");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = zip.getInputStream(e)) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        }
        return bo.toByteArray();
    }

    private static void addPlain(Counting o, List<Written> written, String name, byte[] data) throws IOException {
        Written w = new Written();
        w.name = name;
        w.offset = o.pos;
        CRC32 c = new CRC32();
        c.update(data, 0, data.length);
        w.crc = c.getValue();
        w.csize = data.length;
        w.usize = data.length;
        header(o, 0, 0, w.crc, data.length, data.length, name.getBytes(StandardCharsets.UTF_8), new byte[0]);
        o.write(data, 0, data.length);
        written.add(w);
    }

    private static void header(OutputStream o, int flags, int method, long crc, long csize, long usize, byte[] name, byte[] extra)
        throws IOException {
        le32(o, 0x04034b50);
        le16(o, 20);
        le16(o, flags);
        le16(o, method);
        le16(o, 0);
        le16(o, 0x21);
        le32(o, crc);
        le32(o, csize);
        le32(o, usize);
        le16(o, name.length);
        le16(o, extra.length);
        o.write(name, 0, name.length);
        o.write(extra, 0, extra.length);
    }

    private static void central(OutputStream o, Written w) throws IOException {
        byte[] name = w.name.getBytes(StandardCharsets.UTF_8);
        le32(o, 0x02014b50);
        le16(o, 20);
        le16(o, 20);
        le16(o, w.flags);
        le16(o, w.method);
        le16(o, 0);
        le16(o, 0x21);
        le32(o, w.crc);
        le32(o, w.csize);
        le32(o, w.usize);
        le16(o, name.length);
        le16(o, 0);
        le16(o, 0);
        le16(o, 0);
        le16(o, 0);
        le32(o, 0);
        le32(o, w.offset);
        o.write(name, 0, name.length);
    }

    private static void le16(OutputStream o, int v) throws IOException {
        o.write(v);
        o.write(v >> 8);
    }

    private static void le32(OutputStream o, long v) throws IOException {
        o.write((int) v);
        o.write((int) (v >> 8));
        o.write((int) (v >> 16));
        o.write((int) (v >> 24));
    }
}
