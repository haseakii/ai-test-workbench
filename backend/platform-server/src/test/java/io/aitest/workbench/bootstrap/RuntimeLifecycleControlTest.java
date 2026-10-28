package io.aitest.workbench.bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeLifecycleControlTest {
    @TempDir Path root;
    String nonce="12345678-1234-4abc-8def-123456789abc";
    long pid=12345;
    AtomicInteger exits=new AtomicInteger();
    Path ready(){return root.resolve("ready.json");}
    Path control(){return root.resolve("control.json");}
    RuntimeLifecycleControl listener(){return new RuntimeLifecycleControl(control().toString(),nonce,ready().toString(),pid,Runnable::run,exits::incrementAndGet);}
    ApplicationReadyEvent event(ConfigurableApplicationContext context,String address,String...profiles){
        var env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);env.setActiveProfiles(profiles);
        env.getPropertySources().addFirst(new MapPropertySource("private-control",Map.of("server.address",address)));when(context.getEnvironment()).thenReturn(env);
        return new ApplicationReadyEvent(new SpringApplication(),new String[0],context,Duration.ZERO);
    }
    ConfigurableApplicationContext arm(RuntimeLifecycleControl listener){var context=mock(ConfigurableApplicationContext.class);listener.ready(event(context,"127.0.0.1","synthetic"));return context;}
    void write(Path file,String json)throws Exception{RuntimeFiles.writeNewPrivateFile(file,json.getBytes(StandardCharsets.UTF_8));}
    void acknowledge()throws Exception{write(ready(),"{\"pid\":"+pid+",\"nonce\":\""+nonce+"\",\"port\":18080,\"identityMode\":\"synthetic_only\"}");}
    String request(){return "{\"nonce\":\""+nonce+"\",\"pid\":"+pid+",\"action\":\"stop\"}";}
    @Test void absentControlLeavesExistingLinuxReadinessConfigurationDisabled(){
        var listener=new RuntimeLifecycleControl("",nonce,ready().toString(),pid,Runnable::run,exits::incrementAndGet);
        assertDoesNotThrow(()->listener.ready(null));assertDoesNotThrow(listener::poll);assertEquals(0,exits.get());
    }
    @Test void explicitControlRequiresMatchingPrivateRunScopeAndCanonicalNonce()throws Exception{
        for(String bad:List.of("","invalid",nonce.toUpperCase(Locale.ROOT)))assertThrows(IllegalArgumentException.class,()->new RuntimeLifecycleControl(control().toString(),bad,ready().toString(),pid,Runnable::run,exits::incrementAndGet));
        assertThrows(IllegalArgumentException.class,()->new RuntimeLifecycleControl("relative.json",nonce,ready().toString(),pid,Runnable::run,exits::incrementAndGet));
        assertThrows(IllegalArgumentException.class,()->new RuntimeLifecycleControl(control().toString(),nonce,control().toString(),pid,Runnable::run,exits::incrementAndGet));
        assertThrows(IllegalArgumentException.class,()->new RuntimeLifecycleControl(control().toString(),nonce,root.resolve("other/ready.json").toString(),pid,Runnable::run,exits::incrementAndGet));
    }
    @Test void rejectsExistingControlWithoutOverwritingEvidence()throws Exception{
        write(control(),"original");assertThrows(IllegalArgumentException.class,this::listener);assertEquals("original",Files.readString(control()));
    }
    @Test void controlRequiresPrivateParentWithoutRepairingPermissions()throws Exception{
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IllegalArgumentException.class,this::listener);assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"),Files.getPosixFilePermissions(root));
    }
    @Test void requestBeforeApplicationReadyDoesNotCloseAnyContext()throws Exception{
        var listener=listener();acknowledge();write(control(),request());listener.poll();assertEquals(0,exits.get());
    }
    @Test void validOwnedRequestClosesBeforeExitExactlyOnceAndPreservesAllEvidence()throws Exception{
        var listener=listener();var context=arm(listener);acknowledge();write(control(),request());
        doAnswer(invocation->{assertEquals(0,exits.get());return null;}).when(context).close();listener.poll();listener.poll();
        verify(context,times(1)).close();assertEquals(1,exits.get());assertEquals(request(),Files.readString(control()));assertTrue(Files.exists(ready()));
    }
    @Test void noRequestMeansNoEffect()throws Exception{
        var listener=listener();var context=arm(listener);acknowledge();listener.poll();verify(context,never()).close();assertEquals(0,exits.get());
    }
    @Test void wrongNoncePidActionExtraOrDuplicateFieldsCannotStopAnything()throws Exception{
        var variants=List.of(request().replace(nonce,UUID.randomUUID().toString()),request().replace("12345","12346"),request().replace("stop","restart"),request().replace("12345","\"12345\""),request().replace("12345","12345.0"),request().replace("}",",\"extra\":true}"),request().replace("}",",\"nonce\":\""+nonce+"\"}"),request()+"{}");
        for(String invalid:variants){
            Path run=Files.createDirectory(root.resolve(UUID.randomUUID().toString()));Files.setPosixFilePermissions(run,PosixFilePermissions.fromString("rwx------"));
            var listener=new RuntimeLifecycleControl(run.resolve("control.json").toString(),nonce,run.resolve("ready.json").toString(),pid,Runnable::run,exits::incrementAndGet);var context=arm(listener);
            write(run.resolve("ready.json"),"{\"pid\":"+pid+",\"nonce\":\""+nonce+"\",\"port\":18080,\"identityMode\":\"synthetic_only\"}");write(run.resolve("control.json"),invalid);
            assertThrows(IllegalStateException.class,listener::poll);verify(context,never()).close();assertEquals(0,exits.get());
        }
    }
    @Test void forgedOrMissingReadinessCannotAuthorizeStop()throws Exception{
        var listener=listener();var context=arm(listener);write(control(),request());assertThrows(IllegalStateException.class,listener::poll);verify(context,never()).close();
        write(ready(),"{\"pid\":"+(pid+1)+",\"nonce\":\""+nonce+"\",\"port\":18080,\"identityMode\":\"synthetic_only\"}");assertThrows(IllegalStateException.class,listener::poll);verify(context,never()).close();assertEquals(0,exits.get());
    }
    @Test void symlinkOrBroadlyReadableRequestIsNotConsumed()throws Exception{
        var listener=listener();var context=arm(listener);acknowledge();var source=root.resolve("source");write(source,request());Files.createSymbolicLink(control(),source);
        assertThrows(IllegalStateException.class,listener::poll);verify(context,never()).close();Files.delete(control());
        write(control(),request());Files.setPosixFilePermissions(control(),PosixFilePermissions.fromString("rw-r--r--"));assertThrows(IllegalStateException.class,listener::poll);verify(context,never()).close();assertEquals(0,exits.get());
    }
    @Test void rejectsOversizedRequestAndNeverEchoesItsContent()throws Exception{
        var listener=listener();var context=arm(listener);acknowledge();write(control(),"PRIVATE_DO_NOT_OUTPUT".repeat(200));var failure=assertThrows(IllegalStateException.class,listener::poll);
        assertFalse(failure.getMessage().contains("PRIVATE_DO_NOT_OUTPUT"));verify(context,never()).close();assertEquals(0,exits.get());
    }
    @Test void failedCloseDoesNotExitOrRetryTheOriginalRequest()throws Exception{
        var listener=listener();var context=arm(listener);acknowledge();write(control(),request());doThrow(new IllegalStateException("private exception content")).when(context).close();
        listener.poll();listener.poll();verify(context,times(1)).close();assertEquals(0,exits.get());assertTrue(Files.exists(control()));
    }
    @Test void publicBindingOrNonSyntheticContextCannotArm(){
        var listener=listener();var context=mock(ConfigurableApplicationContext.class);
        assertThrows(IllegalStateException.class,()->listener.ready(event(context,"0.0.0.0","synthetic")));
        assertThrows(IllegalStateException.class,()->listener.ready(event(context,"127.0.0.1","production")));assertEquals(0,exits.get());
    }
}
