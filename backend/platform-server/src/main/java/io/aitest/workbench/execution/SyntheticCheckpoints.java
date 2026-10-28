package io.aitest.workbench.execution;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Disabled by default. Private process configuration only; no HTTP switch or client trigger.
 * The application already refuses every non-synthetic profile and non-loopback target. */
@Component
public final class SyntheticCheckpoints {
    private final String phase;private final UUID binding;private final Path directory;private final AtomicBoolean fired=new AtomicBoolean();
    public SyntheticCheckpoints(@Value("${WB_FAULT_PHASE:}")String phase,@Value("${WB_FAULT_BINDING_ID:}")String binding,@Value("${WB_FAULT_ROOT:}")String root){
        this.phase=phase;
        if(phase.isEmpty()){this.binding=null;this.directory=null;return;}
        if(!Set.of("approval_before_commit","dispatch_committed","provider_acceptance_committed","platform_receipt_committed","provider_result_committed","provider_execution_started").contains(phase))throw new IllegalArgumentException("Unknown synthetic checkpoint");
        this.binding=UUID.fromString(binding);this.directory=Path.of(root).normalize();
        if(!directory.isAbsolute()||Files.isSymbolicLink(directory))throw new IllegalArgumentException("Private absolute checkpoint directory required");
    }
    public static SyntheticCheckpoints disabled(){return new SyntheticCheckpoints("","","");}
    public void hit(String point,UUID target){
        if(!phase.equals(point)||!Objects.equals(binding,target)||!fired.compareAndSet(false,true))return;
        try{
            Files.createDirectories(directory);Path marker=directory.resolve("checkpoint.json");
            String json="{\"phase\":\""+phase+"\",\"bindingId\":\""+binding+"\",\"pid\":"+ProcessHandle.current().pid()+",\"reachedAt\":\""+Instant.now()+"\"}";
            Path temporary=directory.resolve("checkpoint.pending");Files.writeString(temporary,json,StandardOpenOption.CREATE_NEW);
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(temporary,marker,StandardCopyOption.ATOMIC_MOVE);
        }catch(java.io.IOException e){throw new IllegalStateException("Cannot persist synthetic checkpoint",e);}
        // The fault harness verifies this marker, exact PID and jar path before terminating
        // only its own test service process. No other binding pauses here.
        while(!Thread.currentThread().isInterrupted())LockSupport.parkNanos(java.util.concurrent.TimeUnit.SECONDS.toNanos(1));
        throw new IllegalStateException("Synthetic checkpoint interrupted");
    }
}
