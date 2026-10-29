package io.aitest.runner;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Unregistered intent ledger. No result from this class permits execution or proves admission.
 * The legacy writer must be fenced and every legacy owner reconciled before it can be integrated. */
final class ScopedCommandJournal {
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    /** Must verify authenticated caller and current target under locks on this same connection.
     * No production Authority implementation exists yet. */
    interface Authority { boolean lockAndAuthorize(Connection connection, Command command) throws SQLException; }
    enum Status { RECORDED, CONFLICT, FORBIDDEN, UNKNOWN }
    record Result(Status status, UUID intentId, Instant recordedAt) {
        Result {
            if ((status == Status.RECORDED) != (intentId != null && recordedAt != null))
                throw new IllegalArgumentException("Invalid intent outcome");
        }
        static Result state(Status status) { return new Result(status, null, null); }
    }
    record Command(UUID tenant, String provider, UUID nodeRun, String commandType, String key,
                   UUID binding, byte[] specHash, byte[] requestHash, long epoch) {
        Command {
            Objects.requireNonNull(tenant); Objects.requireNonNull(nodeRun); Objects.requireNonNull(binding);
            utf8(provider); utf8(commandType); utf8(key);
            if (specHash == null || specHash.length != 32 || requestHash == null || requestHash.length != 32
                    || epoch < 0 || epoch > MAX_SAFE) throw new IllegalArgumentException("Invalid command");
            specHash = specHash.clone(); requestHash = requestHash.clone();
        }
        @Override public byte[] specHash() { return specHash.clone(); }
        @Override public byte[] requestHash() { return requestHash.clone(); }
    }
    private final DataSource source;
    private final Authority authority;
    private final Clock clock;
    private final java.util.function.Function<byte[],byte[]> digest;
    ScopedCommandJournal(DataSource source, Authority authority, Clock clock) {
        this(source, authority, clock, ScopedCommandJournal::sha256);
    }
    ScopedCommandJournal(DataSource source, Authority authority, Clock clock, java.util.function.Function<byte[],byte[]> digest) {
        this.source=Objects.requireNonNull(source); this.authority=Objects.requireNonNull(authority); this.clock=Objects.requireNonNull(clock);
        this.digest=Objects.requireNonNull(digest);
    }

    /** A successful result means only that a local intent was recorded. Reauthorization happens on every replay.
     * Duplicate-key/deadlock retries happen only before a commit or external action. */
    Result record(Command command) throws SQLException {
        Objects.requireNonNull(command);
        for (int attempt=0; attempt<3; attempt++) {
            boolean committing=false;
            try (Connection c=source.getConnection()) {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false);
                try {
                    if (!authority.lockAndAuthorize(c, command)) { c.rollback(); return Result.state(Status.FORBIDDEN); }
                    var existing=read(c, command);
                    Result result;
                    if (existing != null) result=compare(existing, command);
                    else {
                        var intent=UUID.randomUUID(); Instant at=clock.instant().truncatedTo(ChronoUnit.MICROS);
                        insert(c, command, intent, at); result=new Result(Status.RECORDED, intent, at);
                    }
                    committing=true; c.commit(); return result;
                } catch (SQLException failure) {
                    try { c.rollback(); } catch (SQLException ignored) { return Result.state(Status.UNKNOWN); }
                    if (committing) return Result.state(Status.UNKNOWN);
                    if (("23000".equals(failure.getSQLState()) || "40001".equals(failure.getSQLState())) && attempt<2) continue;
                    throw failure;
                }
            } catch (SQLException failure) {
                if (committing) return Result.state(Status.UNKNOWN);
                throw failure;
            }
        }
        return Result.state(Status.UNKNOWN);
    }

    private record Row(byte[] binding, byte[] specHash, byte[] requestHash, long epoch, byte[] intent, Instant at, byte[] provider, byte[] type, byte[] key) {}
    private static Result compare(Row row, Command command) {
        if (!Arrays.equals(row.provider, utf8(command.provider)) || !Arrays.equals(row.type, utf8(command.commandType)) || !Arrays.equals(row.key, utf8(command.key))
                || !Arrays.equals(row.binding, uuid(command.binding)) || !Arrays.equals(row.specHash, command.specHash)
                || !Arrays.equals(row.requestHash, command.requestHash) || row.epoch != command.epoch)
            return Result.state(Status.CONFLICT);
        return new Result(Status.RECORDED, fromUuid(row.intent), row.at);
    }
    private Row read(Connection c, Command cmd) throws SQLException {
        try (var s=c.prepareStatement("SELECT binding_id,spec_hash,request_hash,ownership_epoch,intent_id,recorded_at,provider_id,command_type,idempotency_key FROM runner_command_intents WHERE tenant_id=? AND provider_digest=? AND node_run_id=? AND command_digest=? AND key_digest=? FOR UPDATE")) {
            namespace(s,cmd);
            try (var r=s.executeQuery()) {
                if (!r.next()) return null;
                var date=r.getTimestamp(6,utc());
                if (date==null) throw new SQLException("Missing intent time");
                return new Row(r.getBytes(1),r.getBytes(2),r.getBytes(3),r.getLong(4),r.getBytes(5),date.toInstant(),r.getBytes(7),r.getBytes(8),r.getBytes(9));
            }
        }
    }
    private void insert(Connection c, Command cmd, UUID intent, Instant at) throws SQLException {
        try (var s=c.prepareStatement("INSERT INTO runner_command_intents (tenant_id,provider_digest,node_run_id,command_digest,key_digest,binding_id,spec_hash,request_hash,ownership_epoch,intent_id,recorded_at,provider_id,command_type,idempotency_key) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            namespace(s,cmd); s.setBytes(6,uuid(cmd.binding)); s.setBytes(7,cmd.specHash); s.setBytes(8,cmd.requestHash);
            s.setLong(9,cmd.epoch); s.setBytes(10,uuid(intent)); s.setTimestamp(11,Timestamp.from(at),utc());
            s.setBytes(12,utf8(cmd.provider)); s.setBytes(13,utf8(cmd.commandType)); s.setBytes(14,utf8(cmd.key));
            if (s.executeUpdate()!=1) throw new SQLException("Invalid intent row count");
        }
    }
    private void namespace(java.sql.PreparedStatement s, Command cmd) throws SQLException {
        s.setBytes(1,uuid(cmd.tenant)); s.setBytes(2,checkedDigest(utf8(cmd.provider))); s.setBytes(3,uuid(cmd.nodeRun));
        s.setBytes(4,checkedDigest(utf8(cmd.commandType))); s.setBytes(5,checkedDigest(utf8(cmd.key)));
    }
    private byte[] checkedDigest(byte[] value) { byte[] result=digest.apply(value); if(result==null || result.length!=32) throw new IllegalStateException("Invalid digest"); return result; }
    private static byte[] sha256(byte[] value) { try { return MessageDigest.getInstance("SHA-256").digest(value); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable",e); } }
    private static byte[] utf8(String value) {
        if (value==null || value.isEmpty()) throw new IllegalArgumentException("Invalid identifier");
        try {
            var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes=new byte[encoded.remaining()]; encoded.get(bytes); return bytes;
        } catch (CharacterCodingException failure) { throw new IllegalArgumentException("Invalid Unicode", failure); }
    }
    private static byte[] uuid(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID fromUuid(byte[] bytes) {
        if (bytes==null || bytes.length!=16) throw new IllegalStateException("Invalid stored intent ID");
        var b=ByteBuffer.wrap(bytes); return new UUID(b.getLong(),b.getLong());
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
}
