package io.aitest.runner;

import java.util.ArrayList;
import java.util.List;

/** Fail-closed admission based on verified observations, never advertised capabilities alone. */
public final class CapabilityGate {
    private CapabilityGate() {}

    public enum Evidence { VERIFIED, UNVERIFIED, UNSUPPORTED }

    public record Observations(Evidence processIsolation, Evidence networkIsolation,
                               Evidence toolEnforcement, Evidence credentialIsolation,
                               Evidence durableAdmission, Evidence fencing) {}

    public record Decision(boolean admitted, List<String> missing) {
        public Decision { missing = List.copyOf(missing); }
    }

    public static Decision evaluate(Observations observations) {
        var missing = new ArrayList<String>();
        if (observations == null) return new Decision(false, List.of("observations"));
        require(missing, "processIsolation", observations.processIsolation());
        require(missing, "networkIsolation", observations.networkIsolation());
        require(missing, "toolEnforcement", observations.toolEnforcement());
        require(missing, "credentialIsolation", observations.credentialIsolation());
        require(missing, "durableAdmission", observations.durableAdmission());
        require(missing, "fencing", observations.fencing());
        return new Decision(missing.isEmpty(), missing);
    }

    private static void require(List<String> missing, String name, Evidence evidence) {
        if (evidence != Evidence.VERIFIED) missing.add(name);
    }
}
