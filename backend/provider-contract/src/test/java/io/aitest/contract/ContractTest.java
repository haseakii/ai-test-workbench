package io.aitest.contract;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;

class ContractTest {
    @Test void safeIntegerBounds() {
        assertEquals(9_007_199_254_740_991L, ContractChecks.safeInteger(9_007_199_254_740_991L));
        assertThrows(IllegalArgumentException.class, () -> ContractChecks.safeInteger(9_007_199_254_740_992L));
        assertThrows(IllegalArgumentException.class, () -> ContractChecks.safeInteger(-1));
    }
    @Test void rejectTimePrecisionLoss() {
        ContractChecks.microsecond(Instant.parse("2026-10-26T00:00:00.000001Z"));
        assertThrows(IllegalArgumentException.class, () -> ContractChecks.microsecond(Instant.parse("2026-10-26T00:00:00.000000001Z")));
    }
    @Test void canonicalKeysAndDecimalStrings() {
        String input = "{\"z\":null,\"amount\":\"1.00\",\"title\":\"订单核对\",\"revision\":9007199254740991,\"targets\":[\"O003\",\"O002\"]}";
        String expected = "{\"amount\":\"1.00\",\"revision\":9007199254740991,\"targets\":[\"O003\",\"O002\"],\"title\":\"订单核对\",\"z\":null}";
        assertEquals(expected, CanonicalJson.canonicalize(input));
        assertNotEquals(CanonicalJson.hash(input), CanonicalJson.hash(input.replace("\"1.00\"", "\"1.0\"")));
        assertNotEquals(CanonicalJson.hash(input), CanonicalJson.hash(input.replace("\"O003\",\"O002\"", "\"O002\",\"O003\"")));
    }
    @Test void rejectDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize("{\"a\":1,\"a\":2}"));
    }
}
