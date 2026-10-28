package io.aitest.workbench.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** User-bound DPAPI adapter preparation, not wired into configuration or initialization.
 * Native Windows execution and recovery require separate host verification. No persistence,
 * key/account generation, network access, elevation, or plaintext fallback exists here. */
public final class WindowsSecretProtection {
    static final int MAX_PLAIN=64*1024,MAX_PROTECTED=256*1024,MAX_WIRE=360*1024;
    static final Duration TIMEOUT=Duration.ofSeconds(10);
    private static final String ENTROPY="ai-test-workbench:portable-secrets:v1";
    private final Host host;
    private final Runner runner;

    public WindowsSecretProtection(){this(new NativeHost(),new NativeRunner());}
    WindowsSecretProtection(Host host,Runner runner){this.host=Objects.requireNonNull(host);this.runner=Objects.requireNonNull(runner);}

    public static final class ProtectionException extends IOException {
        private final String code;
        ProtectionException(String code,String message){super(message);this.code=code;}
        public String code(){return code;}
    }
    enum Operation{PROTECT,UNPROTECT}
    interface Host {
        boolean windows();
        Path systemRoot();
        Map<String,String> environment();
        boolean trustedExecutable(Path file)throws IOException;
    }
    interface Runner { Reply run(List<String> command,Map<String,String> environment,byte[] standardInput,Duration timeout)throws Exception; }
    record Reply(int exitCode,byte[] standardOutput,boolean timedOut) {
        @Override public String toString(){return "WindowsSecretProtection.Reply[redacted]";}
    }

    public byte[] protect(byte[] plain)throws ProtectionException{return transform(Operation.PROTECT,plain);}
    public byte[] unprotect(byte[] protectedBytes)throws ProtectionException{return transform(Operation.UNPROTECT,protectedBytes);}

    private byte[] transform(Operation operation,byte[] value)throws ProtectionException{
        if(!host.windows())throw error("SECRET_PROTECTION_UNSUPPORTED","需要已验证的 Windows 当前用户 DPAPI；本机不支持，不会明文降级。");
        int inputLimit=operation==Operation.PROTECT?MAX_PLAIN:MAX_PROTECTED;
        if(value==null||value.length==0||value.length>inputLimit)throw error("SECRET_PROTECTION_INPUT","受保护数据为空或超过大小限制。");
        byte[] wire=null,output=null;
        try{
            Path root=host.systemRoot();
            if(root==null||!root.isAbsolute()||!root.equals(root.normalize())||root.toString().startsWith("\\\\"))
                throw error("SECRET_PROTECTION_PROVIDER_MISSING","无法确认 Windows 系统 PowerShell/DPAPI；不会下载或更改策略。");
            Path executable=root.resolve("System32/WindowsPowerShell/v1.0/powershell.exe");
            if(!host.trustedExecutable(executable))throw error("SECRET_PROTECTION_PROVIDER_MISSING","系统 PowerShell/DPAPI 不可用；不会安装、绕过策略或明文保存。");
            wire=Base64.getEncoder().encode(value);
            Reply reply=runner.run(command(executable,operation),environment(host.environment()),wire,TIMEOUT);
            if(reply==null)throw error("SECRET_PROTECTION_FAILED","Windows 用户保护未完成；未保存任何数据，不会明文降级。");
            output=reply.standardOutput();
            if(reply.timedOut())throw error("SECRET_PROTECTION_TIMEOUT","Windows 用户保护超时；未保存任何数据，不会明文降级。");
            if(reply.exitCode()!=0)throw error("SECRET_PROTECTION_FAILED","Windows 用户保护被拒绝或失败；请核对系统组件和策略，不会关闭安全限制。");
            if(output==null||output.length==0||output.length>MAX_WIRE)throw new IllegalArgumentException();
            int first=0,last=output.length;
            while(first<last&&space(output[first]))first++;
            while(last>first&&space(output[last-1]))last--;
            byte[] encoded=Arrays.copyOfRange(output,first,last);
            byte[] decoded;
            try{decoded=Base64.getDecoder().decode(encoded);}finally{Arrays.fill(encoded,(byte)0);}
            int outputLimit=operation==Operation.PROTECT?MAX_PROTECTED:MAX_PLAIN;
            if(decoded.length==0||decoded.length>outputLimit||Arrays.equals(decoded,value)){
                Arrays.fill(decoded,(byte)0);throw new IllegalArgumentException();
            }
            return decoded;
        }catch(ProtectionException expected){throw expected;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw error("SECRET_PROTECTION_INTERRUPTED","Windows 用户保护已中断；没有明文降级或保存操作。");}
        catch(Exception failure){throw error("SECRET_PROTECTION_FAILED","Windows 用户保护未完成；原始错误和数据不输出，不会明文降级。");}
        finally{if(wire!=null)Arrays.fill(wire,(byte)0);if(output!=null)Arrays.fill(output,(byte)0);}
    }

