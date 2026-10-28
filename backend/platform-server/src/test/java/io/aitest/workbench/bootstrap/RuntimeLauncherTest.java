package io.aitest.workbench.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Offline process adapters only: no process, database connection, service or software install. */
class RuntimeLauncherTest {
    @TempDir Path root;
    final String password="fixture-private-do-not-echo";
    final String url="jdbc:mysql://127.0.0.1:13306/workbench_platform?sslMode=VERIFY_CA&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";

    class FakeOps implements RuntimeLauncher.Ops {
        Map<Long,RuntimeLauncher.Identity> identities=new HashMap<>();
        List<String> operations=new ArrayList<>();Map<String,String> childEnvironment;List<String> childCommand;
        long time;int starts,migrations;boolean autoReady=true,http=true,failStart,stopOnRequest=true;int migrationResult;
        RuntimeProbe.Result probeResult=new RuntimeProbe.Result(true,"READY","ready","schema",7);
        public RuntimeProbe.Result probe(Map<String,String> env,String stage){operations.add("probe:"+stage);return probeResult;}
        public RuntimeLauncher.Identity identity(long pid){
            if(stopOnRequest&&childEnvironment!=null&&Files.exists(Path.of(childEnvironment.get("WB_RUNTIME_CONTROL_FILE"))))identities.remove(pid);
            return identities.get(pid);
        }
        public long start(List<String> command,Map<String,String> environment,Path log)throws IOException{
            starts++;operations.add("start");childEnvironment=new HashMap<>(environment);childCommand=List.copyOf(command);
            if(failStart)throw new IOException("sensitive "+password);
            long pid=42001;identities.put(pid,new RuntimeLauncher.Identity(pid,Instant.parse("2026-10-27T00:00:00Z"),Path.of(command.getFirst()),command.subList(1,command.size())));
            if(autoReady)RuntimeFiles.writeNewPrivateFile(Path.of(environment.get("WB_RUNTIME_READY_FILE")),RuntimeLauncher.JSON.writeValueAsBytes(Map.of("nonce",environment.get("WB_RUNTIME_READY_NONCE"),"pid",pid,"port",Integer.parseInt(environment.get("WB_PORT")),"identityMode","synthetic_only")));
            return pid;
        }
        public int migration(List<String> command,Map<String,String> environment,Path log){migrations++;operations.add(command.getLast().equals("classpath:db/runner-migration")?"migration:runner":"migration:platform");assertFalse(environment.containsKey("WB_DB_PASSWORD"));assertEquals("workbench_migration",environment.get("WB_MIGRATION_USER"));return migrationResult;}
        public void checkPort(int port){operations.add("port");}
        public boolean httpReady(int port){return http;}
        public long nanos(){return time;}
        public void pause(){time+=Duration.ofSeconds(1).toNanos();}
    }
    Map<String,String> config(Path directory){return Map.of("SPRING_PROFILES_ACTIVE","synthetic","WB_DB_URL",url,"WB_DB_USER","workbench","WB_DB_PASSWORD",password,"WB_BLOB_ROOT",directory.resolve("blobs").toString(),"WB_PORT","18080");}
    RuntimeLauncher.Options options(String action,boolean initialize)throws Exception{
        Path bundle=root.resolve("中文 解包 目录"),runtime=root.resolve("私有 运行");Files.createDirectories(bundle.resolve("app"));
        if(!Files.exists(bundle.resolve("app/platform-server.jar")))try(var jar=new JarOutputStream(Files.newOutputStream(bundle.resolve("app/platform-server.jar")))){
            for(String name:List.of("BOOT-INF/classes/io/aitest/workbench/bootstrap/RuntimeProbe.class","BOOT-INF/classes/io/aitest/workbench/bootstrap/RuntimeReadiness.class","BOOT-INF/classes/io/aitest/workbench/bootstrap/RuntimeLifecycleControl.class","BOOT-INF/classes/static/index.html","BOOT-INF/classes/db/runner-migration/V1__runner_journal.sql")){jar.putNextEntry(new JarEntry(name));jar.write(new byte[]{1});jar.closeEntry();}
        }
        Path config=root.resolve("runtime-private.json"),platform=root.resolve("platform-private.json"),runner=root.resolve("runner-private.json");
        if(!Files.exists(config))RuntimeFiles.writeNewPrivateFile(config,RuntimeLauncher.JSON.writeValueAsBytes(config(runtime)));
        if(!Files.exists(platform))RuntimeFiles.writeNewPrivateFile(platform,RuntimeLauncher.JSON.writeValueAsBytes(Map.of("WB_DB_URL",url,"WB_MIGRATION_USER","workbench_migration","WB_MIGRATION_PASSWORD",password)));
        if(!Files.exists(runner))RuntimeFiles.writeNewPrivateFile(runner,RuntimeLauncher.JSON.writeValueAsBytes(Map.of("WB_DB_URL",url.replace("/workbench_platform?","/workbench_runner?"),"WB_MIGRATION_USER","workbench_migration","WB_MIGRATION_PASSWORD",password)));
        return new RuntimeLauncher.Options(action,bundle,runtime,config,platform,runner,initialize);
    }
    RuntimeLauncher.Options action(RuntimeLauncher.Options o,String action){return new RuntimeLauncher.Options(action,o.bundle(),o.directory(),o.config(),o.platformMigration(),o.runnerMigration(),false);}
    RuntimeLauncher.Failure rejected(String code,RuntimeLauncher.Work<?> work){var f=assertThrows(RuntimeLauncher.Failure.class,work::run);assertEquals(code,f.code);assertFalse(f.getMessage().contains(password));assertFalse(f.getMessage().contains(url));return f;}
    Map<String,String> hostile(){return Map.of("JAVA_TOOL_OPTIONS","-javaagent:untrusted","_JAVA_OPTIONS","-Dserver.address=0.0.0.0","JDK_JAVA_OPTIONS","-Dspring.profiles.active=production","CLASSPATH","bad","LOADER_PATH","bad","MYSQL_PWD","secret","WB_MIGRATION_PASSWORD","privileged","SPRING_APPLICATION_JSON","bad","HTTPS_PROXY","http://proxy.invalid:8080");}

