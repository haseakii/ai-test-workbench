package io.aitest.workbench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import io.aitest.workbench.infrastructure.Store;

@EnabledIfEnvironmentVariable(named="WB_RUNNER_DB_URL",matches="jdbc:mysql:.*")
class DatabasePrivilegesTest {
    private Connection connect(String url,String user,String password)throws Exception{return DriverManager.getConnection(url,user,password);}
    @Test void platformRuntimeCannotDdlOrReadRunner()throws Exception {
        try(var c=connect(System.getenv("WB_DB_URL"),System.getenv("WB_DB_USER"),System.getenv("WB_DB_PASSWORD"));var s=c.createStatement()){
            try(var r=s.executeQuery("SELECT COUNT(*) FROM conversations")){assertTrue(r.next());}
            assertThrows(SQLException.class,()->s.execute("CREATE TEMPORARY TABLE permission_probe(id INT)"));
            assertThrows(SQLException.class,()->s.executeQuery("SELECT * FROM workbench_runner.submission_journal LIMIT 0"));
            assertThrows(SQLException.class,()->s.executeQuery("SELECT user FROM mysql.user LIMIT 0"));
        }
    }
    @Test void runnerRuntimeOnlyReadsAndWritesRunnerJournal()throws Exception {
        try(var c=connect(System.getenv("WB_RUNNER_DB_URL"),"workbench_runner",System.getenv("WB_RUNNER_PASSWORD"));var s=c.createStatement()){
            c.setAutoCommit(false);
            try(var insert=c.prepareStatement("INSERT INTO submission_journal VALUES(?,?,?,?,?,?)")){
                insert.setBytes(1,Store.id(UUID.randomUUID()));insert.setBytes(2,Store.id(UUID.randomUUID()));insert.setBytes(3,Store.key(UUID.randomUUID().toString()));insert.setBytes(4,new byte[32]);insert.setString(5,"synthetic_probe");insert.setLong(6,1);assertEquals(1,insert.executeUpdate());
            }
            assertThrows(SQLException.class,()->s.execute("CREATE TEMPORARY TABLE permission_probe(id INT)"));
            assertThrows(SQLException.class,()->s.executeQuery("SELECT * FROM workbench_platform.conversations LIMIT 0"));
            c.rollback();
        }
    }
    @Test void migrationIdentityCannotReadMysqlAdministration()throws Exception {
        try(var c=connect(System.getenv("WB_DB_URL"),System.getenv("WB_MIGRATION_USER"),System.getenv("WB_MIGRATION_PASSWORD"));var s=c.createStatement()){
            assertThrows(SQLException.class,()->s.executeQuery("SELECT user FROM mysql.user LIMIT 0"));
            try(var r=s.executeQuery("SHOW GRANTS")){int grants=0;while(r.next()){String grant=r.getString(1);assertTrue(grant.contains("USAGE ON *.*")||grant.contains("workbench_platform")||grant.contains("workbench_runner"));assertFalse(grant.contains("WITH GRANT OPTION"));grants++;}assertEquals(3,grants);}
        }
    }
}
