package io.aitest.workbench.bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Optional private launch acknowledgement, independent of API identity and execution facts. */
@Component
public final class RuntimeReadiness {
    private final Path target;
    private final String nonce;
    private final AtomicBoolean published=new AtomicBoolean();

    public RuntimeReadiness(@Value("${WB_RUNTIME_READY_FILE:}")String file,
        @Value("${WB_RUNTIME_READY_NONCE:}")String nonce){
        if(file.isEmpty()&&nonce.isEmpty()){this.target=null;this.nonce=null;return;}
        try{
            if(file.isEmpty()||nonce.isEmpty()||!UUID.fromString(nonce).toString().equals(nonce))throw new IllegalArgumentException();
            this.target=Path.of(file);this.nonce=nonce;
            if(!target.isAbsolute()||!target.equals(target.normalize())||target.getParent()==null)throw new IllegalArgumentException();
            validatePath(target);
        }catch(Exception invalid){throw new IllegalArgumentException("Private readiness file and canonical UUID nonce must be supplied together; an unused absolute non-symlink path is required.");}
    }

    @EventListener
    public void ready(ApplicationReadyEvent event){
        if(target==null)return;
        var context=event.getApplicationContext();
        if(!Arrays.asList(context.getEnvironment().getActiveProfiles()).equals(List.of("synthetic"))
            ||!"127.0.0.1".equals(context.getEnvironment().getProperty("server.address","127.0.0.1"))
            ||!(context instanceof WebServerApplicationContext web)||web.getWebServer()==null)
            throw new IllegalStateException("Private readiness requires the isolated synthetic loopback web server.");
        int port=web.getWebServer().getPort();
        if(port<1||port>65535)throw new IllegalStateException("Private readiness requires an actually bound web port.");
        if(!published.compareAndSet(false,true))return;
        Path temporary=null;
        try{
            validatePath(target);
            temporary=target.getParent().resolve(".workbench-ready-"+UUID.randomUUID()+".pending");
            String json="{\"pid\":"+ProcessHandle.current().pid()+",\"nonce\":\""+nonce+"\",\"port\":"+port+",\"identityMode\":\"synthetic_only\"}\n";
            RuntimeFiles.writeNewPrivateFile(temporary,json.getBytes(StandardCharsets.UTF_8));
            validatePath(target);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
            temporary=null;
        }catch(Exception invalid){throw new IllegalStateException("Private readiness acknowledgement could not be published safely; service is not reported ready.");}
        finally{
            if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}
        }
    }

    private static void validatePath(Path target)throws java.io.IOException{
        for(Path current=target;current!=null;current=current.getParent())
            if(Files.isSymbolicLink(current))throw new java.io.IOException();
        if(!Files.isDirectory(target.getParent(),LinkOption.NOFOLLOW_LINKS)||Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new java.io.IOException();
        RuntimeFiles.requireOwnerDirectory(target.getParent());
    }
}
