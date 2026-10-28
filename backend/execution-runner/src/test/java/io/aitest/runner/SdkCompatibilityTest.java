package io.aitest.runner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;
import com.agentclientprotocol.sdk.json.jackson3.Jackson3AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.*;

/** Offline serialization/build probes. No process, model, authentication or network MCP call. */
class SdkCompatibilityTest {
    @Test void acpInitializeAndCancelRoundTripWithJackson3()throws Exception {
        var mapper=new Jackson3AcpJsonMapper(Jackson3AcpJsonMapper.defaultJsonMapper());
        var input=mapper.readValue("""
            {"protocolVersion":1,"clientCapabilities":{"fs":{"readTextFile":false,"writeTextFile":false},"terminal":false},"clientInfo":{"name":"workbench-offline-probe","version":"1"}}
            """,AcpSchema.InitializeRequest.class);
        assertEquals(1,input.protocolVersion());
        assertEquals(input,mapper.readValue(mapper.writeValueAsString(input),AcpSchema.InitializeRequest.class));
        var cancel=mapper.readValue("{\"sessionId\":\"synthetic-session\"}",AcpSchema.CancelNotification.class);
        assertEquals("synthetic-session",cancel.sessionId());
    }
    @Test void acpPermissionOptionsKeepOriginalRequestIdentity()throws Exception {
        var mapper=new Jackson3AcpJsonMapper(Jackson3AcpJsonMapper.defaultJsonMapper());
        var request=mapper.readValue("""
            {"sessionId":"synthetic-session","toolCall":{"toolCallId":"synthetic-tool","title":"fixture-read","kind":"read"},"options":[{"optionId":"once","name":"Allow once","kind":"allow_once"},{"optionId":"deny","name":"Reject","kind":"reject_once"}]}
            """,AcpSchema.RequestPermissionRequest.class);
        assertEquals("synthetic-session",request.sessionId());assertEquals(2,request.options().size());
        String encoded=mapper.writeValueAsString(request);
        assertTrue(encoded.contains("\"optionId\":\"once\""));assertTrue(encoded.contains("allow_once"));
        assertEquals(request,mapper.readValue(encoded,AcpSchema.RequestPermissionRequest.class));
    }
    @Test void mcpInitializeRoundTripUsesSameManagedJacksonMajor()throws Exception {
        var mapper=new JacksonMcpJsonMapper(JsonMapper.builder().build());
        var input=mapper.readValue("""
            {"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"workbench-offline-probe","version":"1"}}
            """,McpSchema.InitializeRequest.class);
        assertEquals("2025-11-25",input.protocolVersion());
        assertEquals(input,mapper.readValue(mapper.writeValueAsString(input),McpSchema.InitializeRequest.class));
    }
    @Test void productionModulesKeepTheDeclaredDependencyDirection()throws Exception {
        for(String module:new String[]{"provider-contract","platform-server","execution-runner"}){
            try(var files=Files.walk(Path.of("../"+module+"/src/main/java"))){
                for(var file:files.filter(p->p.toString().endsWith(".java")).toList()){
                    String source=Files.readString(file);
                    if(module.equals("provider-contract"))assertFalse(source.contains("org.springframework")||source.contains("java.sql")||source.contains("com.agentclientprotocol.sdk")||source.contains("io.modelcontextprotocol"));
                    if(module.equals("platform-server"))assertFalse(source.contains("import io.aitest.runner."));
                    if(module.equals("execution-runner"))assertFalse(source.contains("import io.aitest.workbench."));
                }
            }
        }
    }
}
