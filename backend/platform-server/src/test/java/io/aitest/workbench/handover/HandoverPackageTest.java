package io.aitest.workbench.handover;

import io.aitest.contract.CanonicalJson;
import io.aitest.contract.ContractChecks;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static io.aitest.workbench.handover.HandoverPackage.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure projection checks. No database, resource access or export authorization is implied. */
class HandoverPackageTest {
    static final UUID CONVERSATION=uuid(1),ENVIRONMENT=uuid(2),BINDING=uuid(3),NODE=uuid(4),SUBJECT=uuid(5),DETAIL=uuid(6),REPORT=uuid(7);
    static UUID uuid(int n){return UUID.fromString("00000000-0000-4000-8000-"+String.format("%012d",n));}
    static final String HASH="a".repeat(64),OTHER_HASH="b".repeat(64);
    Conversation conversation(){return new Conversation(CONVERSATION,ENVIRONMENT,3,4,6,"open");}
    Execution unknown(){return new Execution(BINDING,NODE,null,null,"acceptance_unknown",null,null,Ownership.unknown,null,EffectStatus.unknown,null,null,null,false,null,null,null,null);}
    List<Input> inputs(){return List.of(new Input(DETAIL,"detail",HASH,120,InputState.active,null),new Input(REPORT,"report",OTHER_HASH,130,InputState.active,null));}
    HandoverPackage pack(String generated,List<Input> inputs,List<Execution> executions,List<Reservation> reservations,List<Unresolved> unresolved,Responsibility responsibility){
        return new HandoverPackage(VERSION,generated,conversation(),null,null,executions,inputs,List.of(),reservations,List.of(),List.of(),unresolved,responsibility);
    }
    Responsibility unassigned(){return new Responsibility(null,"unassigned",List.of("核查原提交回执后决定下一步"));}

