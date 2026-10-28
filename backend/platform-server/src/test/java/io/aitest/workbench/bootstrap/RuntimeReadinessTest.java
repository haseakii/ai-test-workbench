package io.aitest.workbench.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeReadinessTest {
    @TempDir Path root;
    String nonce="12345678-1234-4abc-8def-123456789abc";
    private ApplicationReadyEvent event(int port,String address,String...profiles){
        var environment=new StandardEnvironment();environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.setActiveProfiles(profiles);environment.getPropertySources().addFirst(new MapPropertySource("readiness-test",Map.of("server.address",address)));
        var context=mock(ConfigurableApplicationContext.class,withSettings().extraInterfaces(WebServerApplicationContext.class));var server=mock(WebServer.class);
        when(context.getEnvironment()).thenReturn(environment);when(((WebServerApplicationContext)context).getWebServer()).thenReturn(server);when(server.getPort()).thenReturn(port);
        return new ApplicationReadyEvent(new SpringApplication(),new String[0],context,Duration.ZERO);
    }
    private ApplicationReadyEvent event(){return event(18080,"127.0.0.1","synthetic");}
    @Test void absentOptionsAreCompletelyDisabled(){assertDoesNotThrow(()->new RuntimeReadiness("","").ready(null));}
    @Test void requiresBothOptionsAndCanonicalUuid(){
        var file=root.resolve("ready.json").toString();
        assertThrows(IllegalArgumentException.class,()->new RuntimeReadiness(file,""));assertThrows(IllegalArgumentException.class,()->new RuntimeReadiness("",nonce));
        for(String invalid:List.of("not-a-uuid","1-1-1-1-1",nonce.toUpperCase(java.util.Locale.ROOT)))
            assertThrows(IllegalArgumentException.class,()->new RuntimeReadiness(file,invalid));
    }
    @Test void rejectsRelativeAndUnnormalizedPaths(){
        assertThrows(IllegalArgumentException.class,()->new RuntimeReadiness("relative.json",nonce));
        assertThrows(IllegalArgumentException.class,()->new RuntimeReadiness(root.resolve("unused/../ready.json").toString(),nonce));
    }
    @Test void doesNotCreateMissingDirectoriesOrOverwriteExistingFiles()throws Exception{
        var missing=root.resolve("missing/ready.json");assertThrows(IllegalArgumentException.class,()->new RuntimeReadiness(missing.toString(),nonce));assertFalse(Files.exists(missing.getParent()));
        var existing=root.resolve("existing.json");Files.writeString(existing,"keep this file");assertThrows(IllegalArgumentException.class,()->new RuntimeReadiness(existing.toString(),nonce));assertEquals("keep this file",Files.readString(existing));
    }
    @Test void rejectsTargetAndAncestorSymlinks()throws Exception{
        var destination=root.resolve("destination");Files.writeString(destination,"unchanged");var link=root.resolve("ready.json");Files.createSymbolicLink(link,destination);
        assertThrows(IllegalArgumentException.class,()->new RuntimeReadiness(link.toString(),nonce));assertEquals("unchanged",Files.readString(destination));
        var actual=root.resolve("actual");Files.createDirectory(actual);var parentLink=root.resolve("link");Files.createSymbolicLink(parentLink,actual);
        assertThrows(IllegalArgumentException.class,()->new RuntimeReadiness(parentLink.resolve("ready.json").toString(),nonce));
    }
    @Test void onlyPublishesAfterReadyWithActualPortAndExactSafeProjection()throws Exception{
        var target=root.resolve("ready.json");var listener=new RuntimeReadiness(target.toString(),nonce);assertFalse(Files.exists(target));listener.ready(event(19123,"127.0.0.1","synthetic"));
        var json=JsonMapper.builder().build().readTree(Files.readString(target));assertEquals(4,json.size());assertEquals(ProcessHandle.current().pid(),json.get("pid").longValue());assertEquals(nonce,json.get("nonce").stringValue());assertEquals(19123,json.get("port").intValue());assertEquals("synthetic_only",json.get("identityMode").stringValue());
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(target));
        try(var children=Files.list(root)){assertEquals(List.of(target),children.toList());}
    }
    @Test void rejectsPublicBindingProductionAndInvalidActualPort(){
        for(var invalid:List.of(event(18080,"0.0.0.0","synthetic"),event(18080,"127.0.0.1","production"),event(18080,"127.0.0.1","synthetic","production"),event(0,"127.0.0.1","synthetic"))){
            var target=root.resolve(UUID.randomUUID()+".json");assertThrows(IllegalStateException.class,()->new RuntimeReadiness(target.toString(),nonce).ready(invalid));assertFalse(Files.exists(target));
        }
    }
    @Test void rechecksTargetAndParentAtPublicationTime()throws Exception{
        var target=root.resolve("ready.json");var listener=new RuntimeReadiness(target.toString(),nonce);var destination=root.resolve("destination");Files.writeString(destination,"unchanged");Files.createSymbolicLink(target,destination);
        assertThrows(IllegalStateException.class,()->listener.ready(event()));assertTrue(Files.isSymbolicLink(target));assertEquals("unchanged",Files.readString(destination));
    }
    @Test void duplicateReadyEventsDoNotRewriteOrCreateMoreFiles()throws Exception{
        var target=root.resolve("ready.json");var listener=new RuntimeReadiness(target.toString(),nonce);listener.ready(event());var original=Files.readString(target);listener.ready(event(19123,"127.0.0.1","synthetic"));assertEquals(original,Files.readString(target));
        try(var children=Files.list(root)){assertEquals(List.of(target),children.toList());}
    }
    @Test void unsupportedContextDoesNotPublish(){
        var target=root.resolve("ready.json");var context=mock(ConfigurableApplicationContext.class);var environment=new StandardEnvironment();environment.setActiveProfiles("synthetic");when(context.getEnvironment()).thenReturn(environment);
        var event=new ApplicationReadyEvent(new SpringApplication(),new String[0],context,Duration.ZERO);assertThrows(IllegalStateException.class,()->new RuntimeReadiness(target.toString(),nonce).ready(event));assertFalse(Files.exists(target));
    }
    @Test void legacyLinuxOwner755DirectoryKeepsPermissionsAndPrivateAck()throws Exception{
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxr-xr-x"));var target=root.resolve("ready.json");
        new RuntimeReadiness(target.toString(),nonce).ready(event());
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"),Files.getPosixFilePermissions(root));
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(target));
    }
    @Test void peerWritableReadinessDirectoryIsRejectedWithoutPermissionRepair()throws Exception{
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxrwxrwx"));
        try{assertThrows(IllegalArgumentException.class,()->new RuntimeReadiness(root.resolve("ready.json").toString(),nonce));assertFalse(Files.exists(root.resolve("ready.json")));}
        finally{Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));}
    }
}
