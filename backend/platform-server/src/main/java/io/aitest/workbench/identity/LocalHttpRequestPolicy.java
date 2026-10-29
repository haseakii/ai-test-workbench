package io.aitest.workbench.identity;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.ResponseCookie;

/** Unregistered request preconditions, not an authentication filter or a CSRF implementation.
 * The adapter must supply the actual TLS and peer facts from the server, not headers.
 * A trusted server-side CSRF implementation and session reauthorization are still required.
 * GET/HEAD handlers must be read-only, and the adapter must apply this to login and logout too. */
final class LocalHttpRequestPolicy {
    static final String COOKIE = "__Host-workbench";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final String origin;

    /** Must be a single explicit HTTPS origin, fixed by trusted server configuration. */
    LocalHttpRequestPolicy(String configuredOrigin) {
        origin = parseOrigin(configuredOrigin);
        if (origin == null) throw new IllegalArgumentException("Invalid configured HTTPS origin");
    }

    record Request(boolean tls, String peerAddress, String method, Map<String, List<String>> headers) {}
    enum Decision { ALLOW_PRECONDITIONS, REJECT }
    interface CsrfVerifier {
        /** Must bind a server-issued proof to the current authenticated or pre-authenticated context. */
        boolean verify(Request request);
    }

    Decision evaluate(Request request, CsrfVerifier csrf) {
        if (request == null || !request.tls() || !numericAddress(request.peerAddress())
                || request.method() == null || request.headers() == null) return Decision.REJECT;
        var method = request.method();
        if (!method.matches("[A-Z]+") || !(method.equals("GET") || method.equals("HEAD")
                || method.equals("POST") || method.equals("PUT") || method.equals("PATCH") || method.equals("DELETE"))) return Decision.REJECT;
        String suppliedOrigin = null;
        int origins = 0;
        for (var entry : request.headers().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) return Decision.REJECT;
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (name.equals("x-test-subject") || name.equals("forwarded") || name.startsWith("x-forwarded-")
                    || name.equals("x-real-ip")) return Decision.REJECT;
            if (name.equals("origin")) {
                origins += entry.getValue().size();
                if (!entry.getValue().isEmpty()) suppliedOrigin = entry.getValue().getFirst();
            }
        }
        if (origins > 1 || (origins == 1 && !origin.equals(parseOrigin(suppliedOrigin)))) return Decision.REJECT;
        boolean mutation = !method.equals("GET") && !method.equals("HEAD");
        if (mutation) {
            if (origins != 1 || csrf == null) return Decision.REJECT;
            try { if (!csrf.verify(request)) return Decision.REJECT; }
            catch (RuntimeException failure) { return Decision.REJECT; }
        }
        return Decision.ALLOW_PRECONDITIONS;
    }

    /** A missing, duplicated or malformed session cookie is not an authenticated session. */
    static String singleSessionCookie(List<String> cookieHeaders) {
        if (cookieHeaders == null) return null;
        String token = null;
        for (String header : cookieHeaders) {
            if (header == null || header.length() > 8192) return null;
            for (String part : header.split(";", -1)) {
                String field = part.trim();
                int separator = field.indexOf('=');
                if (separator < 0) continue;
                if (COOKIE.equals(field.substring(0, separator).trim())) {
                    if (token != null) return null;
                    token = field.substring(separator + 1);
                    if (!TOKEN.matcher(token).matches()) return null;
                }
            }
        }
        return token;
    }

    /** Only usable by an HTTPS response adapter after confirmed session issuance. */
    static String sessionCookie(String token, Duration maxAge) {
        if (token == null || !TOKEN.matcher(token).matches() || maxAge == null
                || maxAge.isNegative() || maxAge.isZero() || maxAge.getSeconds() < 1) throw new IllegalArgumentException("Invalid session cookie");
        return ResponseCookie.from(COOKIE, token).secure(true).httpOnly(true).sameSite("Strict")
                .path("/").maxAge(maxAge).build().toString();
    }

    private static String parseOrigin(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim()) || value.length() > 512) return null;
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1) return null;
            // Require the caller to use exactly the configured serialization; no Host/forwarded derivation.
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            return "https://" + host + (port == -1 || port == 443 ? "" : ":" + port);
        } catch (IllegalArgumentException failure) { return null; }
    }

    private static boolean numericAddress(String value) {
        if (value == null || value.length() > 45) return false;
        if (!value.contains(":")) {
            if (!value.matches("[0-9]+(\\.[0-9]+){3}")) return false;
            for (String octet : value.split("\\.")) {
                if (octet.length() > 3 || (octet.length() > 1 && octet.startsWith("0")) || Integer.parseInt(octet) > 255) return false;
            }
            return true;
        }
        if (!value.matches("[0-9A-Fa-f:.]+")) return false;
        try { return InetAddress.getByName(value) instanceof java.net.Inet6Address; }
        catch (Exception failure) { return false; }
    }
}
