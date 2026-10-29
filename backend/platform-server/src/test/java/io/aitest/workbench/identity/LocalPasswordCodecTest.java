package io.aitest.workbench.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalPasswordCodecTest {
    private final LocalPasswordCodec codec = new LocalPasswordCodec();

    @Test void saltsAndValidatesUnicodePasswordsWithoutTruncation() {
        String password = "可测试的长密码-😀-1234567890";
        String first = codec.hash(password), second = codec.hash(password);
        assertNotEquals(first, second);
        assertTrue(codec.matches(password, first));
        assertFalse(codec.matches(password + "x", first));
        assertFalse(first.contains(password));
    }

    @Test void boundsAndMalformedHashesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> codec.hash("a".repeat(11)));
        assertThrows(IllegalArgumentException.class, () -> codec.hash("a".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> codec.hash("a".repeat(12) + "\uD800"));
        assertThrows(IllegalArgumentException.class, () -> codec.hash("a".repeat(12) + "\n"));
        assertFalse(codec.matches("a".repeat(12), null));
        assertFalse(codec.matches("a".repeat(12), "{unknown}abcd"));
        assertFalse(codec.matches("a".repeat(12), "{pbkdf2-sha256-600000-v1}" + "0".repeat(95) + "g"));
        String boundary = "a".repeat(128);
        assertTrue(codec.matches(boundary, codec.hash(boundary)));
    }
}
