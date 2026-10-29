package io.aitest.runner;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** JDBC protocol double: verifies orchestration, not MySQL locking or durability. */
class ScopedCommandJournalTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-29T01:02:03.123456Z"),ZoneOffset.UTC);
    private static final UUID TENANT=UUID.randomUUID(), NODE=UUID.randomUUID(), BINDING=UUID.randomUUID();
    private static ScopedCommandJournal.Command command(UUID tenant,String provider,UUID node,String type,String key,UUID binding,int hash,long epoch) {
        byte[] digest=new byte[32]; Arrays.fill(digest,(byte)hash);
        return new ScopedCommandJournal.Command(tenant,provider,node,type,key,binding,digest,digest,epoch);
    }
    private static ScopedCommandJournal.Command base(){return command(TENANT,"acp",NODE,"start","same",BINDING,1,1);}
    @Test void namespacesAreDistinctAndReplayReturnsSameIntent() throws SQLException {
        var db=new Database();var journal=new ScopedCommandJournal(db.source(),(c,cmd)->true,CLOCK);
        var first=journal.record(base());assertEquals(ScopedCommandJournal.Status.RECORDED,first.status());assertEquals(first,journal.record(base()));
        var others=List.of(command(UUID.randomUUID(),"acp",NODE,"start","same",BINDING,1,1),command(TENANT,"other",NODE,"start","same",BINDING,1,1),command(TENANT,"acp",UUID.randomUUID(),"start","same",BINDING,1,1),command(TENANT,"acp",NODE,"close","same",BINDING,1,1),command(TENANT,"acp",NODE,"start","other",BINDING,1,1));
        for(var other:others){var result=journal.record(other);assertEquals(ScopedCommandJournal.Status.RECORDED,result.status());assertNotEquals(first.intentId(),result.intentId());}
        assertEquals(6,db.rows.size());
    }
    @Test void changedBindingHashOrEpochConflictsAndEveryReplayReauthorizes() throws SQLException {
        var db=new Database();var allow=new boolean[]{true};var journal=new ScopedCommandJournal(db.source(),(c,cmd)->allow[0],CLOCK);
        var first=journal.record(base());assertEquals(ScopedCommandJournal.Status.RECORDED,first.status());
        for(var altered:List.of(command(TENANT,"acp",NODE,"start","same",UUID.randomUUID(),1,1),command(TENANT,"acp",NODE,"start","same",BINDING,2,1),command(TENANT,"acp",NODE,"start","same",BINDING,1,2)))assertEquals(ScopedCommandJournal.Status.CONFLICT,journal.record(altered).status());
        allow[0]=false;assertEquals(ScopedCommandJournal.Status.FORBIDDEN,journal.record(base()).status());assertEquals(1,db.rows.size());
    }
    @Test void lostCommitNeverClaimsSuccessAndRetryFindsOriginal() throws SQLException {
        var db=new Database();db.loseCommit=true;var journal=new ScopedCommandJournal(db.source(),(c,cmd)->true,CLOCK);
        assertEquals(ScopedCommandJournal.Status.UNKNOWN,journal.record(base()).status());db.loseCommit=false;
        var recovered=journal.record(base());assertEquals(ScopedCommandJournal.Status.RECORDED,recovered.status());assertEquals(recovered,journal.record(base()));assertEquals(1,db.rows.size());
    }
    @Test void identifiersPreserveUnicodeWhitespaceAndLongValues() throws SQLException {
        var db=new Database();var journal=new ScopedCommandJournal(db.source(),(c,cmd)->true,CLOCK);
        var c=command(TENANT," 提供者 ",NODE," ","中文🙂".repeat(80),BINDING,1,1);
        var result=journal.record(c);assertEquals(ScopedCommandJournal.Status.RECORDED,result.status());assertEquals(result,journal.record(c));
        assertNotEquals(result.intentId(),journal.record(command(TENANT,"提供者",NODE," ","中文🙂".repeat(80),BINDING,1,1)).intentId());
    }
    @Test void digestCollisionsNeverReuseOrOverwriteDifferentNames() throws SQLException {
        var db=new Database();var journal=new ScopedCommandJournal(db.source(),(c,cmd)->true,CLOCK,bytes->new byte[32]);
        var first=journal.record(base());assertEquals(ScopedCommandJournal.Status.RECORDED,first.status());
        for(var c:List.of(command(TENANT,"other",NODE,"start","same",BINDING,1,1),command(TENANT,"acp",NODE,"close","same",BINDING,1,1),command(TENANT,"acp",NODE,"start","other",BINDING,1,1)))
            assertEquals(ScopedCommandJournal.Status.CONFLICT,journal.record(c).status());
        assertEquals(first,journal.record(base()));assertEquals(1,db.rows.size());
    }
    @Test void invalidUnicodeAndHashMutationAreRejected(){
        var c=base();byte[] digest=c.specHash();digest[0]=9;assertEquals(1,c.specHash()[0]);
        assertThrows(IllegalArgumentException.class,()->command(TENANT,"",NODE,"start","key",BINDING,1,1));
        assertThrows(IllegalArgumentException.class,()->command(TENANT,"acp",NODE,"start","key",BINDING,1,9_007_199_254_740_992L));
        assertThrows(IllegalArgumentException.class,()->command(TENANT,"\ud800",NODE,"start","key",BINDING,1,1));
    }
    private static final class Database {
        final Map<String,Object[]> rows=new HashMap<>();boolean loseCommit;
        DataSource source(){return proxy(DataSource.class,(method,args)->method.equals("getConnection")?connection():null);}
        Connection connection(){
            var pending=new Object[][]{null};
            return proxy(Connection.class,(method,args)->switch(method){
                case "prepareStatement" -> statement((String)args[0],pending);
                case "commit" -> {if(pending[0]!=null){rows.put(key(pending[0]),pending[0]);pending[0]=null;}if(loseCommit)throw new SQLException("lost commit");yield null;}
                case "rollback" -> {pending[0]=null;yield null;}
                default -> null;
            });
        }
        PreparedStatement statement(String sql,Object[][] pending){
            Object[] params=new Object[14];
            return proxy(PreparedStatement.class,(method,args)->{
                if(method.startsWith("set") && args[0] instanceof Integer){params[(Integer)args[0]-1]=args[1];return null;}
                if(method.equals("executeUpdate")){pending[0]=params.clone();return 1;}
                if(method.equals("executeQuery")){Object[] row=rows.get(key(params));var hasNext=new boolean[]{row!=null};return proxy(ResultSet.class,(call,a)->switch(call){
                    case "next" -> {boolean result=hasNext[0];hasNext[0]=false;yield result;}
                    case "getBytes" -> row[switch((Integer)a[0]){case 1->5;case 2->6;case 3->7;case 5->9;case 7->11;case 8->12;case 9->13;default->throw new IllegalArgumentException();}];
                    case "getLong" -> row[8];case "getTimestamp" -> row[10];default -> null;
                });}
                return null;
            });
        }
        static String key(Object[] p){return java.util.stream.IntStream.range(0,5).mapToObj(i->HexFormat.of().formatHex((byte[])p[i])).reduce((a,b)->a+":"+b).orElseThrow();}
    }
    @FunctionalInterface interface Handler { Object call(String method,Object[] args) throws Throwable; }
    static <T>T proxy(Class<T> type,Handler h){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(object,method,args)->h.call(method.getName(),args)));}
}
