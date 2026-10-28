package io.aitest.runner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.json.jackson3.Jackson3AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class AcpStdioCompatibilityTest {
    @Test void initializePermissionCallbackAndCancelOverIsolatedStdio()throws Exception {
        var mapper=new Jackson3AcpJsonMapper(Jackson3AcpJsonMapper.defaultJsonMapper());
        String python=System.getenv().getOrDefault("WB_PYTHON","/usr/bin/python3");
        var parameters=AgentParameters.builder(python)
            .args(Path.of("src/test/resources/acp_stdio_fixture.py").toAbsolutePath().toString())
            .inheritEnvironment(false).env(Map.of("PYTHONUNBUFFERED","1","PYTHONUTF8","1")).build();
        var transport=new StdioAcpClientTransport(parameters,mapper);
        var callbacks=new AtomicInteger();
        try(var client=AcpClient.sync(transport).requestTimeout(Duration.ofSeconds(3))
            .requestPermissionHandler(request->{
                callbacks.incrementAndGet();assertEquals("synthetic-session",request.sessionId());
                try{return mapper.readValue("{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"once\"}}",AcpSchema.RequestPermissionResponse.class);}
                catch(Exception e){throw new IllegalStateException(e);}
            }).build()){
            assertEquals(1,client.initialize().protocolVersion());
            var session=client.newSession(mapper.readValue("{\"cwd\":\"/tmp\",\"mcpServers\":[]}",AcpSchema.NewSessionRequest.class));
            assertEquals("synthetic-session",session.sessionId());
            var permission=client.sendExtRequest("_fixture/permission",Map.of());
            assertTrue(mapper.writeValueAsString(permission).contains("once"));assertEquals(1,callbacks.get());
            client.cancel(new AcpSchema.CancelNotification("synthetic-session"));
            var state=(Map<?,?>)client.sendExtRequest("_fixture/state",Map.of());
            assertEquals("synthetic-session",state.get("lastCancelled"));
            assertEquals(false,state.get("hasDatabaseSecrets"));assertEquals(0,((Number)state.get("resourceOperations")).intValue());
        }
    }
}