    @Test void optionsUseLocalAppDataAndKeepChineseSpaceBundlePaths(){
        var value=RuntimeLauncher.options(new String[]{"doctor","--bundle-dir",root.resolve("中文 空格").toString()},Map.of("LOCALAPPDATA",root.resolve("用户 私有").toString()));
        assertEquals(root.resolve("用户 私有/AiTestWorkbench/synthetic"),value.directory());assertEquals(root.resolve("中文 空格/app/platform-server.jar"),value.jar());assertEquals(value.directory().resolve("runtime-private.json"),value.config());
    }
    @Test void unknownDuplicateFlagsAndSecretParametersAreRejected(){
        for(String[] args:List.of(new String[]{"doctor","--password",password},new String[]{"doctor","--bundle-dir","one","--bundle-dir","two"},new String[]{"doctor","--config"},new String[]{"status","--initialize"}))rejected("ARGUMENTS",()->RuntimeLauncher.options(args,Map.of()));
    }
    @Test void helpDoesNotReadPrivateConfigurationOrCreateDirectories(){
        var out=new ByteArrayOutputStream();assertEquals(0,RuntimeLauncher.execute(new String[]{"--help"},Map.of("LOCALAPPDATA",root.resolve("absent").toString()),new PrintStream(out, true, StandardCharsets.UTF_8)));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Java21"));assertFalse(Files.exists(root.resolve("absent")));
    }
    @Test void environmentDropsAgentsLoaderOverridesAndOtherDatabaseCredentials(){
        Map<String,String> config=config(root);var result=RuntimeLauncher.environment(hostile(),config,false);
        for(String key:hostile().keySet())if(!key.equals("HTTPS_PROXY"))assertFalse(result.containsKey(key),key);
        assertEquals("http://proxy.invalid:8080",result.get("HTTPS_PROXY"));assertEquals(password,result.get("WB_DB_PASSWORD"));
        var migration=RuntimeLauncher.environment(config,Map.of("WB_DB_URL",url,"WB_MIGRATION_USER","workbench_migration","WB_MIGRATION_PASSWORD",password),true);
        assertFalse(migration.containsKey("WB_DB_PASSWORD"));assertFalse(migration.containsKey("WB_BLOB_ROOT"));
    }
    @Test void existingAccountRolesCannotBeSwappedOrPointAtRealTargets(){
        for(Map<String,String> delta:List.of(Map.of("WB_DB_USER","root"),Map.of("WB_DB_USER","workbench_migration"),Map.of("SPRING_PROFILES_ACTIVE","production"),Map.of("WB_DB_URL",url.replace("127.0.0.1","192.0.2.1")),Map.of("WB_DB_URL",url.replace("VERIFY_CA","DISABLED")),Map.of("WB_DB_URL",url.replace("connectionTimeZone=UTC","connectionTimeZone=Europe/Paris")))){
            Map<String,String> value=new HashMap<>(config(root));value.putAll(delta);assertThrows(RuntimeLauncher.Failure.class,()->RuntimeLauncher.validate(value,false,"workbench_platform"));
        }
    }
    @Test void duplicateUnknownAndNonStringPrivateValuesAreRejected()throws Exception{
        var o=options("doctor",false);String original=Files.readString(o.config());
        Files.writeString(o.config(),original.substring(0,original.length()-1)+",\"WB_DB_PASSWORD\":\"secret\"}");rejected("CONFIG_INVALID",()->RuntimeLauncher.configuration(o.config(),false));
        Files.writeString(o.config(),"{\"WB_DB_PASSWORD\":12}");rejected("CONFIG_KEYS",()->RuntimeLauncher.configuration(o.config(),false));
        Files.writeString(o.config(),original.replace(password,"REPLACE_PASSWORD"));rejected("CONFIG_VALUES",()->RuntimeLauncher.configuration(o.config(),false));
    }
    @Test void readablePrivateConfigIsRejectedWithoutChangingPermissions()throws Exception{
        var o=options("doctor",false);Files.setPosixFilePermissions(o.config(),PosixFilePermissions.fromString("rw-r--r--"));rejected("CONFIG_PERMISSIONS",()->RuntimeLauncher.configuration(o.config(),false));assertEquals(PosixFilePermissions.fromString("rw-r--r--"),Files.getPosixFilePermissions(o.config()));
    }
    @Test void missingConfigErrorNeverPrintsInputAndCreatesNoRuntime()throws Exception{
        var o=options("doctor",false);Files.delete(o.config());rejected("CONFIG_MISSING",()->RuntimeLauncher.run(o,new FakeOps(),Map.of()));assertFalse(Files.exists(o.directory()));
    }
    @Test void doctorUsesReadOnlySchemaProbeAndCreatesNoRuntime()throws Exception{
        var o=options("doctor",false);FakeOps ops=new FakeOps();assertEquals("PREFLIGHT_PASSED",RuntimeLauncher.run(o,ops,Map.of()).code());assertEquals(List.of("port","probe:schema"),ops.operations);assertFalse(Files.exists(o.directory()));assertEquals(0,ops.starts);
    }
    @Test void doctorDatabaseFailureDoesNotWriteRuntimeOrLogs()throws Exception{
        var o=options("doctor",false);FakeOps ops=new FakeOps();ops.probeResult=new RuntimeProbe.Result(false,"DATABASE_AUTH","数据库认证失败。","schema",null);rejected("DATABASE_AUTH",()->RuntimeLauncher.run(o,ops,Map.of()));assertFalse(Files.exists(o.directory()));
    }
    @Test void stoppedStatusAndStopDoNotRequireCredentialsOrCreateRuntime()throws Exception{
        var o=options("status",false);Files.delete(o.config());FakeOps ops=new FakeOps();assertEquals("STOPPED",RuntimeLauncher.run(o,ops,Map.of()).code());assertEquals("STOPPED",RuntimeLauncher.run(action(o,"stop"),ops,Map.of()).code());assertFalse(Files.exists(o.directory()));
    }
    @Test void upStartsOneFixedJarAndPublishesNoPrivilegedCredential()throws Exception{
        var o=options("up",false);FakeOps ops=new FakeOps();var result=RuntimeLauncher.run(o,ops,hostile());assertEquals("READY",result.code());assertEquals(1,ops.starts);assertEquals(0,ops.migrations);
        assertEquals(RuntimeLauncher.sha(o.jar()),result.sha256());assertTrue(ops.childCommand.get(2).contains(result.sha256()));assertFalse(ops.childEnvironment.containsKey("WB_MIGRATION_PASSWORD"));assertFalse(ops.childEnvironment.containsKey("JAVA_TOOL_OPTIONS"));
        assertEquals("http://proxy.invalid:8080",ops.childEnvironment.get("HTTPS_PROXY"));assertTrue(Files.exists(Path.of(ops.childEnvironment.get("WB_RUNTIME_READY_FILE"))));
    }
    @Test void repeatedUpKeepsOriginalPidAndNeverReinitializes()throws Exception{
        var o=options("up",false);FakeOps ops=new FakeOps();var first=RuntimeLauncher.run(o,ops,Map.of());var again=RuntimeLauncher.run(o,ops,Map.of());assertEquals("ALREADY_READY",again.code());assertEquals(first.pid(),again.pid());assertEquals(1,ops.starts);assertEquals(0,ops.migrations);
    }
    @Test void explicitInitializationOrdersDatabasePlatformRunnerSchemaThenOneStart()throws Exception{
        var o=options("up",true);FakeOps ops=new FakeOps();assertEquals("READY",RuntimeLauncher.run(o,ops,Map.of()).code());assertEquals(List.of("probe:database","port","migration:platform","migration:runner","probe:schema","start"),ops.operations);assertEquals(2,ops.migrations);
    }
    @Test void runningProcessCannotBeMigrated()throws Exception{
        var o=options("up",false);FakeOps ops=new FakeOps();RuntimeLauncher.run(o,ops,Map.of());rejected("RUNNING_INITIALIZE_REFUSED",()->RuntimeLauncher.run(new RuntimeLauncher.Options("up",o.bundle(),o.directory(),o.config(),o.platformMigration(),o.runnerMigration(),true),ops,Map.of()));assertEquals(0,ops.migrations);assertEquals(1,ops.starts);
    }
    @Test void failedMigrationDoesNotStartOrDeleteOriginalFiles()throws Exception{
        var o=options("up",true);FakeOps ops=new FakeOps();ops.migrationResult=1;RuntimeFiles.createPrivateDirectory(o.directory());Path preserve=o.directory().resolve("preserve.txt");RuntimeFiles.writeNewPrivateFile(preserve,"retained".getBytes(StandardCharsets.UTF_8));
        rejected("MIGRATION_FAILED",()->RuntimeLauncher.run(o,ops,Map.of()));assertEquals(0,ops.starts);assertEquals(1,ops.migrations);assertEquals("retained",Files.readString(preserve));assertFalse(Files.exists(o.metadata()));
    }
    @Test void creationIOExceptionKeepsUnknownIntentAndFencesTheNextUp()throws Exception{
        var o=options("up",false);FakeOps ops=new FakeOps();ops.failStart=true;rejected("START_UNCONFIRMED",()->RuntimeLauncher.run(o,ops,Map.of()));assertTrue(Files.exists(o.metadata()));var m=RuntimeLauncher.JSON.readTree(Files.readAllBytes(o.metadata()));assertEquals(0,m.path("pid").longValue());assertEquals("launching",m.path("launchState").stringValue());
        rejected("PROCESS_OWNERSHIP_UNCERTAIN",()->RuntimeLauncher.run(o,ops,Map.of()));assertEquals(1,ops.starts);
    }
    @Test void unconfirmedReadinessRetainsOriginalLiveProcessWithoutReplacement()throws Exception{
        var o=options("up",false);FakeOps ops=new FakeOps();ops.autoReady=false;rejected("START_UNCONFIRMED",()->RuntimeLauncher.run(o,ops,Map.of()));assertTrue(Files.exists(o.metadata()));assertEquals(1,ops.starts);assertEquals(1,ops.identities.size());rejected("NOT_READY",()->RuntimeLauncher.run(action(o,"status"),ops,Map.of()));
    }
    @Test void wrongNoncePortOrPidCanNeverReportReady()throws Exception{
        var o=options("up",false);FakeOps ops=new FakeOps();RuntimeLauncher.run(o,ops,Map.of());Path marker=Path.of(ops.childEnvironment.get("WB_RUNTIME_READY_FILE"));String original=Files.readString(marker);
        for(String wrong:List.of(original.replace("synthetic_only","production"),original.replace("42001","42002"),original.replace("18080","18081"),original.replace(ops.childEnvironment.get("WB_RUNTIME_READY_NONCE"),UUID.randomUUID().toString()))){Files.writeString(marker,wrong);rejected("NOT_READY",()->RuntimeLauncher.run(action(o,"status"),ops,Map.of()));}
    }
    @Test void recycledPidStartOrCommandCannotBeStopped()throws Exception{
        var o=options("up",false);FakeOps ops=new FakeOps();RuntimeLauncher.run(o,ops,Map.of());var first=ops.identities.get(42001L);ops.identities.put(first.pid(),new RuntimeLauncher.Identity(first.pid(),first.startInstant().plusSeconds(1),first.executable(),first.arguments()));rejected("PROCESS_OWNERSHIP_UNCERTAIN",()->RuntimeLauncher.run(action(o,"stop"),ops,Map.of()));assertFalse(Files.exists(Path.of(ops.childEnvironment.get("WB_RUNTIME_CONTROL_FILE"))));
    }
    @Test void missingWindowsProcessInformationRefusesStopInsteadOfGuessing()throws Exception{
        var o=options("up",false);FakeOps ops=new FakeOps();RuntimeLauncher.run(o,ops,Map.of());ops.identities.put(42001L,new RuntimeLauncher.Identity(42001L,null,null,null));rejected("PROCESS_OWNERSHIP_UNCERTAIN",()->RuntimeLauncher.run(action(o,"stop"),ops,Map.of()));assertFalse(Files.exists(Path.of(ops.childEnvironment.get("WB_RUNTIME_CONTROL_FILE"))));
    }
    @Test void stopWritesOnlyOriginalBoundRequestAndRetainsAllEvidence()throws Exception{
        var o=options("up",false);FakeOps ops=new FakeOps();RuntimeLauncher.run(o,ops,Map.of());byte[] before=Files.readAllBytes(o.metadata());assertEquals("STOPPED",RuntimeLauncher.run(action(o,"stop"),ops,Map.of()).code());Path request=Path.of(ops.childEnvironment.get("WB_RUNTIME_CONTROL_FILE"));var body=RuntimeLauncher.JSON.readTree(Files.readAllBytes(request));assertEquals(3,body.size());assertEquals(42001,body.path("pid").longValue());assertEquals("stop",body.path("action").stringValue());assertEquals(ops.childEnvironment.get("WB_RUNTIME_READY_NONCE"),body.path("nonce").stringValue());assertArrayEquals(before,Files.readAllBytes(o.metadata()));assertTrue(Files.exists(Path.of(ops.childCommand.get(2))));assertEquals(1,ops.starts);
    }
    @Test void unknownStopNeverEscalatesToForceKillOrStartsAnotherProcess()throws Exception{
        var o=options("up",false);FakeOps ops=new FakeOps();ops.stopOnRequest=false;RuntimeLauncher.run(o,ops,Map.of());rejected("STOP_UNCONFIRMED",()->RuntimeLauncher.run(action(o,"stop"),ops,Map.of()));assertEquals(1,ops.starts);assertEquals(1,ops.identities.size());assertTrue(Files.exists(Path.of(ops.childEnvironment.get("WB_RUNTIME_CONTROL_FILE"))));
    }
    @Test void actualDirectoryLockBlocksASecondOperation()throws Exception{
        Path directory=root.resolve("lock");RuntimeFiles.createPrivateDirectory(directory);assertEquals("outer",RuntimeLauncher.locked(directory,()->{rejected("OPERATION_BUSY",()->RuntimeLauncher.locked(directory,()->"inner"));return "outer";}));
    }
    @Test void configurationRecordsNeverDisclosePrivateMetadata(){
        var value=new RuntimeLauncher.Metadata(1,"time","java","private-path",password,"nonce",18080,"unknown");assertFalse(value.toString().contains(password));
    }
    @Test void configurationRejectsTrailingJsonObjectWithoutCreatingRuntime()throws Exception{
        var o=options("doctor",false);Files.writeString(o.config(),Files.readString(o.config())+" {}");rejected("CONFIG_INVALID",()->RuntimeLauncher.run(o,new FakeOps(),Map.of()));assertFalse(Files.exists(o.directory()));
    }
    @Test void readinessRejectsStringFractionalAndTrailingNumericClaims()throws Exception{
        var o=options("up",false);FakeOps ops=new FakeOps();RuntimeLauncher.run(o,ops,Map.of());Path marker=Path.of(ops.childEnvironment.get("WB_RUNTIME_READY_FILE"));String original=Files.readString(marker);
        for(String wrong:List.of(original.replace("\"pid\":42001","\"pid\":\"42001\""),original.replace("\"pid\":42001","\"pid\":42001.5"),original.replace("\"port\":18080","\"port\":\"18080\""),original.replace("\"port\":18080","\"port\":18080.5"),original+" {}")){
            assertNotEquals(original,wrong);Files.writeString(marker,wrong);rejected("NOT_READY",()->RuntimeLauncher.run(action(o,"status"),ops,Map.of()));
        }
    }
    @Test void metadataRejectsNoncanonicalNonceCoercionUnknownStateAndTrailingJson()throws Exception{
        var o=options("up",false);FakeOps ops=new FakeOps();RuntimeLauncher.run(o,ops,Map.of());String original=Files.readString(o.metadata()),nonce=ops.childEnvironment.get("WB_RUNTIME_READY_NONCE");
        for(String wrong:List.of(original.replace(nonce,nonce.toUpperCase(Locale.ROOT)),original.replace("\"pid\":42001","\"pid\":\"42001\""),original.replace("\"pid\":42001","\"pid\":42001.5"),original.replace("\"launchState\":\"started\"","\"launchState\":\"finished\""),original+" {}")){
            assertNotEquals(original,wrong);Files.writeString(o.metadata(),wrong);rejected("PROCESS_OWNERSHIP_UNCERTAIN",()->RuntimeLauncher.run(action(o,"stop"),ops,Map.of()));assertFalse(Files.exists(Path.of(ops.childEnvironment.get("WB_RUNTIME_CONTROL_FILE"))));
        }
    }
}
