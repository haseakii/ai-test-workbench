package io.aitest.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL temporary table on one connection: no full migration or multi-connection race claim. */
@EnabledIfEnvironmentVariable(named="WB_MYSQL_SHADOW_TEST", matches="true")
class ScopedCommandJournalMySqlShadowTest {
    @Test void unicodeReplayConflictAndDigestCollisionsRemainConservative() throws Exception {
        String url=System.getenv("WB_RUNNER_DB_URL"),user=System.getenv("WB_MIGRATION_USER"),password=System.getenv("WB_MIGRATION_PASSWORD");
        assertNotNull(url); assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13306/workbench_runner?"));
        assertTrue(url.contains("sslMode=VERIFY")); assertEquals("workbench_migration",user); assertNotNull(password);
        try(Connection c=DriverManager.getConnection(url,user,password)) {
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT DATABASE(), CURRENT_USER()")) {
                assertTrue(r.next());assertEquals("workbench_runner",r.getString(1));assertTrue(r.getString(2).startsWith("workbench_migration@"));
            }
            String ddl=Files.readString(Path.of("src/main/resources/db/migration/V3__scoped_command_intents.sql"))
                .replaceAll("(?m)^--.*(?:\\R|$)", "").replace("CREATE TABLE", "CREATE TEMPORARY TABLE");
            try(Statement s=c.createStatement()){s.execute(ddl);}
            var source=new SharedConnection(c);var clock=Clock.fixed(Instant.parse("2026-10-29T01:02:03.123456Z"),ZoneOffset.UTC);
            var journal=new ScopedCommandJournal(source,(connection,command)->true,clock);
            UUID tenant=UUID.randomUUID(),node=UUID.randomUUID(),binding=UUID.randomUUID();
            String provider=" 提供者 ",type=" ",key="中文🙂".repeat(80); byte[] spec=new byte[32],request=new byte[32];Arrays.fill(spec,(byte)1);Arrays.fill(request,(byte)2);
            var command=new ScopedCommandJournal.Command(tenant,provider,node,type,key,binding,spec,request,1);
            var first=journal.record(command);assertEquals(ScopedCommandJournal.Status.RECORDED,first.status());assertEquals(first,journal.record(command));
            byte[] changed=request.clone();changed[0]=3;
            assertEquals(ScopedCommandJournal.Status.CONFLICT,journal.record(new ScopedCommandJournal.Command(tenant,provider,node,type,key,binding,spec,changed,1)).status());
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT provider_id,command_type,idempotency_key FROM runner_command_intents")) {
                assertTrue(r.next());assertArrayEquals(provider.getBytes(StandardCharsets.UTF_8),r.getBytes(1));assertArrayEquals(type.getBytes(StandardCharsets.UTF_8),r.getBytes(2));assertArrayEquals(key.getBytes(StandardCharsets.UTF_8),r.getBytes(3));assertFalse(r.next());
            }
            UUID collisionTenant=UUID.randomUUID();var colliding=new ScopedCommandJournal(source,(connection,cmd)->true,clock,bytes->new byte[32]);
            var original=new ScopedCommandJournal.Command(collisionTenant,"provider",node,"start","key",binding,spec,request,1);
            var collisionFirst=colliding.record(original);assertEquals(ScopedCommandJournal.Status.RECORDED,collisionFirst.status());
            for(var other:new ScopedCommandJournal.Command[]{
                new ScopedCommandJournal.Command(collisionTenant,"other",node,"start","key",binding,spec,request,1),
                new ScopedCommandJournal.Command(collisionTenant,"provider",node,"close","key",binding,spec,request,1),
                new ScopedCommandJournal.Command(collisionTenant,"provider",node,"start","different",binding,spec,request,1)})
                assertEquals(ScopedCommandJournal.Status.CONFLICT,colliding.record(other).status());
            assertEquals(collisionFirst,colliding.record(original));
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM runner_command_intents")){assertTrue(r.next());assertEquals(2,r.getInt(1));}
        }
    }
    private record SharedConnection(Connection physical) implements DataSource {
        public Connection getConnection() {
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("close"))return null;
                try{return method.invoke(physical,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
        }
        public Connection getConnection(String u,String p) throws SQLException {throw new SQLFeatureNotSupportedException();}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter p){}public void setLoginTimeout(int s){}public int getLoginTimeout(){return 0;}
        public Logger getParentLogger(){return Logger.getGlobal();}public <T>T unwrap(Class<T> c) throws SQLException{throw new SQLException("unsupported");}public boolean isWrapperFor(Class<?> c){return false;}
    }
}
