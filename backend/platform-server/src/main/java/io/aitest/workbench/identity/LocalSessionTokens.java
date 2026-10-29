package io.aitest.workbench.identity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Internal token primitive, not a login service or a registered authentication filter. */
final class LocalSessionTokens {
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    record Principal(UUID tenant, UUID subject, long authorizationRevision) {
        Principal {
            Objects.requireNonNull(tenant); Objects.requireNonNull(subject);
            if (authorizationRevision < 0 || authorizationRevision > MAX_REVISION) throw new IllegalArgumentException("Invalid revision");
        }
    }
    record Stored(Principal principal, Instant expiresAt, boolean revoked) {
        Stored { Objects.requireNonNull(principal); Objects.requireNonNull(expiresAt); }
    }
    record Issued(String value, Instant expiresAt) {
        @Override public String toString() { return "Issued[redacted]"; }
    }
    enum Status { VALID, INVALID, UNAVAILABLE }
    record Resolution(Status status, Principal principal) {
        Resolution { if ((status == Status.VALID) != (principal != null)) throw new IllegalArgumentException(); }
    }
    enum Revocation { REVOKED_OR_ABSENT, UNAVAILABLE }

    /** Implementations must use durable transactions. create() must enforce unique hashes and
     * lock/recheck the current active subject and revision before committing. resolve() must
     * atomically read the session and current membership; stale/inactive returns null. revoke()
     * must durably and idempotently revoke before returning. Never store raw token bytes. */
    interface Store {
        void create(byte[] tokenHash, Principal principal, Instant expiresAt) throws Exception;
        Stored resolve(byte[] tokenHash, Instant now) throws Exception;
        void revoke(byte[] tokenHash) throws Exception;
    }

    private final Store store;
    private final Clock clock;
    private final Duration lifetime;
    private final SecureRandom random;

    LocalSessionTokens(Store store, Clock clock, Duration lifetime) {
        this(store, clock, lifetime, new SecureRandom());
    }
    LocalSessionTokens(Store store, Clock clock, Duration lifetime, SecureRandom random) {
        this.store=Objects.requireNonNull(store); this.clock=Objects.requireNonNull(clock);
        this.lifetime=Objects.requireNonNull(lifetime); this.random=Objects.requireNonNull(random);
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.getNano() % 1_000 != 0)
            throw new IllegalArgumentException("Invalid session lifetime");
    }

    /** Return a token only after store confirms durable commit. Failures leave outcome unknown. */
    Issued issue(Principal principal) {
        Objects.requireNonNull(principal);
        Instant expiresAt = now().plus(lifetime);
        byte[] secret = new byte[32]; random.nextBytes(secret);
        String token = ENCODER.encodeToString(secret);
        try { store.create(digest(secret), principal, expiresAt); }
        catch (Exception failure) { throw new IllegalStateException("Session issuance unavailable"); }
        finally { java.util.Arrays.fill(secret, (byte)0); }
        return new Issued(token, expiresAt);
    }

    Resolution resolve(String token) {
        byte[] hash = tokenHash(token);
        if (hash == null) return new Resolution(Status.INVALID, null);
        try {
            Instant now=now(); Stored session=store.resolve(hash, now);
            if (session == null || session.revoked() || !now.isBefore(session.expiresAt()))
                return new Resolution(Status.INVALID, null);
            return new Resolution(Status.VALID, session.principal());
        } catch (Exception failure) { return new Resolution(Status.UNAVAILABLE, null); }
    }

    Revocation revoke(String token) {
        byte[] hash=tokenHash(token);
        if (hash == null) return Revocation.REVOKED_OR_ABSENT;
        try { store.revoke(hash); return Revocation.REVOKED_OR_ABSENT; }
        catch (Exception failure) { return Revocation.UNAVAILABLE; }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static byte[] tokenHash(String token) {
        if (token == null || token.length() != 43 || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        try {
            byte[] bytes=DECODER.decode(token);
            if (bytes.length != 32 || !ENCODER.encodeToString(bytes).equals(token)) return null;
            try { return digest(bytes); } finally { java.util.Arrays.fill(bytes, (byte)0); }
        } catch (IllegalArgumentException failure) { return null; }
    }
    private static byte[] digest(byte[] token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
