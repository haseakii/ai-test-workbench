package io.aitest.workbench.resource;

import io.aitest.contract.CanonicalJson;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticPermitGatewayTest {
    private final String parameters = "{\"b\":2,\"a\":1}";
    private final OperationPermitPolicy.Scope scope = new OperationPermitPolicy.Scope("tenant","user","binding",1,"env","operation","a".repeat(64),"fixture","csv",CanonicalJson.hash(parameters),1);
    private final OperationPermitPolicy.Permit permit = new OperationPermitPolicy.Permit(scope,Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2027-01-01T00:00:00Z"));

    @Test void exactParametersAreCanonicalizedAndOnlyCommittedConsumptionRunsFixture() {
        var calls=new AtomicInteger();
        var gateway=new SyntheticPermitGateway((user,ref)->permit,p->JdbcPermitConsumption.Outcome.CONSUMED,(s,json)->{calls.incrementAndGet();assertEquals("{\"a\":1,\"b\":2}",json);return "synthetic";});
        assertEquals(new SyntheticPermitGateway.Result(SyntheticPermitGateway.Status.COMPLETED,"synthetic"),gateway.execute("user","issued",parameters));
        assertEquals(SyntheticPermitGateway.Status.REJECTED,gateway.execute("user","issued","{\"a\":2,\"b\":2}").status());
        assertEquals(SyntheticPermitGateway.Status.REJECTED,gateway.execute("other","issued",parameters).status());
        assertEquals(1,calls.get());
    }

    @Test void unknownOrRevokedConsumptionAndReplayNeverRunFixture() {
        var calls=new AtomicInteger();var consumption=new AtomicInteger();
        var gateway=new SyntheticPermitGateway((user,ref)->permit,p->consumption.getAndIncrement()==0 ? JdbcPermitConsumption.Outcome.UNKNOWN : JdbcPermitConsumption.Outcome.REJECTED,(s,json)->{calls.incrementAndGet();return "never";});
        assertEquals(SyntheticPermitGateway.Status.UNKNOWN,gateway.execute("user","issued",parameters).status());
        assertEquals(SyntheticPermitGateway.Status.REJECTED,gateway.execute("user","issued",parameters).status());
        assertEquals(0,calls.get());
    }

    @Test void fixtureFailureIsUnknownAndDoesNotRetry() {
        var calls=new AtomicInteger();var claims=new AtomicInteger();
        var gateway=new SyntheticPermitGateway((user,ref)->permit,p->claims.getAndIncrement()==0 ? JdbcPermitConsumption.Outcome.CONSUMED : JdbcPermitConsumption.Outcome.UNKNOWN,(s,json)->{calls.incrementAndGet();throw new Exception("lost result");});
        assertEquals(SyntheticPermitGateway.Status.UNKNOWN,gateway.execute("user","issued",parameters).status());
        assertEquals(SyntheticPermitGateway.Status.UNKNOWN,gateway.execute("user","issued",parameters).status());
        assertEquals(1,calls.get());
    }
}
