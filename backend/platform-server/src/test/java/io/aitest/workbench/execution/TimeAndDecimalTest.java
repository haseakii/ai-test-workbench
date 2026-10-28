package io.aitest.workbench.execution;

import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.identity.ContentReview;
import io.aitest.workbench.material.Materials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static io.aitest.workbench.infrastructure.Store.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class TimeAndDecimalTest {
    @TempDir Path blobs;
    DispatchAtomicityTest helper(){var h=new DispatchAtomicityTest();h.blobs=blobs;return h;}
    record Inputs(UUID conversation,String detail,String report){}
    byte[] csv(List<String> amounts,List<Instant> times){
        var text=new StringBuilder("order_id,amount,currency,status,occurred_at\r\n");
        for(int i=0;i<amounts.size();i++)text.append("O").append(i).append(',').append(amounts.get(i)).append(",EUR,completed,").append(times.get(i)).append("\r\n");
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
    Inputs inputs(DispatchAtomicityTest.Fixture f,byte[] detail,byte[] report)throws Exception {
        UUID c=UUID.fromString(f.conversations().create(f.owner(),f.environment(),UUID.randomUUID().toString()).get("id").toString());
        f.conversations().message(f.owner(),c,UUID.randomUUID().toString(),"Synthetic time and numeric boundary",1,UUID.randomUUID().toString());
        var material=new Materials(f.db(),f.scope(),blobs.toString());
        var d=material.upload(f.owner(),c,new MockMultipartFile("file","detail.csv","text/csv",detail),UUID.randomUUID().toString());
        var r=material.upload(f.owner(),c,new MockMultipartFile("file","report.csv","text/csv",report),UUID.randomUUID().toString());
        return new Inputs(c,d.get("id").toString(),r.get("id").toString());
    }
    PreparationService.Request request(Inputs i,String from,String to,String zone){return new PreparationService.Request(i.detail(),i.report(),from,to,zone,"EUR","completed",3,600);}
    @Test void injectedClockNormalizesMicrosAndUtcPeriodRegardlessOfClockZone()throws Exception {
        var h=helper();var clock=new MutableClock("2026-10-26T23:59:59.999999999Z");var f=h.fixture("normal",clock.withZone(ZoneId.of("Asia/Shanghai")));
        assertEquals(Instant.parse("2026-10-26T23:59:59.999999Z"),f.db().now());assertEquals("2026-10-26",f.db().quotaPeriod());
        var p=h.prepared(f,false);
        try{
            var event=f.db().one("SELECT occurred_at FROM platform_events WHERE tenant_id=? AND conversation_id=? AND sequence=1",id(TENANT),id(h.conversation(f,p)));
            assertEquals(f.db().now(),instant(event.get("occurred_at")));clock.set("2026-10-27T00:00:00.000000001Z");
            assertEquals(Instant.parse("2026-10-27T00:00:00Z"),f.db().now());assertEquals("2026-10-27",f.db().quotaPeriod());
        }finally{h.close(f,p);}
    }
    @Test void subMicrosecondPlanScopeIsRejectedRatherThanTruncated()throws Exception {
        var f=helper().fixture();var bytes=csv(List.of("1.00"),List.of(Instant.parse("2026-10-25T12:00:00Z")));var i=inputs(f,bytes,bytes);
        var error=assertThrows(Store.Problem.class,()->f.preparation().prepare(f.owner(),i.conversation(),request(i,"2026-10-25T00:00:00.000000001Z","2026-10-26T00:00:00.000000Z","UTC"),UUID.randomUUID().toString()));
        assertEquals(422,error.status);assertEquals("INVALID_TIME_SCOPE",error.code);
    }
    @Test void planRangeLimitIsExactAndNotFlooredToWholeDays()throws Exception {
        var h=helper();var f=h.fixture();var bytes=csv(List.of("1.00"),List.of(Instant.parse("2026-10-25T12:00:00Z")));var i=inputs(f,bytes,bytes);
        var from=Instant.parse("2025-10-25T00:00:00Z");var to=from.plus(Duration.ofDays(366));
        var p=f.preparation().prepare(f.owner(),i.conversation(),request(i,from.toString(),to.toString(),"UTC"),UUID.randomUUID().toString());
        try{
            var other=inputs(f,bytes,bytes);var error=assertThrows(Store.Problem.class,()->f.preparation().prepare(f.owner(),other.conversation(),request(other,from.toString(),to.plusNanos(1000).toString(),"UTC"),UUID.randomUUID().toString()));
            assertEquals("INVALID_PLAN_SCOPE",error.code);
        }finally{h.close(f,p);}
    }
    @Test void queueLeaseUsesInjectedMicrosecondBoundary()throws Exception {
        var h=helper();var clock=new MutableClock("2026-10-26T12:00:00.000000Z");var f=h.fixture("normal",clock);var p=h.prepared(f,true);
        try{
            f.db().update("UPDATE outbox SET lease_until=? WHERE tenant_id=? AND binding_id=?",LocalDateTime.parse("2026-10-26T12:00:00.000001"),id(TENANT),id(h.binding(p)));
            f.worker().tick();assertEquals("prepared",f.db().one("SELECT state FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(h.binding(p))).get("state"));
            clock.set("2026-10-26T12:00:00.000001Z");f.worker().tick();
            assertEquals("accepted",f.db().one("SELECT state FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(h.binding(p))).get("state"));
            h.reconcile(f,p); // The independent Provider executes; the Worker only observes its facts.
            assertEquals("terminal",f.db().one("SELECT state FROM execution_bindings WHERE tenant_id=? AND id=?",id(TENANT),id(h.binding(p))).get("state"));
            assertEquals(1,number(f.db().one("SELECT consumed FROM quota_accounts WHERE tenant_id=? AND subject_id=? AND account_key=?",id(TENANT),id(f.owner().id()),key("total")).get("consumed")));
        }finally{h.close(f,p);}
    }
    @Test void firstAcceptanceWindowIsExclusiveButAcceptedLookupSurvivesExpiry()throws Exception {
        var h=helper();var clock=new MutableClock("2026-10-26T12:00:00.000001Z");var f=h.fixture("normal",clock);var p=h.prepared(f,true);
        try{
            assertTrue(h.dispatch(f,p));clock.set("2026-10-26T12:00:00.000000Z");
            assertEquals("ADMISSION_EXPIRED",assertThrows(Store.Problem.class,()->f.provider().start(h.binding(p))).code);
            clock.set("2026-10-26T12:10:00.000000Z");var first=f.provider().start(h.binding(p));
            clock.set("2026-10-26T12:10:00.000001Z");var replay=f.provider().start(h.binding(p));
            assertArrayEquals((byte[])first.get("receipt_id"),(byte[])replay.get("receipt_id"));h.reconcile(f,p);
            assertEquals("awaiting_verification",f.conversations().snapshot(f.owner(),h.conversation(f,p)).get("state"));
        }finally{h.close(f,p);}
    }
    @Test void unacceptedSubmissionAtExactApprovalExpiryCannotConsumeBudget()throws Exception {
        var h=helper();var clock=new MutableClock("2026-10-26T12:00:00.000001Z");var f=h.fixture("normal",clock);var p=h.prepared(f,true);
        try{
            assertTrue(h.dispatch(f,p));clock.set("2026-10-26T12:10:00.000001Z");
            assertEquals("APPROVAL_EXPIRED",assertThrows(Store.Problem.class,()->f.provider().start(h.binding(p))).code);
        }finally{h.close(f,p);}
        assertEquals(0,number(f.db().one("SELECT consumed FROM quota_accounts WHERE tenant_id=? AND subject_id=? AND account_key=?",id(TENANT),id(f.owner().id()),key("total")).get("consumed")));
    }
    @Test void messageLeaseExpiresAtInjectedMicrosecondWithoutSleeping()throws Exception {
        var clock=new MutableClock("2026-10-26T12:00:00.000001Z");var f=helper().fixture("normal",clock);var review=new ContentReview(f.db(),f.scope());var c=UUID.fromString(f.conversations().create(f.owner(),f.environment(),UUID.randomUUID().toString()).get("id").toString());
        f.conversations().message(f.owner(),c,UUID.randomUUID().toString(),"Lease boundary message",1,UUID.randomUUID().toString());
        var reviewer=f.scope().actor("reviewer");review.eligibility(f.scope().actor("admin"),"reviewer",f.environment(),true,reviewer.revision(),"Synthetic lease boundary review",UUID.randomUUID().toString());
        reviewer=f.scope().actor("reviewer");review.lease(reviewer,c,"Check synthetic lease boundary",1,UUID.randomUUID().toString());var actor=reviewer;
        clock.set("2026-10-26T12:00:01.000000Z");assertFalse(((List<?>)review.read(actor,c).get("messages")).isEmpty());
        clock.set("2026-10-26T12:00:01.000001Z");assertEquals("CONTENT_LEASE_EXPIRED",assertThrows(Store.Problem.class,()->review.read(actor,c)).code);
    }
    @Test void dstDaysPreserveBothRepeatedHoursAndExclusiveEndWithExactMoney()throws Exception {
        var h=helper();var f=h.fixture();var material=new Materials(f.db(),f.scope(),blobs.toString());var zone=ZoneId.of("America/New_York");
        for(var day:List.of(LocalDate.of(2026,3,8),LocalDate.of(2026,11,1))){
            Instant from=day.atStartOfDay(zone).toInstant(),to=day.plusDays(1).atStartOfDay(zone).toInstant();assertEquals(day.getMonthValue()==3?23:25,Duration.between(from,to).toHours());
            var times=List.of(from,to.minusNanos(1),to,day.getMonthValue()==11?Instant.parse("2026-11-01T05:30:00Z"):from.plusSeconds(7200),day.getMonthValue()==11?Instant.parse("2026-11-01T06:30:00Z"):from.plusSeconds(10800));
            var i=inputs(f,csv(Collections.nCopies(5,"0.00"),times),csv(List.of("1.00","2.00","4.00","8.00","16.00"),times));
            var p=f.preparation().prepare(f.owner(),i.conversation(),request(i,from.toString(),to.toString(),zone.toString()),UUID.randomUUID().toString());
            try{var result=material.calculate(i.conversation(),f.db().object(p.get("plan")));assertEquals(4,result.summary().get("differenceCount"));assertEquals("27.00",result.summary().get("totalDifference"));}
            finally{h.close(f,p);}
        }
    }
    @Test void csvAmountAndRowLimitsRejectExponentsPrecisionLossAndOverflow()throws Exception {
        var material=new Materials(null,null,blobs.toString());var time=Instant.parse("2026-10-25T12:00:00Z");
        assertEquals(2,material.parse(csv(List.of("999999999999.99","-999999999999.99"),Collections.nCopies(2,time))).size());
        for(String amount:List.of("1000000000000.00","1.001","1e3","NaN"))assertEquals("CSV_VALUE_INVALID",assertThrows(Store.Problem.class,()->material.parse(csv(List.of(amount),List.of(time)))).code);
        assertEquals("CSV_LIMIT_OR_COLUMNS",assertThrows(Store.Problem.class,()->material.parse(csv(Collections.nCopies(10001,"0.00"),Collections.nCopies(10001,time)))).code);
    }
    @Test void maximumCsvAmountsAggregateAsDecimalStringsWithoutTruncation()throws Exception {
        var h=helper();var f=h.fixture();var time=Instant.parse("2026-10-25T12:00:00Z");var times=Collections.nCopies(10000,time);
        var i=inputs(f,csv(Collections.nCopies(10000,"-999999999999.99"),times),csv(Collections.nCopies(10000,"999999999999.99"),times));
        var p=f.preparation().prepare(f.owner(),i.conversation(),request(i,"2026-10-25T00:00:00Z","2026-10-26T00:00:00Z","UTC"),UUID.randomUUID().toString());
        try{
            var summary=new Materials(f.db(),f.scope(),blobs.toString()).calculate(i.conversation(),f.db().object(p.get("plan"))).summary();
            assertEquals("-9999999999999900.00",summary.get("detailTotal"));assertEquals("9999999999999900.00",summary.get("reportTotal"));assertEquals("19999999999999800.00",summary.get("totalDifference"));assertEquals(10000,summary.get("differenceCount"));
        }finally{h.close(f,p);}
    }
}
