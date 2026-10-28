package io.aitest.workbench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import io.aitest.contract.CanonicalJson;
import io.aitest.workbench.infrastructure.Store;
import java.sql.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class MySqlSemanticsTest {
    private Connection connection()throws Exception {
        Connection c=DriverManager.getConnection(System.getenv("WB_DB_URL"),System.getenv("WB_DB_USER"),System.getenv("WB_DB_PASSWORD"));
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try(var s=c.createStatement()){s.execute("SET time_zone='+00:00'");}
        return c;
    }
    private void execute(Connection c,String sql,Object...args)throws SQLException {
        try(var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.executeUpdate();}
    }
    private record Fixture(UUID conversation,UUID node){}
    private Fixture fixture(Connection c)throws Exception {
        UUID conversation=UUID.randomUUID(),node=UUID.randomUUID(),env=UUID.randomUUID();
        execute(c,"INSERT INTO config_objects(tenant_id,id,kind,name,draft_json) VALUES(?,?,?,?,?)",Store.id(Store.TENANT),Store.id(env),"environment","SQL semantic fixture","{}");
        execute(c,"INSERT INTO conversations(tenant_id,id,subject_id,environment_id,title) VALUES(?,?,?,?,?)",Store.id(Store.TENANT),Store.id(conversation),Store.id("00000000-0000-4000-8000-000000000012"),Store.id(env),"SQL semantic fixture");
        execute(c,"INSERT INTO node_runs VALUES(?,?,?,?)",Store.id(Store.TENANT),Store.id(node),Store.id(conversation),"ready");return new Fixture(conversation,node);
    }
    private void binding(Connection c,Fixture f,UUID binding)throws Exception {
        execute(c,"INSERT INTO execution_bindings(tenant_id,id,node_run_id,conversation_id,state,spec_hash) VALUES(?,?,?,?,?,?)",Store.id(Store.TENANT),Store.id(binding),Store.id(f.node()),Store.id(f.conversation()),"prepared",new byte[32]);
    }
    @Test void actualEngineIsolationTimePrecisionAndDecimal()throws Exception {
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT VERSION(),@@transaction_isolation,@@session.time_zone,@@binlog_format")){
            assertTrue(r.next());assertTrue(r.getString(1).startsWith("8.4."));assertEquals("READ-COMMITTED",r.getString(2));assertEquals("+00:00",r.getString(3));assertEquals("ROW",r.getString(4));
        }
        var old=TimeZone.getDefault();
        try(var c=connection();var s=c.prepareStatement("SELECT CAST(? AS DATETIME(6)),CAST(? AS DECIMAL(18,6))")){
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            var time=LocalDateTime.parse("2026-10-26T00:00:00.000001");s.setObject(1,time);s.setBigDecimal(2,new BigDecimal("123456789.123456"));
            try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(time,r.getObject(1,LocalDateTime.class));assertEquals(new BigDecimal("123456789.123456"),r.getBigDecimal(2));}
        }finally{TimeZone.setDefault(old);}
    }
    @Test void oneOpenBindingAndClosedHistoryAndSafeIntegerCheck()throws Exception {
        try(var c=connection()){c.setAutoCommit(false);try{
            var f=fixture(c);UUID first=UUID.randomUUID();binding(c,f,first);
            assertThrows(SQLException.class,()->binding(c,f,UUID.randomUUID()));
            execute(c,"UPDATE execution_bindings SET closed_at=CURRENT_TIMESTAMP(6),state='terminal',effect_status='verified' WHERE tenant_id=? AND id=?",Store.id(Store.TENANT),Store.id(first));
            UUID second=UUID.randomUUID();binding(c,f,second);
            execute(c,"UPDATE execution_bindings SET closed_at=CURRENT_TIMESTAMP(6),state='terminal',effect_status='verified' WHERE tenant_id=? AND id=?",Store.id(Store.TENANT),Store.id(second));
            binding(c,f,UUID.randomUUID());
            assertThrows(SQLException.class,()->execute(c,"UPDATE conversations SET revision=? WHERE tenant_id=? AND id=?",9_007_199_254_740_992L,Store.id(Store.TENANT),Store.id(f.conversation())));
        }finally{c.rollback();}}
    }
    @Test void idempotencyKeysCompareExactBytes()throws Exception {
        try(var c=connection()){c.setAutoCommit(false);try{
            for(String key:List.of("Case","case","Case "))execute(c,"INSERT INTO commands(tenant_id,id,subject_id,kind,idempotency_key,request_hash,response_json) VALUES(?,?,?,?,?,?,?)",Store.id(Store.TENANT),Store.id(UUID.randomUUID()),Store.id("00000000-0000-4000-8000-000000000012"),Store.key("mysql-probe"),Store.key(key),new byte[32],"{}");
            assertThrows(SQLException.class,()->execute(c,"INSERT INTO commands(tenant_id,id,subject_id,kind,idempotency_key,request_hash,response_json) VALUES(?,?,?,?,?,?,?)",Store.id(Store.TENANT),Store.id(UUID.randomUUID()),Store.id("00000000-0000-4000-8000-000000000012"),Store.key("mysql-probe"),Store.key("Case"),new byte[32],"{}"));
        }finally{c.rollback();}}
    }
    @Test void uuidAndCanonicalJsonRoundTripIgnoreMysqlObjectOrder()throws Exception {
        try(var c=connection()){
            UUID uuid=UUID.randomUUID();String input="{\"z\":null,\"amount\":\"1.00\",\"nested\":{\"b\":2,\"a\":1}}";
            try(var s=c.prepareStatement("SELECT BIN_TO_UUID(?),CAST(? AS JSON)")){
                s.setBytes(1,Store.id(uuid));s.setString(2,input);
                try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(uuid.toString(),r.getString(1));assertEquals(CanonicalJson.hash(input),CanonicalJson.hash(r.getString(2)));}
            }
        }
    }
    @Test void tenantCompositeForeignKeyRejectsWrongParent()throws Exception {
        try(var c=connection()){c.setAutoCommit(false);try{
            var f=fixture(c);
            assertThrows(SQLException.class,()->execute(c,"INSERT INTO messages VALUES(?,?,?,?,?,?)",Store.id(UUID.randomUUID()),Store.id(UUID.randomUUID()),Store.id(f.conversation()),Store.key("cross-tenant"),"fixture",1));
        }finally{c.rollback();}}
    }
    @Test void lockingLastSlotAllowsExactlyOneCommit()throws Exception {
        UUID subject=UUID.randomUUID();String alias="sql-probe-"+subject;
        try(var c=connection()){
            execute(c,"INSERT INTO subjects VALUES(?,?)",Store.id(subject),Store.key(alias));
            execute(c,"INSERT INTO memberships(tenant_id,subject_id,role_name,state) VALUES(?,?,?,?)",Store.id(Store.TENANT),Store.id(subject),"member","active");
            execute(c,"INSERT INTO active_slots(tenant_id,subject_id) VALUES(?,?)",Store.id(Store.TENANT),Store.id(subject));
        }
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        Callable<Integer> reserve=()->{start.await();try(var c=connection()){c.setAutoCommit(false);try(var s=c.prepareStatement("UPDATE active_slots SET occupied=occupied+1 WHERE tenant_id=? AND subject_id=? AND occupied<slot_limit")){
            s.setBytes(1,Store.id(Store.TENANT));s.setBytes(2,Store.id(subject));int changed=s.executeUpdate();c.commit();return changed;
        }catch(Exception e){c.rollback();throw e;}}};
        try{
            var a=pool.submit(reserve);var b=pool.submit(reserve);start.countDown();assertEquals(1,a.get(10,TimeUnit.SECONDS)+b.get(10,TimeUnit.SECONDS));
        }finally{
            pool.shutdownNow();try(var c=connection()){execute(c,"DELETE FROM active_slots WHERE tenant_id=? AND subject_id=?",Store.id(Store.TENANT),Store.id(subject));execute(c,"DELETE FROM memberships WHERE tenant_id=? AND subject_id=?",Store.id(Store.TENANT),Store.id(subject));execute(c,"DELETE FROM subjects WHERE id=?",Store.id(subject));}
        }
    }
}
