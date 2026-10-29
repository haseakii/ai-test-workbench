package io.aitest.runner;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.json.jackson3.Jackson3AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic stdio proof only: memory doubles do not prove MySQL durability or OS isolation. */
class SyntheticAcpHostTest {
    @Test void committedSyntheticIntentPrecedesStdioAndReplayNeverStartsAnotherFixture() {
        var accepted=new AtomicBoolean(); var intent=new AtomicBoolean(); var starts=new AtomicInteger();
        var command=new SubmissionJournal.Command(new byte[16],new byte[16],new byte[]{42},new byte[32],1);
        var authority=new SyntheticStartCoordinator.Authority() {
            public boolean authenticate(SubmissionJournal.Command c){return true;}
            public boolean admit(SubmissionJournal.Command c){return true;}
        };
        var journal=new SyntheticStartCoordinator.Journal() {
            public SubmissionJournal.Result decide(SubmissionJournal.Command c,SubmissionJournal.Operation op){
                assertEquals(SubmissionJournal.Operation.ACCEPT,op); accepted.set(true); return SubmissionJournal.Result.ACCEPTED;
            }
            public SubmissionJournal.Result lookup(SubmissionJournal.Command c){return accepted.get()?SubmissionJournal.Result.ACCEPTED:SubmissionJournal.Result.UNKNOWN;}
        };
        var coordinator=new SyntheticStartCoordinator(authority,journal,c->{
            assertTrue(accepted.get());
            return intent.compareAndSet(false,true)?StartOwnership.Result.NEW_INTENT_COMMITTED:StartOwnership.Result.RECONCILE;
        },c->{
            assertTrue(accepted.get()); assertTrue(intent.get()); starts.incrementAndGet();
            var mapper=new Jackson3AcpJsonMapper(Jackson3AcpJsonMapper.defaultJsonMapper());
            var parameters=AgentParameters.builder(System.getenv().getOrDefault("WB_PYTHON","/usr/bin/python3"))
                .args(Path.of("src/test/resources/acp_stdio_fixture.py").toAbsolutePath().toString())
                .inheritEnvironment(false).env(Map.of("PYTHONUNBUFFERED","1","PYTHONUTF8","1")).build();
            var transport=new StdioAcpClientTransport(parameters,mapper);
            try(var client=AcpClient.sync(transport).requestTimeout(Duration.ofSeconds(3))
                .requestPermissionHandler(request->{
                    try{return mapper.readValue("{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"deny\"}}",AcpSchema.RequestPermissionResponse.class);}
                    catch(Exception e){throw new IllegalStateException(e);}
                }).build()) {
                assertEquals(1,client.initialize().protocolVersion());
                var session=client.newSession(mapper.readValue("{\"cwd\":\"/tmp\",\"mcpServers\":[]}",AcpSchema.NewSessionRequest.class));
                assertEquals("synthetic-session",session.sessionId());
                assertTrue(mapper.writeValueAsString(client.sendExtRequest("_fixture/permission",Map.of())).contains("deny"));
                client.cancel(new AcpSchema.CancelNotification(session.sessionId()));
                var state=(Map<?,?>)client.sendExtRequest("_fixture/state",Map.of());
                assertEquals("synthetic-session",state.get("lastCancelled"));
                assertEquals(false,state.get("hasDatabaseSecrets"));
                assertEquals(0,((Number)state.get("resourceOperations")).intValue());
            }
        });
        assertEquals(SyntheticStartCoordinator.Result.LAUNCH_ATTEMPTED,coordinator.start(command));
        assertEquals(SyntheticStartCoordinator.Result.RECONCILE,coordinator.start(command));
        assertEquals(SyntheticStartCoordinator.Result.RECONCILE,coordinator.reconcile(command));
        assertEquals(1,starts.get());
    }
}
