package tv.clean.phone.adb;

/** Standard base64 with padding and no line breaks. java.util.Base64 is API 26; the phone app supports API 21. */
final class Base64Lite {
    private static final char[] ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private Base64Lite() {}

    static byte[] encode(byte[] in) {
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xff;
            int b1 = i + 1 < in.length ? in[i + 1] & 0xff : 0;
            int b2 = i + 2 < in.length ? in[i + 2] & 0xff : 0;
            sb.append(ALPHABET[b0 >> 2]);
            sb.append(ALPHABET[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < in.length ? ALPHABET[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < in.length ? ALPHABET[b2 & 63] : '=');
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
