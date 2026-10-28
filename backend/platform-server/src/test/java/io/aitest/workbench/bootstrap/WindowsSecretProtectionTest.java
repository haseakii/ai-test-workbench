package io.aitest.workbench.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Fake provider replies exercise adapter boundaries; they are not real DPAPI evidence. */
class WindowsSecretProtectionTest {
    private final byte[] plain="synthetic-plaintext-no-account-created".getBytes(StandardCharsets.UTF_8);
    private final byte[] sealed="fake-provider-ciphertext-for-adapter-tests-only-not-real-dpapi".getBytes(StandardCharsets.UTF_8);

    WindowsSecretProtection.Host host(boolean windows,boolean present){
        return new WindowsSecretProtection.Host(){
            public boolean windows(){return windows;}
            public Path systemRoot(){return Path.of("/test/windows-system");}
            public Map<String,String> environment(){return Map.of("SystemRoot","/test/windows-system","HTTP_PROXY","http://proxy.invalid:8080","WB_DB_PASSWORD","inherited-private-value");}
            public boolean trustedExecutable(Path path){return present;}
        };
    }
    WindowsSecretProtection.Reply reply(byte[] bytes){return new WindowsSecretProtection.Reply(0,Base64.getEncoder().encode(bytes),false);}
    WindowsSecretProtection adapter(WindowsSecretProtection.Runner runner){return new WindowsSecretProtection(host(true,true),runner);}

