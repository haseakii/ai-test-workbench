package io.aitest.workbench.identity;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static io.aitest.workbench.identity.LocalHttpRequestPolicy.*;

class LocalHttpRequestPolicyTest {
    private final LocalHttpRequestPolicy policy = new LocalHttpRequestPolicy("https://workbench.example:8443");
    private Request request(String method, Map<String,List<String>> headers) {
        return new Request(true, "192.168.1.5", method, headers);
    }

    @Test void rejectsInsecureOrForgedOriginsAndProxyOrSyntheticHeaders() {
        assertThrows(IllegalArgumentException.class, () -> new LocalHttpRequestPolicy("http://workbench.example"));
        assertThrows(IllegalArgumentException.class, () -> new LocalHttpRequestPolicy("https://workbench.example/path"));
        assertEquals(Decision.REJECT, policy.evaluate(new Request(false, "192.168.1.5", "GET", Map.of()), null));
        assertEquals(Decision.REJECT, policy.evaluate(new Request(true, "attacker.example", "GET", Map.of()), null));
        assertEquals(Decision.REJECT, policy.evaluate(request("GET", Map.of("X-Test-Subject", List.of("owner"))), null));
        assertEquals(Decision.REJECT, policy.evaluate(request("GET", Map.of("x-Forwarded-Host", List.of("workbench.example"))), null));
        assertEquals(Decision.REJECT, policy.evaluate(request("POST", Map.of("Origin", List.of("https://attacker.example"))), r -> true));
        assertEquals(Decision.REJECT, policy.evaluate(request("POST", Map.of("Origin", List.of("https://workbench.example:8443", "https://attacker.example"))), r -> true));
        assertEquals(Decision.REJECT, policy.evaluate(request("GET", Map.of("Origin", List.of("null"))), null));
        assertEquals(Decision.ALLOW_PRECONDITIONS, policy.evaluate(request("GET", Map.of()), null));
    }

    @Test void writesRequireServerOriginAndAValidatedServerIssuedCsrfProof() {
        var origin = Map.of("Origin", List.of("https://workbench.example:8443"));
        var called = new AtomicInteger();
        assertEquals(Decision.REJECT, policy.evaluate(request("POST", Map.of()), r -> true));
        assertEquals(Decision.REJECT, policy.evaluate(request("POST", origin), null));
        assertEquals(Decision.REJECT, policy.evaluate(request("POST", origin), r -> false));
        assertEquals(Decision.REJECT, policy.evaluate(request("POST", origin), r -> { throw new IllegalStateException(); }));
        assertEquals(Decision.ALLOW_PRECONDITIONS, policy.evaluate(request("PATCH", origin), r -> { called.incrementAndGet(); return true; }));
        assertEquals(1, called.get());
        assertEquals(Decision.REJECT, policy.evaluate(request("OPTIONS", origin), r -> true));
    }

    @Test void onlyOneWellFormedSessionCookieCanBeUsed() {
        String token = "A".repeat(43);
        assertEquals(token, singleSessionCookie(List.of("other=1; " + COOKIE + "=" + token)));
        assertNull(singleSessionCookie(List.of(COOKIE + "=" + token, COOKIE + "=" + token)));
        assertNull(singleSessionCookie(List.of(COOKIE + "=invalid")));
        assertNull(singleSessionCookie(List.of("other=" + token)));
        String cookie = sessionCookie(token, Duration.ofMinutes(10));
        assertTrue(cookie.contains("Secure")); assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict")); assertTrue(cookie.contains("Path=/"));
        assertFalse(cookie.contains("Domain="));
        assertThrows(IllegalArgumentException.class, () -> sessionCookie("invalid", Duration.ofMinutes(1)));
    }
}
