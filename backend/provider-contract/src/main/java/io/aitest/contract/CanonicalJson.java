package io.aitest.contract;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.erdtman.jcs.JsonCanonicalizer;

public final class CanonicalJson {
    private CanonicalJson() {}
    public static String canonicalize(String json) {
        try { return new JsonCanonicalizer(json).getEncodedString(); }
        catch (IOException | IllegalArgumentException e) { throw new IllegalArgumentException("Invalid canonical JSON", e); }
    }
    public static String hash(String json) {
        return hashBytes(canonicalize(json).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public static String hashBytes(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
