package io.aitest.runner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.aitest.runner.CapabilityGate.Evidence.*;

class CapabilityGateTest {
    @Test void unknownOrMissingEvidenceIsRejected() {
        var result = CapabilityGate.evaluate(new CapabilityGate.Observations(
                VERIFIED, UNVERIFIED, UNSUPPORTED, null, VERIFIED, VERIFIED));
        assertFalse(result.admitted());
        assertEquals(java.util.List.of("networkIsolation", "toolEnforcement", "credentialIsolation"), result.missing());
        assertThrows(UnsupportedOperationException.class, () -> result.missing().clear());
        assertFalse(CapabilityGate.evaluate(null).admitted());
    }

    @Test void allEvidenceMustBeVerified() {
        var result = CapabilityGate.evaluate(new CapabilityGate.Observations(
                VERIFIED, VERIFIED, VERIFIED, VERIFIED, VERIFIED, VERIFIED));
        assertTrue(result.admitted());
        assertTrue(result.missing().isEmpty());
    }
}
