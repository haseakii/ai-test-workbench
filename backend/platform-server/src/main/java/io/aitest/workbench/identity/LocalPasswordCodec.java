package io.aitest.workbench.identity;

import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import static org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256;

/** Internal password primitive only; not an authentication service or Spring bean. */
final class LocalPasswordCodec {
    private static final String PREFIX = "{pbkdf2-sha256-600000-v1}";
    private final Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder("", 16, 600_000, PBKDF2WithHmacSHA256);

    String hash(String password) {
        if (!valid(password)) throw new IllegalArgumentException("Invalid password length or characters");
        return PREFIX + encoder.encode(password);
    }

    boolean matches(String password, String encoded) {
        if (!valid(password) || encoded == null || !encoded.startsWith(PREFIX)) return false;
        String hash = encoded.substring(PREFIX.length());
        // Version 1 is exactly a 16-byte random salt followed by a 32-byte derived key.
        if (!hash.matches("[0-9a-f]{96}")) return false;
        try { return encoder.matches(password, hash); }
        catch (IllegalArgumentException failure) { return false; }
    }

    private static boolean valid(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 12
                || password.codePointCount(0, password.length()) > 128) return false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= password.length() || !Character.isLowSurrogate(password.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c) || Character.isISOControl(c)) return false;
        }
        return true;
    }
}
