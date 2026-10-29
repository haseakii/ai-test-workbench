package io.aitest.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MySQL JDBC on one connection and a connection-private temporary shadow table. */
@EnabledIfEnvironmentVariable(named="WB_MYSQL_SHADOW_TEST", matches="true")
class StartOwnershipMySqlShadowTest {
    @Test void committedIntentAndReplayUseOnlyThePrivateShadow() throws Exception {
        String url=System.getenv("WB_RUNNER_DB_URL"),user=System.getenv("WB_MIGRATION_USER"),password=System.getenv("WB_MIGRATION_PASSWORD");
        assertNotNull(url); assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13306/workbench_runner?"));
        assertTrue(url.contains("sslMode=VERIFY")); assertEquals("workbench_migration",user); assertNotNull(password);
        try(Connection c=DriverManager.getConnection(url,user,password)) {
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT DATABASE(), CURRENT_USER()")) {
                assertTrue(r.next());assertEquals("workbench_runner",r.getString(1));assertTrue(r.getString(2).startsWith("workbench_migration@"));
            }
            try(Statement s=c.createStatement()) {
                String v1=Files.readString(Path.of("src/main/resources/db/migration/V1__runner_journal.sql")).replaceFirst("(?m)^--.*\\R", "").replace("CREATE TABLE", "CREATE TEMPORARY TABLE");
                s.execute(v1);
                String v2=Files.readString(Path.of("src/main/resources/db/migration/V2__single_start_intent.sql")).replaceAll("(?m)^--.*(?:\\R|$)", "");
                for(String sql:v2.split(";")) if(!sql.isBlank()) s.execute(sql);
            }
            DataSource source=new SharedConnection(c);
            var command=new SubmissionJournal.Command(new byte[16],new byte[16],new byte[]{42},new byte[32],1);
            var journal=new SubmissionJournal(source);var ownership=new StartOwnership(source);
            assertEquals(SubmissionJournal.Result.ACCEPTED,journal.decide(command,SubmissionJournal.Operation.ACCEPT));
            assertEquals(StartOwnership.Result.NEW_INTENT_COMMITTED,ownership.claim(command));
            assertEquals(StartOwnership.Result.RECONCILE,ownership.claim(command));
            assertEquals(SubmissionJournal.Result.ACCEPTED,journal.lookup(command));
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT MAX(OCTET_LENGTH(start_intent)),COUNT(*) FROM submission_journal")) {
                assertTrue(r.next()); assertEquals(32,r.getInt(1));assertEquals(1,r.getInt(2));
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