    private static boolean space(byte value){return value==' '||value=='\t'||value=='\n'||value=='\r';}
    private static ProtectionException error(String code,String message){return new ProtectionException(code,message);}

    static List<String> command(Path executable,Operation operation){
        // Only this constant program is encoded. Secret bytes travel through redirected stdin.
        String method=operation==Operation.PROTECT?"Protect":"Unprotect";
        String program="$ErrorActionPreference='Stop';$inputBytes=$null;$resultBytes=$null;try{"
            +"Add-Type -AssemblyName System.Security;"
            +"$inputBytes=[Convert]::FromBase64String([Console]::In.ReadToEnd());"
            +"$entropy=[Text.Encoding]::UTF8.GetBytes('"+ENTROPY+"');"
            +"$resultBytes=[Security.Cryptography.ProtectedData]::"+method+"($inputBytes,$entropy,[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            +"[Console]::Out.Write([Convert]::ToBase64String($resultBytes));"
            +"}catch{[Console]::Error.Write('Windows user protection failed');exit 2}"
            +"finally{if($null -ne $inputBytes){[Array]::Clear($inputBytes,0,$inputBytes.Length)}"
            +"if($null -ne $resultBytes){[Array]::Clear($resultBytes,0,$resultBytes.Length)}}";
        String encoded=Base64.getEncoder().encodeToString(program.getBytes(StandardCharsets.UTF_16LE));
        return List.of(executable.toString(),"-NoLogo","-NoProfile","-NonInteractive","-EncodedCommand",encoded);
    }

    static Map<String,String> environment(Map<String,String> inherited){
        var result=new HashMap<String,String>();
        Set<String> blocked=Set.of("JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS","CLASSPATH","MYSQL_PWD",
            "PSMODULEPATH","PSMODULEANALYSISCACHEPATH","COR_ENABLE_PROFILING","COR_PROFILER","COR_PROFILER_PATH");
        for(var value:inherited.entrySet()){
            String key=value.getKey().toUpperCase(Locale.ROOT);
            if(blocked.contains(key)||key.matches("(?:WB_|SPRING_|SERVER_|LOADER_|COMPLUS_|DOTNET_).*"))continue;
            result.put(value.getKey(),value.getValue());
        }
        return result;
    }

    private static final class NativeHost implements Host {
        public boolean windows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows");}
        public Path systemRoot(){String value=System.getenv("SystemRoot");return value==null?null:Path.of(value);}
        public Map<String,String> environment(){return System.getenv();}
        public boolean trustedExecutable(Path file)throws IOException{
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))return false;
            for(Path current=file;current!=null;current=current.getParent())if(Files.isSymbolicLink(current))return false;
            return file.toRealPath().equals(file.toAbsolutePath().normalize());
        }
    }

    /** Only creates a bounded crypto helper. It never launches DB/application/installer code. */
    private static final class NativeRunner implements Runner {
        public Reply run(List<String> command,Map<String,String> environment,byte[] input,Duration timeout)throws Exception{
            ProcessBuilder builder=new ProcessBuilder(command).directory(Path.of(command.getFirst()).getParent().toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();builder.environment().putAll(environment);
            Process child=builder.start();
            try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
                Future<byte[]> reader=workers.submit(()->readBounded(child.getInputStream()));
                Future<?> writer=workers.submit(()->{try(var stream=child.getOutputStream()){stream.write(input);}return null;});
                try{
                    if(!child.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)){
                        child.destroyForcibly();closePipes(child);return new Reply(-1,null,true);
                    }
                    writer.get(1,TimeUnit.SECONDS);
                    return new Reply(child.exitValue(),reader.get(1,TimeUnit.SECONDS),false);
                }finally{
                    if(child.isAlive())child.destroyForcibly();
                    closePipes(child);writer.cancel(true);reader.cancel(true);
                }
            }
        }
        private static byte[] readBounded(InputStream input)throws IOException{
            try(input;var output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];
                try{for(int count;(count=input.read(buffer))!=-1;){if(output.size()+count>MAX_WIRE)throw new IOException("User protection output limit");output.write(buffer,0,count);}return output.toByteArray();}
                finally{Arrays.fill(buffer,(byte)0);}
            }
        }
        private static void closePipes(Process child){
            try{child.getOutputStream().close();}catch(IOException ignored){}
            try{child.getInputStream().close();}catch(IOException ignored){}
            try{child.getErrorStream().close();}catch(IOException ignored){}
        }
    }
}
