package io.aitest.workbench.identity;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalSessionTokensTest {
    private final LocalSessionTokens.Principal principal = new LocalSessionTokens.Principal(UUID.randomUUID(), UUID.randomUUID(), 3);

    @Test void onlyHashIsStoredAndExpiryBoundaryIsExact() throws Exception {
        var store=new MemoryStore(); var clock=new MutableClock();
        var sessions=new LocalSessionTokens(store,clock,Duration.ofSeconds(60));
        var issued=sessions.issue(principal); var second=sessions.issue(principal);
        assertNotEquals(issued.value(),second.value());
        assertFalse(issued.toString().contains(issued.value()));
        assertEquals(32,store.lastHash.length);
        assertFalse(Arrays.equals(Base64.getUrlDecoder().decode(issued.value()),store.lastHash));
        assertEquals(principal,sessions.resolve(issued.value()).principal());
        clock.time=issued.expiresAt().minusNanos(1_000);
        assertEquals(LocalSessionTokens.Status.VALID,sessions.resolve(issued.value()).status());
        clock.time=issued.expiresAt();
        assertEquals(LocalSessionTokens.Status.INVALID,sessions.resolve(issued.value()).status());
    }

    @Test void revocationMalformedInputAndUnavailableStoreFailClosed() throws Exception {
        var store=new MemoryStore(); var clock=new MutableClock();
        var sessions=new LocalSessionTokens(store,clock,Duration.ofMinutes(5));
        var issued=sessions.issue(principal);
        assertEquals(LocalSessionTokens.Revocation.REVOKED_OR_ABSENT,sessions.revoke(issued.value()));
        assertEquals(LocalSessionTokens.Status.INVALID,sessions.resolve(issued.value()).status());
        assertEquals(LocalSessionTokens.Revocation.REVOKED_OR_ABSENT,sessions.revoke(issued.value()));
        assertEquals(LocalSessionTokens.Status.INVALID,sessions.resolve("a".repeat(42)+"b").status());
        assertEquals(LocalSessionTokens.Status.INVALID,sessions.resolve("invalid").status());
        store.fail=true;
        assertEquals(LocalSessionTokens.Status.UNAVAILABLE,sessions.resolve(issued.value()).status());
        assertEquals(LocalSessionTokens.Revocation.UNAVAILABLE,sessions.revoke(issued.value()));
        assertThrows(Exception.class,()->sessions.issue(principal));
        assertThrows(IllegalArgumentException.class,()->new LocalSessionTokens(store,clock,Duration.ofNanos(1)));
    }

    /** Test double; not evidence of transaction durability or current-membership locking. */
    private static final class MemoryStore implements LocalSessionTokens.Store {
        private final Map<String,LocalSessionTokens.Stored> rows=new HashMap<>();
        private boolean fail; private byte[] lastHash;
        private String key(byte[] hash){return HexFormat.of().formatHex(hash);}
        public void create(byte[] hash,LocalSessionTokens.Principal p,Instant expires) throws Exception {
            if(fail)throw new Exception(); lastHash=hash.clone();
            if(rows.putIfAbsent(key(hash),new LocalSessionTokens.Stored(p,expires,false))!=null)throw new Exception();
        }
        public LocalSessionTokens.Stored resolve(byte[] hash,Instant now) throws Exception {if(fail)throw new Exception();return rows.get(key(hash));}
        public void revoke(byte[] hash) throws Exception {
            if(fail)throw new Exception(); var s=rows.get(key(hash));
            if(s!=null)rows.put(key(hash),new LocalSessionTokens.Stored(s.principal(),s.expiresAt(),true));
        }
    }
    private static final class MutableClock extends Clock {
        private Instant time=Instant.parse("2026-10-28T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){if(!ZoneOffset.UTC.equals(zone))throw new IllegalArgumentException();return this;}
        public Instant instant(){return time;}
    }
}