    @Test void nonWindowsFailsBeforeSelectingOrRunningProvider(){
        var calls=new AtomicInteger();
        var adapter=new WindowsSecretProtection(host(false,true),(c,e,i,t)->{calls.incrementAndGet();throw new AssertionError();});
        var failure=assertThrows(WindowsSecretProtection.ProtectionException.class,()->adapter.protect(plain));
        assertEquals("SECRET_PROTECTION_UNSUPPORTED",failure.code());assertEquals(0,calls.get());
    }
    @Test void missingSystemProviderDoesNotRunOrDowngrade(){
        var adapter=new WindowsSecretProtection(host(true,false),(c,e,i,t)->{throw new AssertionError();});
        assertEquals("SECRET_PROTECTION_PROVIDER_MISSING",assertThrows(WindowsSecretProtection.ProtectionException.class,()->adapter.protect(plain)).code());
    }
    @Test void protectAndUnprotectOnlyExchangeBase64ThroughStdinAndRedactedReplies()throws Exception{
        var received=new ArrayList<byte[]>();var commands=new ArrayList<List<String>>();
        var adapter=adapter((command,env,input,timeout)->{
            commands.add(command);received.add(input.clone());assertFalse(env.containsKey("WB_DB_PASSWORD"));
            assertEquals("http://proxy.invalid:8080",env.get("HTTP_PROXY"));assertEquals(Duration.ofSeconds(10),timeout);
            return reply(commands.size()==1?sealed:plain);
        });
        assertArrayEquals(sealed,adapter.protect(plain));assertArrayEquals(plain,adapter.unprotect(sealed));
        assertArrayEquals(plain,Base64.getDecoder().decode(received.get(0)));assertArrayEquals(sealed,Base64.getDecoder().decode(received.get(1)));
        for(var command:commands){assertFalse(String.join(" ",command).contains(new String(plain,StandardCharsets.UTF_8)));assertFalse(String.join(" ",command).contains(Base64.getEncoder().encodeToString(plain)));}
        assertEquals("WindowsSecretProtection.Reply[redacted]",reply(plain).toString());
    }
    @Test void staticCommandsHaveCurrentUserScopeNoElevationOrPolicyBypass(){
        for(var operation:WindowsSecretProtection.Operation.values()){
            var command=WindowsSecretProtection.command(Path.of("/system/WindowsPowerShell/v1.0/powershell.exe"),operation);
            assertEquals(List.of("-NoLogo","-NoProfile","-NonInteractive","-EncodedCommand"),command.subList(1,5));
            var script=new String(Base64.getDecoder().decode(command.get(5)),StandardCharsets.UTF_16LE);
            assertTrue(script.contains("DataProtectionScope]::CurrentUser"));assertTrue(script.contains("[Console]::In.ReadToEnd()"));
            assertTrue(script.contains(operation==WindowsSecretProtection.Operation.PROTECT?"::Protect(":"::Unprotect("));
            assertFalse(script.contains("LocalMachine"));assertFalse(script.contains("Bypass"));assertFalse(script.contains("Invoke-Expression"));assertFalse(script.contains("Start-Process"));
        }
    }
    @Test void inputBoundsRejectBeforeRunnerAndDoNotAlterCallerData(){
        var calls=new AtomicInteger();var adapter=adapter((c,e,i,t)->{calls.incrementAndGet();return reply(sealed);});
        for(byte[] value:new byte[][]{null,new byte[0],new byte[WindowsSecretProtection.MAX_PLAIN+1]})assertEquals("SECRET_PROTECTION_INPUT",assertThrows(WindowsSecretProtection.ProtectionException.class,()->adapter.protect(value)).code());
        assertEquals(0,calls.get());assertArrayEquals("synthetic-plaintext-no-account-created".getBytes(StandardCharsets.UTF_8),plain);
    }
    @Test void noOpPlaintextProviderOutputIsNeverAcceptedAsProtection(){
        var adapter=adapter((c,e,i,t)->reply(plain));
        assertEquals("SECRET_PROTECTION_FAILED",assertThrows(WindowsSecretProtection.ProtectionException.class,()->adapter.protect(plain)).code());
    }
    @Test void nonzeroExitDiscardsSensitiveProviderOutputWithoutErrorLeak(){
        var adapter=adapter((c,e,i,t)->new WindowsSecretProtection.Reply(3,plain.clone(),false));
        var failure=assertThrows(WindowsSecretProtection.ProtectionException.class,()->adapter.protect(plain));
        assertEquals("SECRET_PROTECTION_FAILED",failure.code());assertFalse(failure.toString().contains(new String(plain,StandardCharsets.UTF_8)));assertNull(failure.getCause());
    }
    @Test void timeoutHasExplicitCodeAndNoPlaintextFallback(){
        var adapter=adapter((c,e,i,t)->new WindowsSecretProtection.Reply(-1,null,true));
        assertEquals("SECRET_PROTECTION_TIMEOUT",assertThrows(WindowsSecretProtection.ProtectionException.class,()->adapter.protect(plain)).code());
    }
    @Test void invalidOrOverlargeWireDataIsRejected(){
        for(byte[] value:new byte[][]{"not-base64!".getBytes(),new byte[0],new byte[WindowsSecretProtection.MAX_WIRE+1]}){
            var adapter=adapter((c,e,i,t)->new WindowsSecretProtection.Reply(0,value,false));
            assertEquals("SECRET_PROTECTION_FAILED",assertThrows(WindowsSecretProtection.ProtectionException.class,()->adapter.protect(plain)).code());
        }
    }
    @Test void runnerExceptionsAreFixedAndInternalInputOutputBuffersAreCleared()throws Exception{
        byte[][] borrowed=new byte[2][];
        var adapter=adapter((c,e,i,t)->{borrowed[0]=i;var response=reply(sealed);borrowed[1]=response.standardOutput();return response;});
        byte[] result=adapter.protect(plain);assertArrayEquals(sealed,result);assertTrue(allZero(borrowed[0]));assertTrue(allZero(borrowed[1]));
        var broken=adapter((c,e,i,t)->{throw new IOException("provider error must not echo "+new String(plain,StandardCharsets.UTF_8));});
        var failure=assertThrows(WindowsSecretProtection.ProtectionException.class,()->broken.protect(plain));assertNull(failure.getCause());assertFalse(failure.getMessage().contains(new String(plain,StandardCharsets.UTF_8)));
    }
    @Test void interruptedRunnerPreservesInterruptionWithoutRawCause(){
        var adapter=adapter((c,e,i,t)->{throw new InterruptedException("not-for-output");});
        try{assertEquals("SECRET_PROTECTION_INTERRUPTED",assertThrows(WindowsSecretProtection.ProtectionException.class,()->adapter.protect(plain)).code());assertTrue(Thread.currentThread().isInterrupted());}
        finally{Thread.interrupted();}
    }
    @Test void caseInsensitiveInjectionVariablesAreRemovedAndProxyCaGuardsRemain(){
        var source=new HashMap<String,String>();
        for(String key:List.of("java_tool_options","_JAVA_OPTIONS","jDk_JaVa_OpTiOnS","CLASSPATH","MYSQL_PWD","WB_DB_PASSWORD","SPRING_APPLICATION_JSON","SERVER_PORT","LOADER_PATH","PSModulePath","COR_PROFILER","COMPLUS_EnableDiagnostics","DOTNET_STARTUP_HOOKS"))source.put(key,"must-not-inherit");
        source.put("PSExecutionPolicyPreference","AllSigned");source.put("HTTPS_PROXY","http://trusted-proxy.invalid");source.put("SSL_CERT_FILE","/trusted/ca-bundle");source.put("RUNTIME_AUTH_GUARD","guard-value");
        var actual=WindowsSecretProtection.environment(source);
        assertEquals(Map.of("PSExecutionPolicyPreference","AllSigned","HTTPS_PROXY","http://trusted-proxy.invalid","SSL_CERT_FILE","/trusted/ca-bundle","RUNTIME_AUTH_GUARD","guard-value"),actual);
    }
    @Test void nativeAdapterOnCurrentNonWindowsNeverAttemptsPowershell(){
        assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"),"Only the non-Windows rejection path may be exercised here; actual Windows DPAPI is not authorized by this test.");
        assertEquals("SECRET_PROTECTION_UNSUPPORTED",assertThrows(WindowsSecretProtection.ProtectionException.class,()->new WindowsSecretProtection().protect(plain)).code());
    }
    private boolean allZero(byte[] value){for(byte b:value)if(b!=0)return false;return true;}
}
