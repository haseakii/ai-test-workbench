package io.aitest.runner;

import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/** Test-only coordination boundary. This is not an ACP host or a resource authorization service.
 * Reconciliation never launches. A committed intent is never reset, even when a launch fails. */
final class SyntheticStartCoordinator {
    interface Authority {
        /** Recheck the current caller and the frozen command on every request. */
        boolean authenticate(SubmissionJournal.Command command);
        /** Recheck immediately before each launch attempt. */
        boolean admit(SubmissionJournal.Command command);
    }
    interface Journal {
        SubmissionJournal.Result decide(SubmissionJournal.Command command, SubmissionJournal.Operation operation) throws SQLException;
        SubmissionJournal.Result lookup(SubmissionJournal.Command command) throws SQLException;
    }
    interface Ownership { StartOwnership.Result claim(SubmissionJournal.Command command) throws SQLException; }
    interface Launcher { void launchOnce(SubmissionJournal.Command command) throws Exception; }

    enum Result { LAUNCH_ATTEMPTED, FORBIDDEN, CLOSED, CONFLICT, STALE_EPOCH, RECONCILE, UNKNOWN }
    private final Authority authority;
    private final Journal journal;
    private final Ownership ownership;
    private final Launcher launcher;

    /** Explicit synthetic fixture wiring; callers must supply a trusted synthetic authority. */
    static SyntheticStartCoordinator using(DataSource source, Authority authority, Launcher launcher) {
        var durableJournal = new SubmissionJournal(source);
        var durableOwnership = new StartOwnership(source);
        return new SyntheticStartCoordinator(authority, new Journal() {
            @Override public SubmissionJournal.Result decide(SubmissionJournal.Command command, SubmissionJournal.Operation operation) throws SQLException {
                return durableJournal.decide(command, operation);
            }
            @Override public SubmissionJournal.Result lookup(SubmissionJournal.Command command) throws SQLException {
                return durableJournal.lookup(command);
            }
        }, durableOwnership::claim, launcher);
    }

    SyntheticStartCoordinator(Authority authority, Journal journal, Ownership ownership, Launcher launcher) {
        this.authority = Objects.requireNonNull(authority);
        this.journal = Objects.requireNonNull(journal);
        this.ownership = Objects.requireNonNull(ownership);
        this.launcher = Objects.requireNonNull(launcher);
    }

    Result start(SubmissionJournal.Command command) {
        Objects.requireNonNull(command);
        try {
            if (!authority.authenticate(command) || !authority.admit(command)) return Result.FORBIDDEN;
            var accepted = journal.decide(command, SubmissionJournal.Operation.ACCEPT);
            if (accepted != SubmissionJournal.Result.ACCEPTED) return map(accepted);
            var claim = ownership.claim(command);
            if (claim == StartOwnership.Result.UNKNOWN) return Result.UNKNOWN;
            if (claim != StartOwnership.Result.NEW_INTENT_COMMITTED) return Result.RECONCILE;
            // This is a synthetic fixture boundary: real execution additionally needs atomic
            // authority/fencing enforcement at the receiving resource, not just this recheck.
            if (!authority.authenticate(command) || !authority.admit(command)) return Result.RECONCILE;
            launcher.launchOnce(command);
            return Result.LAUNCH_ATTEMPTED;
        } catch (Exception failure) {
            // The caller cannot infer whether a side effect occurred; never retry automatically.
            return Result.UNKNOWN;
        }
    }

    Result reconcile(SubmissionJournal.Command command) {
        Objects.requireNonNull(command);
        try {
            if (!authority.authenticate(command)) return Result.FORBIDDEN;
            var found = journal.lookup(command);
            return found == SubmissionJournal.Result.ACCEPTED ? Result.RECONCILE : map(found);
        } catch (Exception failure) { return Result.UNKNOWN; }
    }

    private static Result map(SubmissionJournal.Result result) {
        if (result == null) return Result.UNKNOWN;
        return switch (result) {
            case CLOSED -> Result.CLOSED;
            case CONFLICT -> Result.CONFLICT;
            case STALE_EPOCH -> Result.STALE_EPOCH;
            case ACCEPTED -> Result.RECONCILE;
            case UNKNOWN -> Result.UNKNOWN;
        };
    }
}
