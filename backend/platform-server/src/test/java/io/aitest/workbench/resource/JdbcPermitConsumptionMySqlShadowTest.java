package io.aitest.workbench.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL transaction against a connection-private temporary shadow; not a concurrency proof. */
@EnabledIfEnvironmentVariable(named="WB_MYSQL_SHADOW_TEST", matches="true")
class JdbcPermitConsumptionMySqlShadowTest {
    @Test void committedConsumptionCannotBeRepeated() throws Exception {
        String url=System.getenv("WB_DB_URL"),user=System.getenv("WB_MIGRATION_USER"),password=System.getenv("WB_MIGRATION_PASSWORD");
        assertNotNull(url); assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13306/workbench_platform?"));
        assertTrue(url.contains("sslMode=VERIFY"));assertEquals("workbench_migration",user);assertNotNull(password);
        try(Connection c=DriverManager.getConnection(url,user,password)) {
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT DATABASE(), CURRENT_USER()")) {
                assertTrue(r.next());assertEquals("workbench_platform",r.getString(1));assertTrue(r.getString(2).startsWith("workbench_migration@"));
            }
            String ddl=Files.readString(Path.of("src/main/resources/db/migration/V8__resource_operation_consumption.sql"))
                    .replaceFirst("(?m)^--.*\\R", "").replace("CREATE TABLE", "CREATE TEMPORARY TABLE");
            try(Statement s=c.createStatement()){s.execute(ddl);}
            Instant now=Instant.parse("2026-10-28T00:00:00.123456Z");
            var scope=new OperationPermitPolicy.Scope("tenant","subject","binding",1,"env","operation","a".repeat(64),"resource","tool","b".repeat(64),3);
            var permit=new OperationPermitPolicy.Permit(scope,now.minusSeconds(1),now.plusSeconds(60));
            var current=new OperationPermitPolicy.Current(true,true,false,false,scope);
            var component=new JdbcPermitConsumption(new SharedConnection(c),(connection,p)->current,Clock.fixed(now,ZoneOffset.UTC));
            assertEquals(JdbcPermitConsumption.Outcome.CONSUMED,component.consume(permit));
            assertEquals(JdbcPermitConsumption.Outcome.UNKNOWN,component.consume(permit));
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT COUNT(*), MAX(ownership_epoch), MAX(authorization_revision) FROM resource_operation_consumptions")) {
                assertTrue(r.next());assertEquals(1,r.getInt(1));assertEquals(1,r.getLong(2));assertEquals(3,r.getLong(3));
            }
        }
    }
    private record SharedConnection(Connection physical) implements DataSource {
        public Connection getConnection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("close")) return null;
                try{return method.invoke(physical,args);} catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
        }
        public Connection getConnection(String u,String p) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        public PrintWriter getLogWriter(){return null;} public void setLogWriter(PrintWriter p){} public void setLoginTimeout(int s){} public int getLoginTimeout(){return 0;}
        public Logger getParentLogger(){return Logger.getGlobal();} public <T>T unwrap(Class<T> c) throws SQLException{throw new SQLException("unsupported");} public boolean isWrapperFor(Class<?> c){return false;}
    }
}
