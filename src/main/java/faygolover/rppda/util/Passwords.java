package faygolover.rppda.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Пароли КПК: хранится только хэш PBKDF2 с солью — даже ГМ видит лишь «пароль есть». */
public final class Passwords {
    private Passwords() {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 20_000;
    public static final int MAX_LENGTH = 32;

    public static String newSalt() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    public static String hash(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, 256);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(h);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("PBKDF2 недоступен", e);
        }
    }

    public static boolean check(String password, String salt, String expected) {
        byte[] a = hash(password, salt).getBytes();
        byte[] b = expected.getBytes();
        return MessageDigest.isEqual(a, b);
    }
}
