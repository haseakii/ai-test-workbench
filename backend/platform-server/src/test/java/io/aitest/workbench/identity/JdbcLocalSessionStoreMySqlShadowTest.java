package io.aitest.workbench.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Single connection shadow: temporary-table DDL omits FK, so this is not a V9 migration or concurrency test. */
@EnabledIfEnvironmentVariable(named="WB_MYSQL_SHADOW_TEST", matches="true")
class JdbcLocalSessionStoreMySqlShadowTest {
    @Test void sessionRespectsMembershipRevisionRevocationAndExpiry() throws Exception {
        String url=System.getenv("WB_DB_URL"), user=System.getenv("WB_MIGRATION_USER"), password=System.getenv("WB_MIGRATION_PASSWORD");
        assertNotNull(url); assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13306/workbench_platform?"));
        assertTrue(url.contains("sslMode=VERIFY")); assertEquals("workbench_migration",user); assertNotNull(password);
        try(Connection c=DriverManager.getConnection(url,user,password)) {
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT DATABASE(),CURRENT_USER()")) {
                assertTrue(r.next()); assertEquals("workbench_platform",r.getString(1)); assertTrue(r.getString(2).startsWith("workbench_migration@"));
            }
            try(Statement s=c.createStatement()) {
                s.execute("CREATE TEMPORARY TABLE memberships (tenant_id BINARY(16) NOT NULL,subject_id BINARY(16) NOT NULL,state VARCHAR(20) NOT NULL,revision BIGINT NOT NULL,PRIMARY KEY(tenant_id,subject_id)) ENGINE=InnoDB");
                String ddl=Files.readString(Path.of("src/main/resources/db/migration/V9__local_auth_sessions.sql"))
                    .replaceFirst("(?m)^--.*\\R", "")
                    .replace("CREATE TABLE", "CREATE TEMPORARY TABLE")
                    .replace(" FOREIGN KEY (tenant_id, subject_id) REFERENCES memberships(tenant_id, subject_id),\n", "");
                assertFalse(ddl.contains("FOREIGN KEY")); s.execute(ddl);
            }
            UUID tenant=UUID.randomUUID(),subject=UUID.randomUUID(); byte[] hash=new byte[32]; hash[0]=42;
            try(PreparedStatement s=c.prepareStatement("INSERT INTO memberships VALUES (?,?,?,?)")) {
                s.setBytes(1,id(tenant));s.setBytes(2,id(subject));s.setString(3,"active");s.setLong(4,1);assertEquals(1,s.executeUpdate());
            }
            var principal=new LocalSessionTokens.Principal(tenant,subject,1);
            var store=new JdbcLocalSessionStore(new SharedConnection(c));
            Instant now=Instant.parse("2026-10-28T00:00:00.123456Z"), expires=now.plusSeconds(60);
            store.create(hash,principal,expires);
            assertEquals(principal,store.resolve(hash,now).principal());
            assertNull(store.resolve(hash,expires));
            try(Statement s=c.createStatement()){assertEquals(1,s.executeUpdate("UPDATE memberships SET revision=2"));}
            assertNull(store.resolve(hash,now));
            byte[] nextHash=hash.clone(); nextHash[0]=43;
            store.create(nextHash,new LocalSessionTokens.Principal(tenant,subject,2),expires);
            assertNotNull(store.resolve(nextHash,now));
            store.revoke(nextHash); store.revoke(nextHash); assertNull(store.resolve(nextHash,now));
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
