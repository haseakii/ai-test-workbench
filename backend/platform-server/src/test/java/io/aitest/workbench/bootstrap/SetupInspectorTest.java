package io.aitest.workbench.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class SetupInspectorTest {
    @TempDir Path temporary;
    private Path bundle,target,setup;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @BeforeEach void files()throws Exception{
        bundle=temporary.resolve("发行包 中文 with spaces");setup=bundle.resolve("setup");Files.createDirectories(setup);target=temporary.resolve("target absent");
        Path source=Path.of("../distribution/setup").toAbsolutePath().normalize();
        for(String name:List.of("dependencies.json","bootstrap-actions.json","bootstrap.sql.template","initialize.ini.template","my.ini.template"))Files.copy(source.resolve(name),setup.resolve(name));
    }
    SetupInspector.Report inspect()throws Exception{return SetupInspector.inspect(bundle,target,"Linux");}
    void edit(String file,java.util.function.Consumer<Map<String,Object>> operation)throws Exception{
        @SuppressWarnings("unchecked") Map<String,Object> data=JSON.readValue(Files.readAllBytes(setup.resolve(file)),Map.class);operation.accept(data);Files.write(setup.resolve(file),JSON.writeValueAsBytes(data));
    }
    @Test void completePlanReportsUnknownArtifactsAndNeverCreatesTarget()throws Exception{
        var before=inventory();var report=inspect();assertTrue(report.readOnly());assertFalse(report.canInitialize());assertFalse(report.executionAuthorized());
        assertEquals("portable-private-mysql",report.recommendedRoute());assertEquals(11,report.actions().size());assertEquals(5,report.inputs().size());
        assertTrue(report.checks().stream().anyMatch(c->c.id().equals("java-files")&&c.state().equals("MISSING")));
        assertEquals(before,inventory());assertFalse(Files.exists(target));
    }
    @Test void fakeExecutablePresenceIsNotTrustedOrExecuted()throws Exception{
        for(String name:List.of("runtime/java/bin/java.exe","runtime/mysql/bin/mysqld.exe")){Path file=bundle.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,"NOT_AN_EXECUTABLE_DO_NOT_RUN");}
        var report=inspect();assertFalse(report.canInitialize());assertEquals(2,report.checks().stream().filter(c->c.id().endsWith("-files")&&c.state().equals("UNVERIFIED")).count());assertFalse(Files.exists(target));
    }
    @Test void editedApprovalFlagDoesNotEnableExecution()throws Exception{
        edit("dependencies.json",v->v.put("allowedToExecute",true));var failure=assertThrows(SetupInspector.Invalid.class,this::inspect);assertEquals("SETUP_PLAN_NOT_READONLY",failure.code);assertFalse(Files.exists(target));
    }
    @Test void editedBootstrapExecutorFlagDoesNotEnableExecution()throws Exception{
        edit("bootstrap-actions.json",v->v.put("executorIncluded",true));assertEquals("SETUP_PLAN_NOT_READONLY",assertThrows(SetupInspector.Invalid.class,this::inspect).code);
    }
    @Test void sourceAndTargetBindTheReviewHash()throws Exception{
        var first=inspect();assertEquals(first.planSha256(),inspect().planSha256());
        Files.writeString(setup.resolve("bootstrap.sql.template"),"\n-- reviewed content differs\n",StandardOpenOption.APPEND);
        assertNotEquals(first.planSha256(),inspect().planSha256());
        assertNotEquals(inspect().planSha256(),SetupInspector.inspect(bundle,target.resolve("another"),"Linux").planSha256());
    }
    @Test void existingDataIsPreservedAndNotAdoptedAsNewInstance()throws Exception{
        Files.createDirectory(target);Path existing=target.resolve("must-keep.db");Files.writeString(existing,"EXISTING_SYNTHETIC_DATA");
        var report=inspect();assertTrue(report.checks().stream().anyMatch(c->c.id().equals("new-data")&&c.state().equals("BLOCKED")));assertEquals("EXISTING_SYNTHETIC_DATA",Files.readString(existing));
    }
    @Test void symbolicLinkTargetIsRejectedWithoutTouchingLinkedData()throws Exception{
        Path real=temporary.resolve("original");Files.createDirectory(real);Files.writeString(real.resolve("keep"),"safe");Files.createSymbolicLink(target,real);
        assertEquals("SETUP_PATH_INVALID",assertThrows(SetupInspector.Invalid.class,this::inspect).code);assertEquals("safe",Files.readString(real.resolve("keep")));
    }
    @Test void changedExecutablePathCannotEscapeBundle()throws Exception{
        edit("dependencies.json",v->{@SuppressWarnings("unchecked") var components=(List<Map<String,Object>>)v.get("components");components.getFirst().put("expectedRelativeExecutable","../outside.exe");});
        assertEquals("SETUP_CATALOG_INVALID",assertThrows(SetupInspector.Invalid.class,this::inspect).code);
    }
    @Test void duplicateOrTrailingJsonIsRejected()throws Exception{
        Path file=setup.resolve("dependencies.json");String text=Files.readString(file);
        Files.writeString(file,text+" {}");assertEquals("SETUP_JSON_INVALID",assertThrows(SetupInspector.Invalid.class,this::inspect).code);
        Files.writeString(file,text.stripTrailing().replaceFirst("\\{","{\"allowedToExecute\":true,"));assertEquals("SETUP_JSON_INVALID",assertThrows(SetupInspector.Invalid.class,this::inspect).code);
    }
    @Test void missingAndOversizedInputFailBeforeAnyTargetCreation()throws Exception{
        Path file=setup.resolve("bootstrap.sql.template");Files.delete(file);assertEquals("SETUP_INPUT_MISSING",assertThrows(SetupInspector.Invalid.class,this::inspect).code);
        Files.writeString(file,"x".repeat(256*1024+1));assertEquals("SETUP_INPUT_MISSING",assertThrows(SetupInspector.Invalid.class,this::inspect).code);assertFalse(Files.exists(target));
    }
    @Test void jsonOutputIsReadableButExplicitlyNotAnInstallationSuccess()throws Exception{
        var bytes=new ByteArrayOutputStream();int result=SetupInspector.execute(new String[]{"--bundle-dir",bundle.toString(),"--runtime-dir",target.toString(),"--json"},Map.of(),new PrintStream(bytes));
        assertEquals(2,result);var report=JSON.readTree(bytes.toByteArray());assertEquals("SETUP_PREPARATION_ONLY",report.get("code").stringValue());assertFalse(report.get("canInitialize").booleanValue());assertFalse(Files.exists(target));
    }
    @Test void humanOutputExplainsActionsAndNoAutomaticChanges()throws Exception{
        var bytes=new ByteArrayOutputStream();assertEquals(2,SetupInspector.execute(new String[]{"--bundle-dir",bundle.toString(),"--runtime-dir",target.toString()},Map.of(),new PrintStream(bytes)));
        assertTrue(bytes.toString().contains("3个用户动作"));assertFalse(bytes.toString().contains("\"format\""));assertTrue(bytes.toString().contains("不自动重试或清理"));assertFalse(Files.exists(target));
    }
    @Test void executionAndSecretArgumentsAreNotAcceptedOrEchoed(){
        String secret="SHOULD_NOT_ECHO_3b7cd";var bytes=new ByteArrayOutputStream();assertEquals(2,SetupInspector.execute(new String[]{"--execute",secret},Map.of(),new PrintStream(bytes)));assertFalse(bytes.toString().contains(secret));
    }
    private Map<String,String> inventory()throws Exception{
        Map<String,String> result=new TreeMap<>();try(var stream=Files.walk(temporary)){for(Path p:stream.filter(Files::isRegularFile).toList())result.put(temporary.relativize(p).toString(),io.aitest.contract.CanonicalJson.hashBytes(Files.readAllBytes(p)));}return result;
    }
}
