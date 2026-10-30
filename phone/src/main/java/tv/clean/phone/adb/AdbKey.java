package tv.clean.phone.adb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * The RSA identity this phone presents to a TV's adbd. The TV remembers it after "Always allow",
 * so the prompt appears once per TV. Pure Java: no Android classes, so it runs in the JVM self-test.
 */
public final class AdbKey {
    private static final int KEY_BITS = 2048;
    private static final int KEY_WORDS = KEY_BITS / 32;
    /** DER DigestInfo header for SHA-1. adbd treats its 20-byte token as an already-computed digest. */
    private static final byte[] SHA1_DIGEST_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
    private static final String NAME = "cleanyourtv-phone@android";

    private final PrivateKey privateKey;
    private final BigInteger modulus;
    private final BigInteger exponent;

    private AdbKey(PrivateKey privateKey, BigInteger modulus, BigInteger exponent) {
        this.privateKey = privateKey;
        this.modulus = modulus;
        this.exponent = exponent;
    }

    /** Loads the key stored in {@code dir}, or makes and stores a new one. */
    public static AdbKey loadOrCreate(File dir) throws IOException {
        File file = new File(dir, "adbkey.pk8");
        try {
            if (file.isFile()) {
                byte[] der = readAll(file);
                PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
                if (key instanceof RSAPrivateCrtKey) {
                    RSAPrivateCrtKey crt = (RSAPrivateCrtKey) key;
                    if (crt.getModulus().bitLength() == KEY_BITS) {
                        return new AdbKey(key, crt.getModulus(), crt.getPublicExponent());
                    }
                }
            }
        } catch (GeneralSecurityException | IOException ignored) {
            // unreadable or wrong shape: make a new one below (the TV just asks "Allow?" again)
        }
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(KEY_BITS);
            KeyPair pair = gen.generateKeyPair();
            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) pair.getPrivate();
            if (dir != null) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(pair.getPrivate().getEncoded());
                }
            }
            return new AdbKey(pair.getPrivate(), crt.getModulus(), crt.getPublicExponent());
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot create an ADB key: " + e, e);
        }
    }

    /** Signs adbd's 20-byte AUTH token the way adbd's RSA_verify(NID_sha1) expects. */
    public byte[] sign(byte[] token) throws IOException {
        try {
            Signature sig = Signature.getInstance("NONEwithRSA");
            sig.initSign(privateKey);
            sig.update(SHA1_DIGEST_INFO);
            sig.update(token);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException("signing failed: " + e, e);
        }
    }

    /** The AUTH_RSAPUBLICKEY payload: base64 of Android's mincrypt RSAPublicKey struct, a name, and a NUL. */
    public byte[] publicKeyPayload() {
        byte[] blob = publicKeyBlob();
        byte[] b64 = Base64Lite.encode(blob);
        byte[] tail = (" " + NAME + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] out = new byte[b64.length + tail.length];
        System.arraycopy(b64, 0, out, 0, b64.length);
        System.arraycopy(tail, 0, out, b64.length, tail.length);
        return out;
    }

    /** struct RSAPublicKey { u32 len; u32 n0inv; u32 n[len]; u32 rr[len]; u32 exponent; } little endian. */
    /** What the TV's "Allow USB debugging?" dialog shows: MD5 of the 524-byte public key, as AA:BB:CC:... */
    public String fingerprint() {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5").digest(publicKeyBlob());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", d[i] & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    byte[] publicKeyBlob() {
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = modulus.mod(two32);
        BigInteger n0inv = two32.subtract(n0.modInverse(two32)); // -1 / n[0] mod 2^32
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(modulus); // (2^2048)^2 mod n
        ByteArrayOutputStream out = new ByteArrayOutputStream(4 + 4 + KEY_WORDS * 8 + 4);
        writeLe32(out, KEY_WORDS);
        writeLe32(out, n0inv.longValue());
        writeWordsLe(out, modulus);
        writeWordsLe(out, rr);
        writeLe32(out, exponent.longValue());
        return out.toByteArray();
    }

    private static void writeWordsLe(ByteArrayOutputStream out, BigInteger value) {
        byte[] be = value.toByteArray();
        byte[] le = new byte[KEY_WORDS * 4];
        for (int i = 0; i < le.length && i < be.length; i++) {
            le[i] = be[be.length - 1 - i];
        }
        out.write(le, 0, le.length);
    }

    private static void writeLe32(ByteArrayOutputStream out, long v) {
        out.write((int) (v & 0xff));
        out.write((int) ((v >> 8) & 0xff));
        out.write((int) ((v >> 16) & 0xff));
        out.write((int) ((v >> 24) & 0xff));
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** For the self-test: the public half as a JCA spec. */
    RSAPublicKeySpec publicSpec() {
        return new RSAPublicKeySpec(modulus, exponent);
    }
}
