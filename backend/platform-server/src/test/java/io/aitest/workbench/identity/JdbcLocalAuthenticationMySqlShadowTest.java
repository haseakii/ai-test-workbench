package io.aitest.workbench.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** One real MySQL connection, temporary shadows only. FK is omitted because MySQL disallows it on temporary tables. */
@EnabledIfEnvironmentVariable(named="WB_MYSQL_SHADOW_TEST", matches="true")
class JdbcLocalAuthenticationMySqlShadowTest {
    @Test void accountVerificationAndRateLimitsUseOnlyTemporaryRows() throws Exception {
        String url=System.getenv("WB_DB_URL"),user=System.getenv("WB_MIGRATION_USER"),password=System.getenv("WB_MIGRATION_PASSWORD");
        assertNotNull(url); assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13306/workbench_platform?"));
        assertTrue(url.contains("sslMode=VERIFY")); assertEquals("workbench_migration",user); assertNotNull(password);
        try(Connection c=DriverManager.getConnection(url,user,password)) {
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT DATABASE(),CURRENT_USER()")) {
                assertTrue(r.next()); assertEquals("workbench_platform",r.getString(1)); assertTrue(r.getString(2).startsWith("workbench_migration@"));
            }
            try(Statement s=c.createStatement()) {
                s.execute("CREATE TEMPORARY TABLE memberships (tenant_id BINARY(16) NOT NULL,subject_id BINARY(16) NOT NULL,state VARCHAR(20) NOT NULL,revision BIGINT NOT NULL,PRIMARY KEY(tenant_id,subject_id)) ENGINE=InnoDB");
                String ddl=Files.readString(Path.of("src/main/resources/db/migration/V10__local_accounts_and_login_limits.sql"))
                    .replaceAll("(?m)^--.*(?:\\R|$)", "")
                    .replace("CREATE TABLE", "CREATE TEMPORARY TABLE")
                    .replace(" FOREIGN KEY (tenant_id, subject_id) REFERENCES memberships(tenant_id, subject_id)\n", "")
                    .replace(" FOREIGN KEY (tenant_id) REFERENCES tenants(id)\n", "")
                    .replace(",\n) ENGINE=InnoDB", "\n) ENGINE=InnoDB");
                assertFalse(ddl.contains("FOREIGN KEY"));
                for(String sql:ddl.split(";")) if(!sql.isBlank()) s.execute(sql);
            }
            UUID tenant=UUID.randomUUID(),subject=UUID.randomUUID();
            try(PreparedStatement s=c.prepareStatement("INSERT INTO memberships VALUES(?,?,?,?)")) {
                s.setBytes(1,id(tenant));s.setBytes(2,id(subject));s.setString(3,"active");s.setLong(4,1);assertEquals(1,s.executeUpdate());
            }
            String secret="synthetic test password",hash=new LocalPasswordCodec().hash(secret);
            try(PreparedStatement s=c.prepareStatement("INSERT INTO local_auth_accounts VALUES(?,?,?,?,?,?)")) {
                s.setBytes(1,id(tenant));s.setBytes(2,id(subject));s.setBytes(3,"tester".getBytes(StandardCharsets.UTF_8));s.setBytes(4,hash.getBytes(StandardCharsets.UTF_8));s.setLong(5,1);s.setBoolean(6,true);assertEquals(1,s.executeUpdate());
            }
            Instant now=Instant.parse("2026-10-28T00:00:00.123456Z");
            var auth=new JdbcLocalAuthentication(new SharedConnection(c),Clock.fixed(now,ZoneOffset.UTC));
            var first=auth.verify(tenant,"tester","127.0.0.1",secret);
            assertEquals(JdbcLocalAuthentication.Status.VERIFIED,first.status());assertEquals(subject,first.verified().principal().subject());
            assertEquals(JdbcLocalAuthentication.Status.DENIED,auth.verify(tenant,"tester","127.0.0.1","synthetic incorrect password").status());
            try(Statement s=c.createStatement()){assertEquals(1,s.executeUpdate("UPDATE memberships SET state='suspended',revision=2"));}
            assertEquals(JdbcLocalAuthentication.Status.DENIED,auth.verify(tenant,"tester","127.0.0.1",secret).status());
            try(Statement s=c.createStatement()){assertEquals(1,s.executeUpdate("UPDATE memberships SET state='active',revision=3"));}
            for(int i=0;i<5;i++)assertEquals(JdbcLocalAuthentication.Status.VERIFIED,auth.verify(tenant,"tester","127.0.0.1",secret).status());
            assertEquals(JdbcLocalAuthentication.Status.DENIED,auth.verify(tenant,"tester","127.0.0.1",secret).status());
            var later=new JdbcLocalAuthentication(new SharedConnection(c),Clock.fixed(now.plusSeconds(60),ZoneOffset.UTC));
            var afterWindow=later.verify(tenant,"tester","127.0.0.1",secret);
            assertEquals(JdbcLocalAuthentication.Status.VERIFIED,afterWindow.status());assertEquals(3,afterWindow.verified().principal().authorizationRevision());
        }
    }
    private static byte[] id(UUID v){return ByteBuffer.allocate(16).putLong(v.getMostSignificantBits()).putLong(v.getLeastSignificantBits()).array();}
    private record SharedConnection(Connection physical) implements DataSource {
        public Connection getConnection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("close")) return null;
                try{return method.invoke(physical,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
        }
        public Connection getConnection(String u,String p) throws SQLException {throw new SQLFeatureNotSupportedException();}
        public PrintWriter getLogWriter(){return null;} public void setLogWriter(PrintWriter p){} public void setLoginTimeout(int s){} public int getLoginTimeout(){return 0;}
        public Logger getParentLogger(){return Logger.getGlobal();} public <T>T unwrap(Class<T> c) throws SQLException{throw new SQLException("unsupported");} public boolean isWrapperFor(Class<?> c){return false;}
    }
}
