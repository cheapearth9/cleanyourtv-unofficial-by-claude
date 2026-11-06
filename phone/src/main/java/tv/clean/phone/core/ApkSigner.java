package tv.clean.phone.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signs an APK the old way (v1, the META-INF signature files), which every Android version understands. Written by hand
 * because Android has no public API for it. The key is a throw-away one made for this purpose: a system app on the TV only
 * has to carry a valid signature, it does not have to match anything.
 */
public final class ApkSigner {
    private ApkSigner() {}

    private static final String KEY_HEX = ""
        + "308204bd020100300d06092a864886f70d0101010500048204a7308204a30201000282010100a55a5ac840eefea6fff4"
        + "5af10e778795f6411ccdd22707029a0cddefd2a046bf6108d7b93a0fcd1af316132f6860dbfbb188a7dde1ab573ce294"
        + "e91b383004b8166897317f74f2dab867b47e9011d3c8dec9cab85a05364ff06b23dc5d636404c39a5029b7ebc5acd672"
        + "d23a73612ae3317def00faa21c9fb439567c230f5ff6133e9905fa88bc989c1d9ca3e577468c40be1363b9c758b23bad"
        + "0725749853c9aff03c247d71806d9181144bee9065111bec2469f6f389b3ee2256821b4aa0429291a9cf5ed82dfd582c"
        + "5c76e2a3a2c9673fcb026b5c72e1e9f18ef18e1048ba2cd315183df40018a5d32f569fd21db407869beda30f62da2369"
        + "ea99e63910a302030100010282010041f70eda6ed52970341226a99e2cdf415b3154ad6396403cb07a4b9bde3a249f3a"
        + "b5cd61cea0f9213432ed4a6f689cba07744e0bbd789e5fe2c0a7523d93227ed229bd8f789a8e3be56f31d97fde2cd028"
        + "d8f5ef9fb20e9dcb7ae29e89ff19aab0e201f2f2b5b371a547bf48a438f2641a9d73d8bf2427292b3be89db35af1dbd4"
        + "54a6f75b284dff59e081d265991a9083ca59fef60a59026e83db72c321789f63c135cedab7b24de23266649f2627fd36"
        + "2b5418d50e8f438998ac62226e4fc3974d262f206978da26f5b3ed442201d806acba706fbe9fcbd203f66d48d69f7bcd"
        + "089141ba69a112bc6ceb865271d6bded5fc2072e07535aa94aaa369f25a8b102818100d255ed6b8519b4f6a7c7932536"
        + "16dd7bb72f94ef024d21be1a8f62e4ab25fd1ebac42eef774a3eb97a2cc48e5d09fd2f43d86805b04026396125ab5e79"
        + "49dfeb2954ec95b9acb0b8262ca0a03efc972478ab68e6b4368ed93965ab112a2f92a3e842b540ffb9a900f90bc34c1e"
        + "4090e71da79cd6717b0062f6fb0464e406993902818100c9405ecc627604fb84d9d5923c13840b469a739812d95e515b"
        + "fd51f0738d8ab89576afe21d25f6c5db9e224c3758d82884944bdb1ad9b0a2997a9b8b0b0d54e1530ad8b29323458a5d"
        + "58f99a93cc1dd3abf5d8e0cc420bc38120cbb0612d1c8fa88ab64715da957b0b60e52e00337892ca304fb1fa572f07d8"
        + "de730bbe5444bb0281805aa8dee4f141db56558b184979bef14859f51064d4c11a422e808f47b0dd76f4bfdbbfbf47d3"
        + "a0a5a19b9a3d22ad238c33da1b3c18740e05bd71d723a973c6283e70746934d6d2750d3f3f1cd3095cbc43a6de585265"
        + "11bd5f980adc232ace3207f278ddc35beb14984411b1c5785efcbf3377ce96ab45c7625d5fcf7cd9bb4102818100804d"
        + "26629c9eeafc42acf168621e7aff094aec48d63377dadc8f7a0e964c7554ca0cb982a8dc8c1f85aca1e6ff1e07e884e5"
        + "5a1adc5a4e2ecb340c37af9b1732f35325e56ac280d1fe42fc161d05733a8015a2e765b0f178f938a442d52102655fa8"
        + "d71e28838aeabfcd4d2e6f7860f50c2b92933fab1753a461e4ec66b1a88302818078f06258d7b3c8a4b752146b7aa33b"
        + "99b4079c6d42902af3e9f7c00f3051ed15ca1d557263704a888dec9734cf4ca8496fa17179d142b356856fe32b82130b"
        + "1d59c4a7486b94230d8bf8d1d99ec5bbf98a0eb1c47243748b4ead282377711d30986ca2511d82e374d7d52ecfa447e9"
        + "4f7ec8b8a7364f696efb440bf981790e4b";
    private static final String CERT_HEX = ""
        + "3082032d30820215a003020102021401f8e47d4ca566a0765976780c0a334c14795b76300d06092a864886f70d01010b"
        + "050030253123302106035504030c1a636c65616e796f757274762073797374656d20776562766965773020170d323631"
        + "3130363131353432335a180f32313236313031333131353432335a30253123302106035504030c1a636c65616e796f75"
        + "7274762073797374656d207765627669657730820122300d06092a864886f70d01010105000382010f003082010a0282"
        + "010100a55a5ac840eefea6fff45af10e778795f6411ccdd22707029a0cddefd2a046bf6108d7b93a0fcd1af316132f68"
        + "60dbfbb188a7dde1ab573ce294e91b383004b8166897317f74f2dab867b47e9011d3c8dec9cab85a05364ff06b23dc5d"
        + "636404c39a5029b7ebc5acd672d23a73612ae3317def00faa21c9fb439567c230f5ff6133e9905fa88bc989c1d9ca3e5"
        + "77468c40be1363b9c758b23bad0725749853c9aff03c247d71806d9181144bee9065111bec2469f6f389b3ee2256821b"
        + "4aa0429291a9cf5ed82dfd582c5c76e2a3a2c9673fcb026b5c72e1e9f18ef18e1048ba2cd315183df40018a5d32f569f"
        + "d21db407869beda30f62da2369ea99e63910a30203010001a3533051301d0603551d0e04160414e4c8aadbd23601d136"
        + "9a54323fb32d3b777360d3301f0603551d23041830168014e4c8aadbd23601d1369a54323fb32d3b777360d3300f0603"
        + "551d130101ff040530030101ff300d06092a864886f70d01010b050003820101008ae8f2b12f13f333dac18758ec88d6"
        + "835541d59d00f8b79fdb9bd1460cd2b6a27e385f8205f51bccfc7130878b3813518eb11f8754a411fdd4c6df6a5224ba"
        + "2be87f6adabd23eb765a3e3dea0e34be9fdc07ea6c07ad4ee844fdef8320a4ec683734566edeb81080a046af85d1a319"
        + "15ee335cc645fc2739bf5b705fdad87aec006315f9d8cfa4d3e81f52ee2b23b3454d6b620c8a318f274192db67396c08"
        + "23d0e39e0a4314b4dd2d896e079d0652fa965ee6cebb890348d6fd3da0fefcbb4b66f39cffa7bc8c9ba38a3550328ab9"
        + "38d36fe679c559c6294cd93efad062d733f8808c9b7ad9619779661a447f384cc9651b9f9b8caa0a617c9263fedb8734"
        + "60";

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static String base64(byte[] in) {
        final String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.length; i += 3) {
            int b = (in[i] & 0xff) << 16;
            if (i + 1 < in.length) b |= (in[i + 1] & 0xff) << 8;
            if (i + 2 < in.length) b |= in[i + 2] & 0xff;
            sb.append(abc.charAt((b >> 18) & 63)).append(abc.charAt((b >> 12) & 63));
            sb.append(i + 1 < in.length ? abc.charAt((b >> 6) & 63) : '=');
            sb.append(i + 2 < in.length ? abc.charAt(b & 63) : '=');
        }
        return sb.toString();
    }

    /** What {@link #sign} produces: the three files that go into META-INF. */
    public static final class Files {
        public byte[] manifest;
        public byte[] sf;
        public byte[] rsa;
    }

    /** {@code digests}: entry name to the SHA-256 of its uncompressed content. Order is kept. */
    public static Files sign(Map<String, byte[]> digests) throws IOException {
        try {
            StringBuilder mf = new StringBuilder("Manifest-Version: 1.0\r\nCreated-By: cleanyourtv\r\n\r\n");
            Map<String, String> sections = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : digests.entrySet()) {
                String section = line("Name: " + e.getKey()) + "SHA-256-Digest: " + base64(e.getValue()) + "\r\n\r\n";
                sections.put(e.getKey(), section);
                mf.append(section);
            }
            byte[] manifest = mf.toString().getBytes(StandardCharsets.UTF_8);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            StringBuilder sf = new StringBuilder("Signature-Version: 1.0\r\nCreated-By: cleanyourtv\r\n");
            sf.append("SHA-256-Digest-Manifest: ").append(base64(sha.digest(manifest))).append("\r\n\r\n");
            for (Map.Entry<String, String> e : sections.entrySet()) {
                sf.append(line("Name: " + e.getKey()));
                sf.append("SHA-256-Digest: ").append(base64(sha.digest(e.getValue().getBytes(StandardCharsets.UTF_8)))).append("\r\n\r\n");
            }
            byte[] sfBytes = sf.toString().getBytes(StandardCharsets.UTF_8);

            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(hex(KEY_HEX)));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(sfBytes);
            byte[] signature = sig.sign();

            Files f = new Files();
            f.manifest = manifest;
            f.sf = sfBytes;
            f.rsa = pkcs7(hex(CERT_HEX), signature);
            return f;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Could not sign: " + e.getMessage());
        }
    }

    /** A manifest line wrapped at 72 bytes, as the format asks. */
    private static String line(String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= 70) return s + "\r\n";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int max = 70;
        while (i < raw.length) {
            int n = Math.min(max, raw.length - i);
            if (i > 0) sb.append(" ");
            sb.append(new String(raw, i, n, StandardCharsets.UTF_8)).append("\r\n");
            i += n;
            max = 69;
        }
        return sb.toString();
    }

    // ---- DER ----

    private static byte[] der(int tag, byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        if (len < 128) {
            o.write(len);
        } else if (len < 256) {
            o.write(0x81);
            o.write(len);
        } else if (len < 65536) {
            o.write(0x82);
            o.write(len >> 8);
            o.write(len);
        } else {
            o.write(0x83);
            o.write(len >> 16);
            o.write(len >> 8);
            o.write(len);
        }
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static final byte[] NULL = {5, 0};
    private static final byte[] OID_SHA256 = der(6, hex("608648016503040201"));
    private static final byte[] OID_RSA = der(6, hex("2a864886f70d010101"));
    private static final byte[] OID_DATA = der(6, hex("2a864886f70d010701"));
    private static final byte[] OID_SIGNED = der(6, hex("2a864886f70d010702"));

    private static byte[] pkcs7(byte[] certDer, byte[] signature) throws java.security.GeneralSecurityException {
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new java.io.ByteArrayInputStream(certDer));
        byte[] sha256 = der(0x30, OID_SHA256, NULL);
        byte[] rsa = der(0x30, OID_RSA, NULL);
        byte[] issuerAndSerial = der(0x30, cert.getIssuerX500Principal().getEncoded(), der(2, cert.getSerialNumber().toByteArray()));
        byte[] signerInfo = der(0x30, der(2, new byte[] {1}), issuerAndSerial, sha256, rsa, der(4, signature));
        byte[] signedData = der(0x30, der(2, new byte[] {1}), der(0x31, sha256), der(0x30, OID_DATA),
            der(0xa0, certDer), der(0x31, signerInfo));
        return der(0x30, OID_SIGNED, der(0xa0, signedData));
    }
}
