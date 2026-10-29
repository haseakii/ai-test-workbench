package io.aitest.runner;

import com.agentclientprotocol.sdk.client.AcpSyncClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static io.aitest.runner.CapabilityGate.Evidence.*;
import static org.junit.jupiter.api.Assertions.*;

class AcpStdioHostTest {
    @TempDir Path temp;
    private AcpStdioHost.LaunchSpec fixture() {
        return new AcpStdioHost.LaunchSpec(Path.of(System.getenv().getOrDefault("WB_PYTHON", "/usr/bin/python3")),
            List.of(Path.of("src/test/resources/acp_stdio_fixture.py").toAbsolutePath().toString()), Path.of("/tmp"), Duration.ofSeconds(3));
    }
    @Test void disabledOrUnverifiedHostFailsBeforeStartingProcess() {
        assertThrows(IllegalStateException.class, () -> new AcpStdioHost().open(fixture()));
        var host = new AcpStdioHost(true, ignored -> new CapabilityGate.Observations(VERIFIED, UNVERIFIED, VERIFIED, VERIFIED, VERIFIED, VERIFIED));
        assertThrows(IllegalStateException.class, () -> host.open(fixture()));
        assertThrows(IllegalArgumentException.class, () -> new AcpStdioHost.LaunchSpec(Path.of("relative"), List.of(), Path.of("/tmp"), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new AcpStdioHost.LaunchSpec(Path.of("/bin/x"), List.of(), Path.of("/tmp"), Duration.ofMinutes(1)));
    }
    @Test void approvedSyntheticFixtureUsesStdioDeniesPermissionsAndDoesNotInheritSecrets() throws Exception {
        // Fake proof is solely for this no-resource fixture; it is not an OS isolation observation.
        var host = new AcpStdioHost(true, ignored -> new CapabilityGate.Observations(VERIFIED, VERIFIED, VERIFIED, VERIFIED, VERIFIED, VERIFIED));
        try (var session = host.open(fixture())) {
            assertEquals(1, session.protocolVersion());
            assertEquals("synthetic-session", session.sessionId());
            Field field = AcpStdioHost.Session.class.getDeclaredField("client"); field.setAccessible(true);
            var client = (AcpSyncClient) field.get(session);
            var permission = (Map<?, ?>) client.sendExtRequest("_fixture/permission", Map.of());
            assertTrue(permission.toString().contains("cancelled"));
            session.cancel();
            var state = (Map<?, ?>) client.sendExtRequest("_fixture/state", Map.of());
            assertEquals(false, state.get("hasDatabaseSecrets"));
            assertEquals("synthetic-session", state.get("lastCancelled"));
            assertEquals(0, ((Number) state.get("resourceOperations")).intValue());
        }
    }
    private AcpStdioHost.LaunchSpec lifecycle(String mode, Path marker, Duration timeout) {
        return new AcpStdioHost.LaunchSpec(Path.of(System.getenv().getOrDefault("WB_PYTHON", "/usr/bin/python3")),
            List.of(Path.of("src/test/resources/acp_host_lifecycle_fixture.py").toAbsolutePath().toString(), mode, marker.toString()),
            temp, timeout);
    }
    private AcpStdioHost fakeApprovedFixtureHost() {
        return new AcpStdioHost(true, ignored -> new CapabilityGate.Observations(VERIFIED, VERIFIED, VERIFIED, VERIFIED, VERIFIED, VERIFIED));
    }
    private void assertOwnedFixtureExited(Path marker) throws Exception {
        assertTrue(Files.exists(marker), "only the owned fixture writes this marker");
        long pid = Long.parseLong(Files.readString(marker));
        var process = ProcessHandle.of(pid);
        if (process.isPresent()) process.get().onExit().get(5, TimeUnit.SECONDS);
        assertTrue(ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).orElseThrow().isAlive());
    }
    @Test void unsupportedVersionClosesOwnedProcessBeforeCreatingSession() throws Exception {
        Path marker = temp.resolve("unsupported.pid");
        assertThrows(IllegalStateException.class, () -> fakeApprovedFixtureHost().open(lifecycle("unsupported", marker, Duration.ofSeconds(3))));
        assertFalse(Files.exists(Path.of(marker + ".session")));
        assertOwnedFixtureExited(marker);
    }
    @Test void timeoutClosesOwnedProcessAndMissingAuthorityDoesNotSpawn() throws Exception {
        Path denied = temp.resolve("denied.pid");
        assertThrows(IllegalStateException.class, () -> new AcpStdioHost().open(lifecycle("timeout", denied, Duration.ofMillis(250))));
        assertFalse(Files.exists(denied));
        Path marker = temp.resolve("timeout.pid");
        assertThrows(IllegalStateException.class, () -> fakeApprovedFixtureHost().open(lifecycle("timeout", marker, Duration.ofMillis(250))));
        assertOwnedFixtureExited(marker);
    }
}
