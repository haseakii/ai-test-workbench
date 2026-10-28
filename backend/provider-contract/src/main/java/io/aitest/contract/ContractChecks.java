package io.aitest.contract;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class ContractChecks {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private ContractChecks() {}
    public static long safeInteger(long value) {
        if (value < 0 || value > MAX_SAFE_INTEGER) throw new IllegalArgumentException("Unsafe integer");
        return value;
    }
    public static Instant microsecond(Instant value) {
        if (!value.equals(value.truncatedTo(ChronoUnit.MICROS))) throw new IllegalArgumentException("Time loses microsecond precision");
        return value;
    }
}
