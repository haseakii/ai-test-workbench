package io.aitest.workbench.handover;

import io.aitest.contract.CanonicalJson;
import io.aitest.contract.ContractChecks;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.function.Function;

/**
 * An immutable projection of already authorized facts, not a database reader or an
 * authority to export them. The calling service must reauthorize every source,
 * check the original approved plan hash and record an audit before serialization.
 * No body text, attachment bytes, credentials, local paths or provider URLs belong
 * in this projection. Missing legacy/provider facts stay null or unknown.
 *
 * The package hash covers this projection. It is distinct from operationHash and
 * context.sha256, which identify their complete original immutable payloads.
 */
public record HandoverPackage(
        String schemaVersion, String generatedAt, Conversation conversation,
        Context context, Operation operation, List<Execution> executions,
        List<Input> inputs, List<Artifact> artifacts,
        List<Reservation> reservations, List<AccountBalance> accountBalances,
        List<Verification> verifications, List<Unresolved> unresolved,
        Responsibility responsibility) {
    public static final String VERSION="fixed-csv-handover-v1";
    private static final JsonMapper JSON=JsonMapper.builder().build();

    public HandoverPackage {
        if(!VERSION.equals(schemaVersion))throw new IllegalArgumentException("Unsupported handover version");
        generatedAt=time(generatedAt);Objects.requireNonNull(conversation);
        executions=sorted(executions,e->e.bindingId().toString());
        inputs=sorted(inputs,e->e.attachmentId().toString());
        artifacts=sorted(artifacts,e->e.artifactId().toString());
        reservations=sorted(reservations,e->e.bindingId().toString());
        accountBalances=sorted(accountBalances,e->e.subjectId()+":"+e.period()+":"+e.accountKey());
        verifications=sorted(verifications,e->e.artifactId()+":"+e.resultRevision());
        unresolved=sorted(unresolved,e->e.code()+":"+Objects.toString(e.bindingId(),""));
        Objects.requireNonNull(responsibility);
        var bindings=new HashSet<UUID>();executions.forEach(e->bindings.add(e.bindingId()));
        if(reservations.stream().anyMatch(r->!bindings.contains(r.bindingId())))
            throw new IllegalArgumentException("Reservation without an exported binding");
        if(artifacts.stream().anyMatch(a->!bindings.contains(a.bindingId())))
            throw new IllegalArgumentException("Artifact without an exported binding");
        var artifactIds=new HashSet<UUID>();artifacts.forEach(a->artifactIds.add(a.artifactId()));
        if(verifications.stream().anyMatch(v->!artifactIds.contains(v.artifactId())))
            throw new IllegalArgumentException("Verification without an exported artifact");
    }

    /** Canonical JSON is generated from fields only; it never fetches referenced content. */
    public String canonicalJson(){return CanonicalJson.canonicalize(JSON.writeValueAsString(this));}
    public String sha256(){return CanonicalJson.hash(canonicalJson());}

    public record Conversation(UUID conversationId,UUID environmentId,long inputRevision,
                               long revision,long eventWatermark,String state) {
        public Conversation {
            Objects.requireNonNull(conversationId);Objects.requireNonNull(environmentId);
            safe(inputRevision);safe(revision);safe(eventWatermark);
            state=choice(state,"open","awaiting_user","awaiting_verification","resolved","handoff","archived_unconfirmed");
        }
    }
    public enum ContextState { active, stale, unknown }
    public record Context(UUID reference,String sha256,String purpose,Long inputRevision,
                          ContextState state,String invalidationCode) {
        public Context {
            Objects.requireNonNull(reference);sha256=hash(sha256);purpose=choice(purpose,"understanding","investigation");
            nullableSafe(inputRevision);Objects.requireNonNull(state);invalidationCode=nullableRef(invalidationCode);
            if(state==ContextState.active&&invalidationCode!=null)throw new IllegalArgumentException("Active context cannot carry an invalidation");
        }
    }
    public record Authority(String kind,UUID subjectId,String qualificationRef) {
        public Authority {
            kind=choice(kind,"human","policy","unknown");qualificationRef=nullableRef(qualificationRef);
            if(kind.equals("human")&&subjectId==null)throw new IllegalArgumentException("Human approval needs its actual subject");
        }
    }
    /** Scope is an explicit projection; it is not the entire original approved plan. */
    public record CsvScope(UUID detailAttachmentId,String detailSha256,UUID reportAttachmentId,
                           String reportSha256,String from,String to,String timeZone,
                           String currency,String status) {
        public CsvScope {
            Objects.requireNonNull(detailAttachmentId);Objects.requireNonNull(reportAttachmentId);
            if(detailAttachmentId.equals(reportAttachmentId))throw new IllegalArgumentException("Distinct CSV roles required");
            detailSha256=hash(detailSha256);reportSha256=hash(reportSha256);from=time(from);to=time(to);
            if(!Instant.parse(from).isBefore(Instant.parse(to)))throw new IllegalArgumentException("Empty CSV range");
            ZoneId.of(Objects.requireNonNull(timeZone));currency=requireCurrency(currency);
            status=choice(status,"completed","pending","cancelled");
        }
    }
    public record Operation(UUID approvalId,UUID bindingId,long approvalRevision,String state,
                            String operationHash,CsvScope scope,Authority authority,
                            Long inputRevision,Long environmentRevision,Long authorizationRevision,
                            String validUntil) {
        public Operation {
            Objects.requireNonNull(approvalId);Objects.requireNonNull(bindingId);safe(approvalRevision);
            state=choice(state,"pending","approved","denied","superseded","unknown");operationHash=hash(operationHash);
            Objects.requireNonNull(scope);nullableSafe(inputRevision);nullableSafe(environmentRevision);nullableSafe(authorizationRevision);
            validUntil=nullableTime(validUntil);
        }
    }
    public enum Ownership { active, inactive, unknown }
    public enum EffectStatus { none, verified, unknown }
    public record Execution(UUID bindingId,UUID nodeRunId,String providerId,String providerRunId,
                            String state,String acceptedAt,String closedAt,Ownership ownership,
                            String outcome,EffectStatus effectStatus,Long snapshotRevision,
                            String authoritativeSnapshotHash,String receiptRef,
                            boolean stopRequested,Boolean providerCancelRequested,String closureProofRef,
                            Long providerEventWatermark,String providerResumeCursor) {
        public Execution {
            Objects.requireNonNull(bindingId);Objects.requireNonNull(nodeRunId);providerId=nullableRef(providerId);
            providerRunId=nullableRef(providerRunId);
            state=choice(state,"prepared","submitting","acceptance_unknown","accepted","active","waiting_permission","reconciling","stopping","terminal","rejected","unknown");
            acceptedAt=nullableTime(acceptedAt);closedAt=nullableTime(closedAt);Objects.requireNonNull(ownership);
            if(outcome!=null)outcome=choice(outcome,"succeeded","failed","cancelled","rejected");
            Objects.requireNonNull(effectStatus);nullableSafe(snapshotRevision);
            authoritativeSnapshotHash=nullableHash(authoritativeSnapshotHash);receiptRef=nullableRef(receiptRef);
            closureProofRef=nullableRef(closureProofRef);nullableSafe(providerEventWatermark);
            if(providerResumeCursor!=null&&(providerResumeCursor.isEmpty()||providerResumeCursor.length()>4096||providerResumeCursor.codePoints().anyMatch(Character::isISOControl)))throw new IllegalArgumentException("Invalid original Provider cursor");
            if(closedAt!=null&&ownership!=Ownership.inactive)throw new IllegalArgumentException("A closed binding must have authoritative inactive ownership");
            if(acceptedAt!=null&&closedAt!=null&&Instant.parse(closedAt).isBefore(Instant.parse(acceptedAt)))throw new IllegalArgumentException("Closure precedes acceptance");
        }
    }
    public enum InputState { active, revoked, unknown }
    public record Input(UUID attachmentId,String role,String sha256,long bytes,InputState state,
                        String authorizationRef) {
        public Input {
            Objects.requireNonNull(attachmentId);role=choice(role,"detail","report","other");sha256=hash(sha256);safe(bytes);
            Objects.requireNonNull(state);authorizationRef=nullableRef(authorizationRef);
        }
    }
    public record CsvResult(long differenceCount,String detailTotal,String reportTotal,
                            String totalDifference,String currency,String algorithm) {
        public CsvResult {
            safe(differenceCount);detailTotal=decimal(detailTotal);reportTotal=decimal(reportTotal);totalDifference=decimal(totalDifference);
            currency=requireCurrency(currency);algorithm=choice(algorithm,"report-minus-detail-v1");
            if(new java.math.BigDecimal(reportTotal).subtract(new java.math.BigDecimal(detailTotal)).compareTo(new java.math.BigDecimal(totalDifference))!=0)
                throw new IllegalArgumentException("Inconsistent CSV totals");
        }
    }
    public record Artifact(UUID artifactId,UUID bindingId,String sha256,long bytes,String state,
                           long resultRevision,long inputRevision,CsvResult result) {
        public Artifact {
            Objects.requireNonNull(artifactId);Objects.requireNonNull(bindingId);sha256=hash(sha256);safe(bytes);
            state=choice(state,"ready","superseded","revoked","unknown");safe(resultRevision);safe(inputRevision);
        }
    }
    /** An unresolved legacy pair remains null, never guessed from today's default model. */
    public record Reservation(UUID bindingId,UUID subjectId,String period,String state,
                              String totalAccountKey,String modelAccountKey) {
        public Reservation {
            Objects.requireNonNull(bindingId);Objects.requireNonNull(subjectId);period=utcPeriod(period);
            state=choice(state,"reserved","consumed","released","settled","unknown");
            if((totalAccountKey==null)!=(modelAccountKey==null))throw new IllegalArgumentException("Partial frozen account pair");
            if(totalAccountKey!=null){
                if(!totalAccountKey.equals("total"))throw new IllegalArgumentException("Invalid total account");
                modelAccountKey=ref(modelAccountKey);
                if(!modelAccountKey.startsWith("model:")||modelAccountKey.length()==6)throw new IllegalArgumentException("Invalid model account");
            }
        }
    }
    /** These are whole-account balances at export, not attributed per-binding model charges. */
    public record AccountBalance(UUID subjectId,String accountKey,String period,long turnLimit,
                                 long reserved,long consumed) {
        public AccountBalance {
            Objects.requireNonNull(subjectId);accountKey=ref(accountKey);period=utcPeriod(period);
            safe(turnLimit);safe(reserved);safe(consumed);
            if(reserved>ContractChecks.MAX_SAFE_INTEGER-consumed)throw new IllegalArgumentException("Unsafe account total");
        }
    }
    public record Verification(UUID artifactId,long resultRevision,UUID subjectId,boolean resolved) {
        public Verification {Objects.requireNonNull(artifactId);safe(resultRevision);Objects.requireNonNull(subjectId);}
    }
    public record Unresolved(String code,UUID bindingId,String nextCheck) {
        public Unresolved {code=ref(code);nextCheck=text(nextCheck,1000);}
    }
    public record Responsibility(UUID nextSubjectId,String status,List<String> nextChecks) {
        public Responsibility {
            status=choice(status,"assigned","unassigned");
            if(status.equals("assigned")!=(nextSubjectId!=null))throw new IllegalArgumentException("Responsibility must name an actual assignee");
            nextChecks=List.copyOf(Objects.requireNonNull(nextChecks).stream().map(s->text(s,1000)).toList());
        }
    }
    private static long safe(long value){return ContractChecks.safeInteger(value);}
    private static void nullableSafe(Long value){if(value!=null)safe(value);}
    private static String hash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid SHA-256");return value;}
    private static String nullableHash(String value){return value==null?null:hash(value);}
    private static String ref(String value){if(value==null||!value.matches("[A-Za-z0-9_.:-]{1,180}"))throw new IllegalArgumentException("Invalid opaque reference");return value;}
    private static String nullableRef(String value){return value==null?null:ref(value);}
    private static String text(String value,int limit){if(value==null||value.isBlank()||value.length()>limit||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid handover text");return value;}
    private static String choice(String value,String...values){if(value==null||!Set.of(values).contains(value))throw new IllegalArgumentException("Invalid handover state");return value;}
    private static String requireCurrency(String value){if(value==null||!value.matches("[A-Z]{3}"))throw new IllegalArgumentException("Invalid currency");return value;}
    private static String decimal(String value){if(value==null||!value.matches("-?(0|[1-9][0-9]{0,16})\\.[0-9]{2}"))throw new IllegalArgumentException("Invalid exact CSV amount");return value;}
    private static String time(String value){
        if(value==null||!value.endsWith("Z"))throw new IllegalArgumentException("UTC time required");
        var parsed=ContractChecks.microsecond(Instant.parse(value));
        String normalized=new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(parsed);
        if(!normalized.matches("[0-9]{4}-.*"))throw new IllegalArgumentException("Four-digit UTC year required");
        return normalized;
    }
    private static String nullableTime(String value){return value==null?null:time(value);}
    private static String utcPeriod(String value){if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!LocalDate.parse(value).toString().equals(value))throw new IllegalArgumentException("Invalid frozen UTC period");return value;}
    private static <T> List<T> sorted(List<T> values,Function<T,String> identity){
        var result=new ArrayList<>(Objects.requireNonNull(values));result.forEach(Objects::requireNonNull);result.sort(Comparator.comparing(identity));
        var seen=new HashSet<String>();for(T value:result)if(!seen.add(identity.apply(value)))throw new IllegalArgumentException("Duplicate handover fact");
        return List.copyOf(result);
    }
}