    @Test void canonicalDigestIsStableForEquivalentFactOrderAndUtcFormatting(){
        var first=pack("2026-10-26T12:00:00Z",inputs(),List.of(unknown()),List.of(),List.of(),unassigned());
        var reversed=new ArrayList<>(inputs());Collections.reverse(reversed);
        var second=pack("2026-10-26T12:00:00.000000Z",reversed,List.of(unknown()),List.of(),List.of(),unassigned());
        assertEquals(first.canonicalJson(),second.canonicalJson());assertEquals(first.sha256(),second.sha256());
        assertEquals(CanonicalJson.hashBytes(first.canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)),first.sha256());
    }
    @Test void projectionCopiesListsAndCannotChangeAfterExport(){
        var values=new ArrayList<>(inputs());var next=new ArrayList<>(List.of("核查原提交"));
        var result=pack("2026-10-26T12:00:00Z",values,List.of(unknown()),List.of(),List.of(),new Responsibility(null,"unassigned",next));String before=result.sha256();
        values.clear();next.add("未经批准的替代执行");
        assertEquals(2,result.inputs().size());assertEquals(1,result.responsibility().nextChecks().size());assertEquals(before,result.sha256());
        assertThrows(UnsupportedOperationException.class,()->result.inputs().clear());
    }
    @Test void unknownExecutionAndLegacyReservationDoNotInventProviderOrFrozenAccountFacts(){
        var reservation=new Reservation(BINDING,SUBJECT,"2026-10-25","reserved",null,null);
        var result=pack("2026-10-26T12:00:00Z",inputs(),List.of(unknown()),List.of(reservation),List.of(new Unresolved("SUBMISSION_RESULT_UNKNOWN",BINDING,"沿用原提交键核查，不启动替代任务")),unassigned());
        var json=JsonMapper.builder().build().readTree(result.canonicalJson());
        assertTrue(json.get("executions").get(0).get("providerRunId").isNull());assertTrue(json.get("context").isNull());
        assertEquals("unknown",json.get("executions").get(0).get("ownership").asText());
        assertEquals("reserved",json.get("reservations").get(0).get("state").asText());
        assertTrue(json.get("reservations").get(0).get("modelAccountKey").isNull());assertEquals("2026-10-25",json.get("reservations").get(0).get("period").asText());
        assertEquals(Ownership.unknown,result.executions().getFirst().ownership());
    }
    @Test void staleContextCanBeRepresentedWithoutBecomingActiveOrChangingItsOriginalHash(){
        var context=new Context(uuid(8),HASH,"investigation",2L,ContextState.stale,"INPUT_CHANGED");
        var result=new HandoverPackage(VERSION,"2026-10-26T12:00:00Z",conversation(),context,null,List.of(unknown()),inputs(),List.of(),List.of(),List.of(),List.of(),List.of(),unassigned());
        assertEquals(HASH,result.context().sha256());assertEquals(ContextState.stale,result.context().state());assertEquals(2,result.context().inputRevision());assertEquals(3,result.conversation().inputRevision());
        assertThrows(IllegalArgumentException.class,()->new Context(uuid(8),HASH,"investigation",2L,ContextState.active,"INPUT_CHANGED"));
    }
    @Test void contentChangeChangesPackageDigestWithoutPretendingToChangeApprovalHash(){
        var scope=new CsvScope(DETAIL,HASH,REPORT,OTHER_HASH,"2026-10-25T00:00:00Z","2026-10-26T00:00:00Z","UTC","EUR","completed");
        var op=new Operation(uuid(9),BINDING,2,"approved",HASH,scope,new Authority("human",SUBJECT,"membership:"+SUBJECT+":2"),3L,1L,2L,"2026-10-26T12:10:00Z");
        var first=new HandoverPackage(VERSION,"2026-10-26T12:00:00Z",conversation(),null,op,List.of(unknown()),inputs(),List.of(),List.of(),List.of(),List.of(),List.of(),unassigned());
        var second=new HandoverPackage(VERSION,"2026-10-26T12:00:00Z",conversation(),null,op,List.of(unknown()),inputs(),List.of(),List.of(),List.of(),List.of(),List.of(new Unresolved("AWAIT_ORIGINAL_PROVIDER",BINDING,"查询原拥有者")),unassigned());
        assertNotEquals(first.sha256(),second.sha256());assertEquals(HASH,first.operation().operationHash());assertEquals(HASH,second.operation().operationHash());
    }
    @Test void precisionAndIntegerBoundariesAreRejectedRatherThanRounded(){
        assertThrows(IllegalArgumentException.class,()->pack("2026-10-26T12:00:00.0000001Z",inputs(),List.of(),List.of(),List.of(),unassigned()));
        assertThrows(IllegalArgumentException.class,()->pack("2026-10-26T12:00:00+00:00",inputs(),List.of(),List.of(),List.of(),unassigned()));
        assertThrows(IllegalArgumentException.class,()->new Conversation(CONVERSATION,ENVIRONMENT,ContractChecks.MAX_SAFE_INTEGER+1,1,1,"open"));
        assertThrows(IllegalArgumentException.class,()->new AccountBalance(SUBJECT,"total","2026-10-25",ContractChecks.MAX_SAFE_INTEGER,ContractChecks.MAX_SAFE_INTEGER,1));
        assertThrows(IllegalArgumentException.class,()->new Input(DETAIL,"detail","A".repeat(64),10,InputState.active,null));
    }
    @Test void duplicateFactsAndDanglingEvidenceAreRejected(){
        assertThrows(IllegalArgumentException.class,()->pack("2026-10-26T12:00:00Z",List.of(inputs().getFirst(),inputs().getFirst()),List.of(),List.of(),List.of(),unassigned()));
        assertThrows(IllegalArgumentException.class,()->pack("2026-10-26T12:00:00Z",inputs(),List.of(),List.of(new Reservation(BINDING,SUBJECT,"2026-10-25","reserved","total","model:fixture-v1")),List.of(),unassigned()));
        assertThrows(IllegalArgumentException.class,()->new Reservation(BINDING,SUBJECT,"2026-10-25","reserved","total",null));
        assertThrows(IllegalArgumentException.class,()->new Responsibility(SUBJECT,"unassigned",List.of()));
    }
    @Test void exactLargeCsvTotalsSurviveAndInconsistentEvidenceIsRejected(){
        var result=new CsvResult(10000,"-9999999999999900.00","9999999999999900.00","19999999999999800.00","EUR","report-minus-detail-v1");
        assertEquals("19999999999999800.00",result.totalDifference());
        assertThrows(IllegalArgumentException.class,()->new CsvResult(1,"1.00","2.00","2.00","EUR","report-minus-detail-v1"));
        assertThrows(IllegalArgumentException.class,()->new CsvResult(1,"1e3","2.00","1.00","EUR","report-minus-detail-v1"));
    }
    @Test void closureRequiresInactiveOwnershipAndOpaqueReferencesRejectPathsOrUrls(){
        assertThrows(IllegalArgumentException.class,()->new Execution(BINDING,NODE,null,null,"terminal",null,"2026-10-26T12:00:00Z",Ownership.unknown,"cancelled",EffectStatus.unknown,null,null,null,false,null,null,null,null));
        assertThrows(IllegalArgumentException.class,()->new Execution(BINDING,NODE,"fixture","https://private-host/run","accepted",null,null,Ownership.active,null,EffectStatus.none,null,null,null,false,null,null,null,null));
        assertThrows(IllegalArgumentException.class,()->new Authority("human",null,null));
        assertThrows(IllegalArgumentException.class,()->new Unresolved("UNKNOWN",BINDING,"line1\nprivate-path"));
    }
    @Test void originalProviderCursorAndPendingStopDoNotInventClosureProof(){
        var execution=new Execution(BINDING,NODE,null,null,"stopping",null,null,Ownership.unknown,null,EffectStatus.unknown,8L,HASH,null,true,true,null,9L,"original+/cursor==");
        assertTrue(execution.stopRequested());assertEquals(Boolean.TRUE,execution.providerCancelRequested());assertNull(execution.closureProofRef());assertEquals("original+/cursor==",execution.providerResumeCursor());assertEquals(9L,execution.providerEventWatermark());
        assertThrows(IllegalArgumentException.class,()->new Execution(BINDING,NODE,null,null,"stopping",null,null,Ownership.unknown,null,EffectStatus.unknown,null,null,null,true,null,null,ContractChecks.MAX_SAFE_INTEGER+1,"original"));
        assertThrows(IllegalArgumentException.class,()->new Execution(BINDING,NODE,null,null,"stopping",null,null,Ownership.unknown,null,EffectStatus.unknown,null,null,null,true,null,null,null,"bad\ncursor"));
    }
    @Test void expandedYearsCannotEscapeTheFourDigitWireTimeAndPeriodContract(){
        assertThrows(IllegalArgumentException.class,()->pack("+10000-01-01T00:00:00Z",inputs(),List.of(),List.of(),List.of(),unassigned()));
        assertThrows(IllegalArgumentException.class,()->new Reservation(BINDING,SUBJECT,"+10000-01-01","reserved",null,null));
        assertThrows(IllegalArgumentException.class,()->new Reservation(BINDING,SUBJECT,"-0001-01-01","reserved",null,null));
    }
}
