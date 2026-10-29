package io.aitest.runner;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.json.jackson3.Jackson3AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Internal transport only. No component registers this host, and the default is disabled.
 * A trusted server-side policy must verify the exact launch and isolation; this transport
 * does not establish process, network, resource or credential isolation by itself. */
final class AcpStdioHost {
    record LaunchSpec(Path executable, List<String> arguments, Path cwd, Duration timeout) {
        LaunchSpec {
            Objects.requireNonNull(executable); Objects.requireNonNull(arguments);
            Objects.requireNonNull(cwd); Objects.requireNonNull(timeout);
            arguments = List.copyOf(arguments);
            if (!executable.isAbsolute() || !cwd.isAbsolute() || arguments.size() > 32
                    || timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0
                    || arguments.stream().anyMatch(a -> a.length() > 4096 || a.chars().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Invalid ACP launch specification");
        }
    }
    interface LaunchPolicy {
        /** The caller must bind these observations to the exact binary, environment and current authorization. */
        CapabilityGate.Observations verify(LaunchSpec spec);
    }
    private final boolean enabled;
    private final LaunchPolicy policy;
    AcpStdioHost() { this.enabled = false; this.policy = null; }
    AcpStdioHost(boolean enabled, LaunchPolicy policy) { this.enabled = enabled; this.policy = policy; }

    Session open(LaunchSpec spec) throws Exception {
        Objects.requireNonNull(spec);
        if (!enabled || policy == null || !CapabilityGate.evaluate(policy.verify(spec)).admitted())
            throw new IllegalStateException("ACP transport unavailable: isolation not verified");
        var mapper = new Jackson3AcpJsonMapper(Jackson3AcpJsonMapper.defaultJsonMapper());
        var parameters = AgentParameters.builder(spec.executable().toString()).args(spec.arguments())
            .inheritEnvironment(false).env(Map.of("PYTHONUNBUFFERED", "1", "PYTHONUTF8", "1")).build();
        var transport = new StdioAcpClientTransport(parameters, mapper);
        // Agent stderr is untrusted and may contain secrets; never forward it to application logs.
        transport.setStdErrorHandler(ignored -> {});
        transport.setExceptionHandler(ignored -> {});
        AcpSyncClient client = AcpClient.sync(transport).requestTimeout(spec.timeout())
            .requestPermissionHandler(request -> {
                try { return mapper.readValue("{\"outcome\":{\"outcome\":\"cancelled\"}}", AcpSchema.RequestPermissionResponse.class); }
                catch (Exception failure) { throw new IllegalStateException("Cannot deny ACP permission"); }
            }).build();
        try {
            var init = client.initialize();
            if (init.protocolVersion() != 1) throw new IllegalStateException("Unsupported ACP protocol version");
            var input = mapper.readValue(mapper.writeValueAsString(Map.of("cwd", spec.cwd().toString(), "mcpServers", List.of())), AcpSchema.NewSessionRequest.class);
            var session = client.newSession(input);
            if (session.sessionId() == null || session.sessionId().isBlank()) throw new IllegalStateException("Invalid ACP session");
            return new Session(client, session.sessionId(), init.protocolVersion());
        } catch (Exception failure) {
            client.close();
            // SDK errors can contain process output, command details, or other sensitive data.
            throw new IllegalStateException("ACP initialization failed");
        }
    }

    static final class Session implements AutoCloseable {
        private final AcpSyncClient client;
        private final String sessionId;
        private final int protocolVersion;
        Session(AcpSyncClient client, String sessionId, int protocolVersion) {
            this.client = client; this.sessionId = sessionId; this.protocolVersion = protocolVersion;
        }
        String sessionId() { return sessionId; }
        int protocolVersion() { return protocolVersion; }
        void cancel() { client.cancel(new AcpSchema.CancelNotification(sessionId)); }
        @Override public void close() { client.close(); }
    }
}
