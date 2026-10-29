package io.aitest.workbench.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Internal coordination only: never register as an HTTP endpoint without TLS, CSRF and trusted source handling. */
final class LocalLoginFlow {
    interface Authentication { JdbcLocalAuthentication.Result verify(UUID tenant, String login, String source, String password); }
    interface Issuer { LocalSessionTokens.Issued issue(JdbcLocalAuthentication.Verified verified); }
    interface Sessions {
        LocalSessionTokens.Resolution resolve(String token);
        LocalSessionTokens.Revocation revoke(String token);
    }
    enum Status { SUCCESS, FAILED }
    record Login(Status status, LocalSessionTokens.Issued session) {
        Login { if ((status == Status.SUCCESS) != (session != null)) throw new IllegalArgumentException(); }
    }
    private final Authentication authentication;
    private final Issuer issuer;
    private final Sessions sessions;

    static LocalLoginFlow using(DataSource source, Clock clock, Duration lifetime) {
        var authentication = new JdbcLocalAuthentication(source, clock);
        var issuer = new JdbcVerifiedSessionIssuer(source, clock, lifetime);
        var sessions = new LocalSessionTokens(new JdbcLocalSessionStore(source), clock, lifetime);
        return new LocalLoginFlow(authentication::verify, issuer::issue, new Sessions() {
            public LocalSessionTokens.Resolution resolve(String token) { return sessions.resolve(token); }
            public LocalSessionTokens.Revocation revoke(String token) { return sessions.revoke(token); }
        });
    }

    LocalLoginFlow(Authentication authentication, Issuer issuer, Sessions sessions) {
        this.authentication = Objects.requireNonNull(authentication);
        this.issuer = Objects.requireNonNull(issuer);
        this.sessions = Objects.requireNonNull(sessions);
    }

    /** source must come from a trusted server network boundary, not a client header. */
    Login login(UUID tenant, String login, String source, String password) {
        try {
            var result = authentication.verify(tenant, login, source, password);
            if (result == null || result.status() != JdbcLocalAuthentication.Status.VERIFIED || result.verified() == null)
                return new Login(Status.FAILED, null);
            var issued = issuer.issue(result.verified());
            return issued == null ? new Login(Status.FAILED, null) : new Login(Status.SUCCESS, issued);
        } catch (RuntimeException failure) { return new Login(Status.FAILED, null); }
    }

    LocalSessionTokens.Resolution current(String token) {
        try {
            var result = sessions.resolve(token);
            return result == null ? new LocalSessionTokens.Resolution(LocalSessionTokens.Status.UNAVAILABLE, null) : result;
        } catch (RuntimeException failure) { return new LocalSessionTokens.Resolution(LocalSessionTokens.Status.UNAVAILABLE, null); }
    }

    LocalSessionTokens.Revocation logout(String token) {
        try {
            var result = sessions.revoke(token);
            return result == null ? LocalSessionTokens.Revocation.UNAVAILABLE : result;
        } catch (RuntimeException failure) { return LocalSessionTokens.Revocation.UNAVAILABLE; }
    }
}
