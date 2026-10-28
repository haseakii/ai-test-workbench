package io.aitest.workbench.infrastructure;

import io.aitest.contract.CanonicalJson;
import io.aitest.contract.ContractChecks;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

@Component
public class Store {
    public static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    public final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    public Store(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc,manager,Clock.systemUTC());
    }
    @Autowired public Store(JdbcTemplate jdbc, PlatformTransactionManager manager,Clock clock) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager);
        this.clock=Objects.requireNonNull(clock);
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public <T> T transaction(Supplier<T> work) {
        return tx.execute(status -> { jdbc.queryForList("SELECT id FROM tenants WHERE id=? FOR UPDATE", id(TENANT)); return work.get(); });
    }
    /** Queue claiming only: never acquire business guards while holding a queue row. */
    public <T> T queueTransaction(Supplier<T> work) { return tx.execute(status -> work.get()); }
    public List<Map<String,Object>> rows(String sql,Object... args) { return jdbc.queryForList(sql,args); }
    public Map<String,Object> one(String sql,Object... args) {
        var result=rows(sql,args); if(result.isEmpty()) throw new Problem(404,"NOT_FOUND"); return result.getFirst();
    }
    public int update(String sql,Object...args) { return jdbc.update(sql,args); }
    public static byte[] id(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    public static byte[] id(String value) { try{return id(UUID.fromString(value));}catch(Exception e){throw new Problem(422,"INVALID_UUID");} }
    public static String uuid(Object value) { var b=ByteBuffer.wrap((byte[])value);return new UUID(b.getLong(),b.getLong()).toString(); }
    public static byte[] key(String value) { if(value==null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length>128)throw new Problem(422,"INVALID_KEY"); return value.getBytes(StandardCharsets.UTF_8); }
    public static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
    public static String hex(Object value) { return HexFormat.of().formatHex((byte[])value); }
    public static long number(Object value) {
        try {
            if(!(value instanceof Number))throw new ArithmeticException();
            return ContractChecks.safeInteger(new java.math.BigDecimal(value.toString()).longValueExact());
        }catch(ArithmeticException|IllegalArgumentException e){throw new Problem(422,"SAFE_INTEGER_REQUIRED");}
    }
    public Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    /** User-total and model accounts share one explicit UTC day, independent of business DST. */
    public String quotaPeriod() { return LocalDate.ofInstant(now(),ZoneOffset.UTC).toString(); }
    public static Instant instant(Object value) {
        if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);
        if(value instanceof java.sql.Timestamp t)return t.toInstant();
        throw new IllegalStateException("Unsupported JDBC time");
    }
    public String json(Object value) { return json.writeValueAsString(value); }
    @SuppressWarnings("unchecked") public Map<String,Object> object(Object value) {
        if(value instanceof Map<?,?> m) return (Map<String,Object>)m;
        return json.readValue(value.toString(),Map.class);
    }
    public void audit(UUID actor,String action,UUID object,Map<String,Object> detail) {
        update("INSERT INTO audit_entries VALUES(?,?,?,?,?,?,?)",id(TENANT),id(UUID.randomUUID()),id(actor),action,object==null?null:id(object),json(detail),LocalDateTime.ofInstant(now(),ZoneOffset.UTC));
    }
    public void event(UUID conversation,String type,Map<String,Object> payload) {
        // Join the caller's transaction, or create one for a standalone publisher.
        // A rejected INSERT must roll back the counter as well as the event row.
        transaction(()->{
            var c=one("SELECT event_sequence FROM conversations WHERE tenant_id=? AND id=? FOR UPDATE",id(TENANT),id(conversation));
            long sequence=number(c.get("event_sequence"))+1;
            update("UPDATE conversations SET event_sequence=? WHERE tenant_id=? AND id=?",sequence,id(TENANT),id(conversation));
            update("INSERT INTO platform_events VALUES(?,?,?,?,?,?)",id(TENANT),id(conversation),sequence,type,json(payload),LocalDateTime.ofInstant(now(),ZoneOffset.UTC));
            return null;
        });
    }
    public Map<String,Object> command(UUID actor,String kind,String key,Object body,CommandScope access,Runnable authorize,Supplier<Map<String,Object>> work) {
        return transaction(() -> {
            // The caller supplies only a server-resolved actor. Recheck its current membership
            // before looking at any idempotency record, including the conflict-only path.
            var member=rows("SELECT state FROM memberships WHERE tenant_id=? AND subject_id=?",id(TENANT),id(actor));
            if(member.isEmpty())throw new Problem(401,"UNAUTHENTICATED");
            String state=member.getFirst().get("state").toString();
            if(!state.equals("active")&&!(state.equals("pending")&&kind.equals("membership.request")))throw new Problem(403,"MEMBER_NOT_ACTIVE");
            String hash=CanonicalJson.hash(json(Map.of("method",kind,"body",body)));
            // Never broaden this lookup to a global key/hash: another subject, tenant or
            // command kind must be indistinguishable from a key with no local receipt.
            var prior=rows("SELECT id,request_hash,response_json FROM commands WHERE tenant_id=? AND subject_id=? AND kind=? AND idempotency_key=?",id(TENANT),id(actor),Store.key(kind),Store.key(key));
            if(!prior.isEmpty()) {
                if(!hash.equals(hex(prior.getFirst().get("request_hash"))))throw new Problem(409,"IDEMPOTENCY_CONFLICT");
                authorize.run(); // Only an equal request can replay, and current scope still applies.
                update("UPDATE commands SET access_kind=?,access_id=? WHERE tenant_id=? AND id=? AND access_kind IS NULL",access.kind().name(),access.objectId()==null?null:id(access.objectId()),id(TENANT),prior.getFirst().get("id"));
                return object(prior.getFirst().get("response_json"));
            }
            authorize.run();
            UUID command=UUID.randomUUID();
            var response=new LinkedHashMap<>(work.get());response.put("commandId",command.toString());
            update("INSERT INTO commands(tenant_id,id,subject_id,kind,idempotency_key,request_hash,response_json,access_kind,access_id) VALUES(?,?,?,?,?,?,?,?,?)",id(TENANT),id(command),id(actor),Store.key(kind),Store.key(key),hex(hash),json(response),access.kind().name(),access.objectId()==null?null:id(access.objectId()));
            return response;
        });
    }
    public static final class Problem extends RuntimeException {
        public final int status; public final String code;
        public Problem(int status,String code){super(code);this.status=status;this.code=code;}
    }
}
