package io.aitest.runner;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticStartCoordinatorTest {
    private final SubmissionJournal.Command command = new SubmissionJournal.Command(new byte[16], new byte[16], new byte[]{1}, new byte[32], 1);
    private final AtomicBoolean allowed = new AtomicBoolean(true), claimed = new AtomicBoolean();
    private final AtomicInteger launches = new AtomicInteger(), authChecks = new AtomicInteger();
    private final SyntheticStartCoordinator.Authority authority = new SyntheticStartCoordinator.Authority() {
        public boolean authenticate(SubmissionJournal.Command ignored) { authChecks.incrementAndGet(); return allowed.get(); }
        public boolean admit(SubmissionJournal.Command ignored) { return allowed.get(); }
    };
    private final SyntheticStartCoordinator.Journal journal = new SyntheticStartCoordinator.Journal() {
        public SubmissionJournal.Result decide(SubmissionJournal.Command ignored, SubmissionJournal.Operation operation) { return SubmissionJournal.Result.ACCEPTED; }
        public SubmissionJournal.Result lookup(SubmissionJournal.Command ignored) { return SubmissionJournal.Result.ACCEPTED; }
    };
    private SyntheticStartCoordinator coordinator(SyntheticStartCoordinator.Launcher launcher) {
        return new SyntheticStartCoordinator(authority, journal,
            ignored -> claimed.compareAndSet(false,true) ? StartOwnership.Result.NEW_INTENT_COMMITTED : StartOwnership.Result.RECONCILE, launcher);
    }
    @Test void restartAndReconcileNeverLaunchAgainAndRecheckIdentity() {
        var first=coordinator(ignored -> launches.incrementAndGet());
        assertEquals(SyntheticStartCoordinator.Result.LAUNCH_ATTEMPTED, first.start(command));
        assertEquals(SyntheticStartCoordinator.Result.RECONCILE, coordinator(ignored -> launches.incrementAndGet()).start(command));
        assertEquals(SyntheticStartCoordinator.Result.RECONCILE, first.reconcile(command));
        allowed.set(false);
        assertEquals(SyntheticStartCoordinator.Result.FORBIDDEN, first.reconcile(command));
        assertEquals(1, launches.get()); assertEquals(5, authChecks.get());
    }
    @Test void lostLaunchReplyStaysUnknownAndNeverReplays() {
        var c=coordinator(ignored -> { launches.incrementAndGet(); throw new Exception("reply lost"); });
        assertEquals(SyntheticStartCoordinator.Result.UNKNOWN, c.start(command));
        assertEquals(SyntheticStartCoordinator.Result.RECONCILE, c.start(command));
        assertEquals(1, launches.get());
    }
    @Test void unknownCommitDoesNotInvokeLauncher() {
        var c=new SyntheticStartCoordinator(authority,journal,ignored -> StartOwnership.Result.UNKNOWN,ignored -> launches.incrementAndGet());
        assertEquals(SyntheticStartCoordinator.Result.UNKNOWN,c.start(command)); assertEquals(0,launches.get());
    }
    @Test void revocationBetweenIntentAndLaunchPreventsExecution() {
        var c=new SyntheticStartCoordinator(authority,journal,ignored -> {allowed.set(false); return StartOwnership.Result.NEW_INTENT_COMMITTED;},ignored -> launches.incrementAndGet());
        assertEquals(SyntheticStartCoordinator.Result.RECONCILE,c.start(command)); assertEquals(0,launches.get());
    }
}
