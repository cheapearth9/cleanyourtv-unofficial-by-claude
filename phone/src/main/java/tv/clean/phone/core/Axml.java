package tv.clean.phone.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Just enough of Android's binary XML (the compiled AndroidManifest.xml inside an APK) to read an app's package name and
 * version code and to rename its package. Plain Java, no Android classes, so it runs in the JVM tests too.
 */
public final class Axml {
    private Axml() {}

    private static final int FILE = 0x0003;
    private static final int POOL = 0x0001;
    private static final int START_ELEMENT = 0x0102;
    private static final int UTF8 = 0x100;

    /** What the manifest says about the app. */
    public static final class Info {
        public String packageName = "";
        public long versionCode = -1;
        /** Every string of the manifest, for "does it mention X" checks. */
        public List<String> strings = new ArrayList<>();
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static int u32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static void put16(ByteArrayOutputStream o, int v) {
        o.write(v);
        o.write(v >> 8);
    }

    private static void put32(ByteArrayOutputStream o, int v) {
        o.write(v);
        o.write(v >> 8);
        o.write(v >> 16);
        o.write(v >> 24);
    }

    private static final class Pool {
        int start;
        int size;
        int flags;
        int styleCount;
        List<String> strings = new ArrayList<>();
        byte[] styleData = new byte[0];
        int[] styleOffsets = new int[0];
    }

    private static Pool readPool(byte[] b) {
        if (b.length < 8 || u16(b, 0) != FILE) throw new IllegalArgumentException("not a compiled Android manifest");
        int pos = u16(b, 2);
        if (pos + 28 > b.length || u16(b, pos) != POOL) throw new IllegalArgumentException("the manifest has no string table");
        Pool p = new Pool();
        p.start = pos;
        int header = u16(b, pos + 2);
        p.size = u32(b, pos + 4);
        int count = u32(b, pos + 8);
        p.styleCount = u32(b, pos + 12);
        p.flags = u32(b, pos + 16);
        int stringsStart = u32(b, pos + 20);
        int stylesStart = u32(b, pos + 24);
        if (count < 0 || count > 1 << 20 || p.styleCount < 0 || p.styleCount > 1 << 20 || pos + p.size > b.length) {
            throw new IllegalArgumentException("the manifest string table is damaged");
        }
        boolean utf8 = (p.flags & UTF8) != 0;
        int offsets = pos + header;
        for (int i = 0; i < count; i++) {
            int at = pos + stringsStart + u32(b, offsets + 4 * i);
            p.strings.add(utf8 ? readUtf8(b, at) : readUtf16(b, at));
        }
        if (p.styleCount > 0) {
            p.styleOffsets = new int[p.styleCount];
            for (int i = 0; i < p.styleCount; i++) p.styleOffsets[i] = u32(b, offsets + 4 * count + 4 * i);
            int end = pos + p.size;
            p.styleData = new byte[end - (pos + stylesStart)];
            System.arraycopy(b, pos + stylesStart, p.styleData, 0, p.styleData.length);
        }
        return p;
    }

