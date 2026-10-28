package io.aitest.workbench.bootstrap;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Owner-private, per-launch stop request. No HTTP endpoint, OS signal, or remote authority. */
@Component
public final class RuntimeLifecycleControl {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path control,ready;
    private final String nonce;
    private final long pid;
    private final Executor executor;
    private final Runnable exit;
    private final AtomicBoolean stopping=new AtomicBoolean();
    private volatile ConfigurableApplicationContext context;

    @Autowired
    public RuntimeLifecycleControl(@Value("${WB_RUNTIME_CONTROL_FILE:}")String control,
        @Value("${WB_RUNTIME_READY_NONCE:}")String nonce,@Value("${WB_RUNTIME_READY_FILE:}")String ready){
        this(control,nonce,ready,ProcessHandle.current().pid(),command->{
            var thread=new Thread(command,"workbench-private-graceful-stop");thread.setDaemon(false);thread.start();
        },()->System.exit(0));
    }

    RuntimeLifecycleControl(String control,String nonce,String ready,long pid,Executor executor,Runnable exit){
        this.pid=pid;this.executor=Objects.requireNonNull(executor);this.exit=Objects.requireNonNull(exit);
        // Existing Linux launches only set READY_*; an absent CONTROL_FILE remains disabled.
        if(control.isEmpty()){this.control=null;this.ready=null;this.nonce=null;return;}
        try{
            if(nonce.isEmpty()||ready.isEmpty()||!UUID.fromString(nonce).toString().equals(nonce)||pid<1)throw new IOException();
            this.control=Path.of(control);this.ready=Path.of(ready);this.nonce=nonce;
            RuntimeFiles.location(this.control);RuntimeFiles.location(this.ready);
            if(this.control.equals(this.ready)||!this.control.getParent().equals(this.ready.getParent()))throw new IOException();
            RuntimeFiles.requirePrivateDirectory(this.control.getParent());
            if(Files.exists(this.control,LinkOption.NOFOLLOW_LINKS))throw new IOException();
        }catch(Exception invalid){throw new IllegalArgumentException("Private lifecycle requires distinct unused control and readiness paths in one private run directory and a canonical launch nonce.");}
    }

    @EventListener
    public void ready(ApplicationReadyEvent event){
        if(control==null)return;
        var candidate=event.getApplicationContext();
        if(!Arrays.asList(candidate.getEnvironment().getActiveProfiles()).equals(List.of("synthetic"))
            ||!"127.0.0.1".equals(candidate.getEnvironment().getProperty("server.address","127.0.0.1")))
            throw new IllegalStateException("Private lifecycle requires the isolated synthetic loopback service.");
        this.context=candidate;
    }

    @Scheduled(fixedDelay=250)
    public void poll(){
        var candidate=context;
        if(control==null||candidate==null||stopping.get()||!Files.exists(control,LinkOption.NOFOLLOW_LINKS))return;
        try{
            RuntimeFiles.requirePrivateDirectory(control.getParent());
            JsonNode request=read(control),acknowledgement=read(ready);
            if(!request.isObject()||request.size()!=3||!text(request,"action","stop")||!text(request,"nonce",nonce)||!samePid(request)
                ||!acknowledgement.isObject()||acknowledgement.size()!=4||!text(acknowledgement,"nonce",nonce)
                ||!samePid(acknowledgement)||!text(acknowledgement,"identityMode","synthetic_only")
                ||!acknowledgement.has("port")||!acknowledgement.get("port").isIntegralNumber()
                ||acknowledgement.get("port").bigIntegerValue().compareTo(BigInteger.ONE)<0
                ||acknowledgement.get("port").bigIntegerValue().compareTo(BigInteger.valueOf(65535))>0)throw new IOException();
            if(!stopping.compareAndSet(false,true))return;
            // Keep the request, acknowledgement and original process metadata as evidence.
            executor.execute(()->{
                try{candidate.close();exit.run();}
                catch(RuntimeException failure){System.err.println("Private graceful shutdown was not confirmed; original runtime evidence is retained.");}
            });
        }catch(Exception invalid){throw new IllegalStateException("Private lifecycle request was not accepted; original process and evidence are retained.");}
    }

    private boolean samePid(JsonNode value){
        return value.has("pid")&&value.get("pid").isIntegralNumber()&&value.get("pid").bigIntegerValue().equals(BigInteger.valueOf(pid));
    }
    private static boolean text(JsonNode value,String field,String expected){
        return value.has(field)&&value.get(field).isString()&&expected.equals(value.get(field).stringValue());
    }
    private static JsonNode read(Path path)throws IOException{
        RuntimeFiles.requirePrivateFile(path);
        try(var channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(channel.size()<2||channel.size()>2048)throw new IOException();
            var bytes=ByteBuffer.allocate((int)channel.size());
            while(bytes.hasRemaining())if(channel.read(bytes)<0)throw new IOException();
            if(channel.read(ByteBuffer.allocate(1))!=-1)throw new IOException();
            return JSON.readTree(bytes.array());
        }
    }
}
