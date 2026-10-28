package io.aitest.workbench;

import io.aitest.workbench.bootstrap.SchemaReadiness;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WB_MIGRATION_PASSWORD",matches=".+")
class MigrationReadinessTest {
    @TempDir Path files;
    DriverManagerDataSource source(String user,String password){return new DriverManagerDataSource(System.getenv("WB_DB_URL"),user,password);}
    DriverManagerDataSource migration(){return source(System.getenv("WB_MIGRATION_USER"),System.getenv("WB_MIGRATION_PASSWORD"));}
    DriverManagerDataSource runtime(){return source(System.getenv("WB_DB_USER"),System.getenv("WB_DB_PASSWORD"));}
    String prefix(){return "a01_"+UUID.randomUUID().toString().replace("-","").substring(0,12);}
    Flyway flyway(String history){return Flyway.configure().dataSource(migration()).locations("filesystem:"+files).table(history).baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();}
    void sql(String file,String text)throws Exception{Files.writeString(files.resolve(file),text);}
    void verify(String history,int target){SchemaReadiness.verify(runtime(),"filesystem:"+files,history,target);}
    @Test void actualPlatformHistoryIsReadyWithRuntimeReadOnlyPrivileges(){assertDoesNotThrow(()->SchemaReadiness.platform(runtime()));}
    @Test void missingPendingAndChecksumChangedHistoryCannotBeReady()throws Exception {
        String p=prefix(),history=p+"_history";sql("V1__probe.sql","CREATE TABLE "+p+"_one(id INT PRIMARY KEY) ENGINE=InnoDB;");
        assertThrows(IllegalStateException.class,()->verify(history,1));flyway(history).migrate();assertDoesNotThrow(()->verify(history,1));
        sql("V2__next.sql","CREATE TABLE "+p+"_two(id INT PRIMARY KEY) ENGINE=InnoDB;");assertThrows(IllegalStateException.class,()->verify(history,2));
        Files.delete(files.resolve("V2__next.sql"));sql("V1__probe.sql","CREATE TABLE "+p+"_one(id BIGINT PRIMARY KEY) ENGINE=InnoDB;");assertThrows(IllegalStateException.class,()->verify(history,1));
    }
    @Test void failedDdlHistoryAndFutureVersionCannotBeReadyOrRepaired()throws Exception {
        String p=prefix(),history=p+"_history";sql("V1__probe.sql","CREATE TABLE "+p+"_one(id INT PRIMARY KEY) ENGINE=InnoDB;");flyway(history).migrate();
        sql("V2__failed.sql","CREATE TABLE "+p+"_partial(id INT PRIMARY KEY) ENGINE=InnoDB; SELECT * FROM "+p+"_does_not_exist;");assertThrows(Exception.class,()->flyway(history).migrate());assertThrows(IllegalStateException.class,()->verify(history,2));
        try(var c=runtime().getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+history+" WHERE success=0")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
        String q=prefix(),other=q+"_history";Files.delete(files.resolve("V2__failed.sql"));sql("V1__probe.sql","CREATE TABLE "+q+"_one(id INT PRIMARY KEY) ENGINE=InnoDB;");flyway(other).migrate();
        sql("V2__future.sql","CREATE TABLE "+q+"_two(id INT PRIMARY KEY) ENGINE=InnoDB;");flyway(other).migrate();assertThrows(IllegalStateException.class,()->verify(other,1));
    }
    @Test void interruptedOwnMigrationProcessLeavesPartialDdlAndCannotPassReadiness()throws Exception {
        String p=prefix(),history=p+"_history";sql("V1__probe.sql","CREATE TABLE "+p+"_one(id INT PRIMARY KEY) ENGINE=InnoDB;");flyway(history).migrate();
        sql("V2__interrupt.sql","CREATE TABLE "+p+"_partial(id INT PRIMARY KEY) ENGINE=InnoDB;");
        var marker=files.resolve("checkpoint");var log=files.resolve("child.log");String executable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var child=new ProcessBuilder(executable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),MigrationProbeProcess.class.getName(),files.toString(),history,marker.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{
            assertTimeoutPreemptively(Duration.ofSeconds(15),()->{while(!Files.exists(marker)&&child.isAlive())Thread.sleep(50);assertTrue(Files.exists(marker),"Owned migration did not reach checkpoint");});
            assertEquals(Long.toString(child.pid()),Files.readString(marker));
            // Process object belongs to this test; never signal MySQL or a discovered/shared PID.
            child.destroyForcibly();assertTrue(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->verify(history,2));
            try(var c=runtime().getConnection();var s=c.createStatement()){
                try(var r=s.executeQuery("SELECT COUNT(*) FROM "+p+"_partial")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
                try(var r=s.executeQuery("SELECT COUNT(*) FROM "+history+" WHERE success=1 AND version='2'")){assertTrue(r.next());assertEquals(0,r.getInt(1));}
            }
        }finally{if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));}}
        // Prefix tables/history are retained as evidence; no clean, DROP or repair.
    }
}
