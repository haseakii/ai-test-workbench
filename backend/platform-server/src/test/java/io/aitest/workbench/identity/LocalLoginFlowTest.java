package io.aitest.workbench.identity;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalLoginFlowTest {
    private static final UUID TENANT=UUID.randomUUID();
    private static LocalLoginFlow.Sessions sessions(LocalSessionTokens.Resolution resolution, LocalSessionTokens.Revocation revocation) {
        return new LocalLoginFlow.Sessions() {
            public LocalSessionTokens.Resolution resolve(String token) { return resolution; }
            public LocalSessionTokens.Revocation revoke(String token) { return revocation; }
        };
    }
    @Test void deniedOrUnavailableAuthenticationNeverIssuesSession() {
        var calls=new AtomicInteger();
        var empty=sessions(new LocalSessionTokens.Resolution(LocalSessionTokens.Status.INVALID,null), LocalSessionTokens.Revocation.REVOKED_OR_ABSENT);
        for (var result : new JdbcLocalAuthentication.Result[]{JdbcLocalAuthentication.Result.denied(),JdbcLocalAuthentication.Result.unavailable()}) {
            var flow=new LocalLoginFlow((t,l,s,p)->result, verified->{calls.incrementAndGet();return null;},empty);
            assertEquals(LocalLoginFlow.Status.FAILED,flow.login(TENANT,"alice","127.0.0.1","password").status());
        }
        assertEquals(0,calls.get());
    }
    @Test void verifiedEvidenceStaysInternalAndIssuanceFailureDoesNotReturnToken() {
        var principal=new LocalSessionTokens.Principal(TENANT,UUID.randomUUID(),1);
        var evidence=new JdbcLocalAuthentication.Verified(principal,2);
        var empty=sessions(new LocalSessionTokens.Resolution(LocalSessionTokens.Status.INVALID,null), LocalSessionTokens.Revocation.REVOKED_OR_ABSENT);
        var flow=new LocalLoginFlow((t,l,s,p)->new JdbcLocalAuthentication.Result(JdbcLocalAuthentication.Status.VERIFIED,evidence),
                verified->{assertSame(evidence,verified);throw new IllegalStateException("private");},empty);
        var outcome=flow.login(TENANT,"alice","127.0.0.1","password");
        assertEquals(LocalLoginFlow.Status.FAILED,outcome.status()); assertNull(outcome.session());
    }
    @Test void currentAndLogoutUseAuthoritativeStoreAndFailClosedOnErrors() {
        var principal=new LocalSessionTokens.Principal(TENANT,UUID.randomUUID(),1);
        var current=new LocalSessionTokens.Resolution(LocalSessionTokens.Status.VALID,principal);
        var session=new LocalSessionTokens.Issued("secret",Instant.parse("2030-01-01T00:00:00Z"));
        var flow=new LocalLoginFlow((t,l,s,p)->new JdbcLocalAuthentication.Result(JdbcLocalAuthentication.Status.VERIFIED,new JdbcLocalAuthentication.Verified(principal,1)),
                v->session,sessions(current,LocalSessionTokens.Revocation.REVOKED_OR_ABSENT));
        assertSame(session,flow.login(TENANT,"alice","local","password").session());
        assertSame(principal,flow.current("token").principal());
        assertEquals(LocalSessionTokens.Revocation.REVOKED_OR_ABSENT,flow.logout("token"));
        var unavailable=new LocalLoginFlow((t,l,s,p)->JdbcLocalAuthentication.Result.denied(),v->session,new LocalLoginFlow.Sessions(){
            public LocalSessionTokens.Resolution resolve(String token){throw new IllegalStateException("private");}
            public LocalSessionTokens.Revocation revoke(String token){throw new IllegalStateException("private");}
        });
        assertEquals(LocalSessionTokens.Status.UNAVAILABLE,unavailable.current("token").status());
        assertEquals(LocalSessionTokens.Revocation.UNAVAILABLE,unavailable.logout("token"));
    }
}
