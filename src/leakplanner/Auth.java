package leakplanner;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

/** Password hashing (PBKDF2) and signed, expiring bearer tokens (HMAC-SHA256). */
public final class Auth {
    private static final int ITER = 120_000;
    private static final long TOKEN_TTL_MS = 8L * 3600 * 1000;
    private static final byte[] KEY = System.getenv().getOrDefault("SECRET", "dev-secret-change-me").getBytes(StandardCharsets.UTF_8);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Auth() {}

    // ---------- passwords ----------
    private static byte[] pbkdf2(String pw, byte[] salt, int iter) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(pw.toCharArray(), salt, iter, 256)).getEncoded();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public static String hashPassword(String pw) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return "pbkdf2$" + ITER + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(pbkdf2(pw, salt, ITER));
    }

    public static boolean checkPassword(String hash, String pw) {
        try {
            String[] p = hash.split("\\$");
            byte[] want = B64D.decode(p[3]);
            return MessageDigest.isEqual(want, pbkdf2(pw, B64D.decode(p[2]), Integer.parseInt(p[1])));
        } catch (RuntimeException e) { return false; }
    }

    // ---------- tokens ----------
    private static String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public static String issueToken(int userId) {
        String payload = B64.encodeToString((userId + ":" + (System.currentTimeMillis() + TOKEN_TTL_MS)).getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(payload);
    }

    /** Returns the user id, or null if the token is missing, tampered with or expired. */
    public static Integer verifyToken(String token) {
        try {
            int dot = token.indexOf('.');
            String payload = token.substring(0, dot), sig = token.substring(dot + 1);
            if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8))) return null;
            String[] parts = new String(B64D.decode(payload), StandardCharsets.UTF_8).split(":");
            if (System.currentTimeMillis() > Long.parseLong(parts[1])) return null;
            return Integer.parseInt(parts[0]);
        } catch (RuntimeException e) { return null; }
    }
}