    private static String readUtf16(byte[] b, int at) {
        int len = u16(b, at);
        at += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7fff) << 16) | u16(b, at);
            at += 2;
        }
        return new String(b, at, len * 2, StandardCharsets.UTF_16LE);
    }

    private static String readUtf8(byte[] b, int at) {
        int chars = b[at++] & 0xff;
        if ((chars & 0x80) != 0) at++;
        int bytes = b[at++] & 0xff;
        if ((bytes & 0x80) != 0) {
            bytes = ((bytes & 0x7f) << 8) | (b[at++] & 0xff);
        }
        return new String(b, at, bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteArrayOutputStream o, String s, boolean utf8) {
        if (utf8) {
            byte[] raw = s.getBytes(StandardCharsets.UTF_8);
            int chars = s.length();
            if (chars > 0x7f) {
                o.write((chars >> 8) | 0x80);
                o.write(chars);
            } else {
                o.write(chars);
            }
            if (raw.length > 0x7f) {
                o.write((raw.length >> 8) | 0x80);
                o.write(raw.length);
            } else {
                o.write(raw.length);
            }
            o.write(raw, 0, raw.length);
            o.write(0);
        } else {
            int chars = s.length();
            if (chars > 0x7fff) {
                put16(o, (chars >> 16) | 0x8000);
                put16(o, chars & 0xffff);
            } else {
                put16(o, chars);
            }
            byte[] raw = s.getBytes(StandardCharsets.UTF_16LE);
            o.write(raw, 0, raw.length);
            put16(o, 0);
        }
    }

    /** Reads the package name, version code and strings of a compiled manifest. */
    public static Info read(byte[] b) {
        Pool pool = readPool(b);
        Info info = new Info();
        info.strings = pool.strings;
        int pos = pool.start + pool.size;
        while (pos + 8 <= b.length) {
            int type = u16(b, pos);
            int size = u32(b, pos + 4);
            if (size < 8 || pos + size > b.length) break;
            if (type == START_ELEMENT && pos + 36 <= b.length) {
                int name = u32(b, pos + 20);
                if (name >= 0 && name < pool.strings.size() && pool.strings.get(name).equals("manifest")) {
                    int attrStart = u16(b, pos + 24);
                    int attrSize = u16(b, pos + 26);
                    int attrCount = u16(b, pos + 28);
                    for (int i = 0; i < attrCount; i++) {
                        int a = pos + 16 + attrStart + i * attrSize;
                        if (a + 20 > b.length) break;
                        int attrName = u32(b, a + 4);
                        if (attrName < 0 || attrName >= pool.strings.size()) continue;
                        String n = pool.strings.get(attrName);
                        int raw = u32(b, a + 8);
                        if (n.equals("package") && raw >= 0 && raw < pool.strings.size()) {
                            info.packageName = pool.strings.get(raw);
                        } else if (n.equals("versionCode")) {
                            info.versionCode = u32(b, a + 16) & 0xffffffffL;
                        }
                    }
                }
                break;
            }
            pos += size;
        }
        return info;
    }

    /** True when {@code s} is the package name or something named under it ("pkg.Provider"). */
    private static boolean under(String s, String pkg) {
        return s.equals(pkg) || s.startsWith(pkg + ".");
    }

    /**
     * The manifest with its package renamed: every string that is {@code from} or starts with {@code from + "."}
     * (the package attribute, and names such as content provider authorities made from it) now says {@code to}.
     * Classes live under other names (org.chromium...), so those are left alone.
     */
    public static byte[] renamePackage(byte[] b, String from, String to) {
        Pool pool = readPool(b);
        boolean utf8 = (pool.flags & UTF8) != 0;
        int header = u16(b, pool.start + 2);
        int count = pool.strings.size();

        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = strings.size();
            String s = pool.strings.get(i);
            if (under(s, from)) s = to + s.substring(from.length());
            writeString(strings, s, utf8);
        }
        while (strings.size() % 4 != 0) strings.write(0);

        ByteArrayOutputStream newPool = new ByteArrayOutputStream();
        int stringsStart = header + 4 * count + 4 * pool.styleCount;
        int stylesStart = pool.styleCount > 0 ? stringsStart + strings.size() : 0;
        put16(newPool, POOL);
        put16(newPool, header);
        put32(newPool, stringsStart + strings.size() + pool.styleData.length);
        put32(newPool, count);
        put32(newPool, pool.styleCount);
        put32(newPool, pool.flags & ~1); // the strings are no longer sorted
        put32(newPool, stringsStart);
        put32(newPool, stylesStart);
        for (int i = header - 28; i > 0; i--) newPool.write(0);
        for (int off : offsets) put32(newPool, off);
        for (int off : pool.styleOffsets) put32(newPool, off);
        newPool.write(strings.toByteArray(), 0, strings.size());
        newPool.write(pool.styleData, 0, pool.styleData.length);

        byte[] pooled = newPool.toByteArray();
        int rest = pool.start + pool.size;
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + 256);
        out.write(b, 0, pool.start);
        out.write(pooled, 0, pooled.length);
        out.write(b, rest, b.length - rest);
        byte[] result = out.toByteArray();
        result[4] = (byte) result.length;
        result[5] = (byte) (result.length >> 8);
        result[6] = (byte) (result.length >> 16);
        result[7] = (byte) (result.length >> 24);
        return result;
    }
}
