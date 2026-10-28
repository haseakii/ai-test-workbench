package io.aitest.workbench.bootstrap;

import java.io.Console;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Separate distribution CLI. No Spring context, worker, provisioning or forceful process kill. */
public final class RuntimeLauncher {
    static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static final Set<String> RUNTIME_KEYS=Set.of("SPRING_PROFILES_ACTIVE","WB_DB_URL","WB_DB_USER","WB_DB_PASSWORD","WB_BLOB_ROOT","WB_PORT");
    static final Set<String> MIGRATION_KEYS=Set.of("WB_DB_URL","WB_MIGRATION_USER","WB_MIGRATION_PASSWORD");
    private RuntimeLauncher(){}

    static final class Failure extends RuntimeException {
        final String code;
        Failure(String code,String message){super(message);this.code=code;}
    }
    static Failure failure(String code,String message){return new Failure(code,message);}
    public record Result(boolean ok,String code,String message,String state,Long pid,String sha256){
        static Result ok(String code,String message,String state,Metadata metadata){return new Result(true,code,message,state,metadata==null?null:metadata.pid(),metadata==null?null:metadata.sha256());}
    }
    record Options(String action,Path bundle,Path directory,Path config,Path platformMigration,Path runnerMigration,boolean initialize){
        Path jar(){return bundle.resolve("app/platform-server.jar");}
        Path metadata(){return directory.resolve("windows-process.json");}
    }
    record Metadata(long pid,String startInstant,String java,String artifactJar,String sha256,String nonce,int port,String launchState){
        @Override public String toString(){return "RuntimeLauncher.Metadata[private]";}
    }
    record Identity(long pid,Instant startInstant,Path executable,List<String> arguments){}
    interface Ops {
        RuntimeProbe.Result probe(Map<String,String> environment,String stage);
        Identity identity(long pid);
        long start(List<String> command,Map<String,String> environment,Path log)throws IOException;
        int migration(List<String> command,Map<String,String> environment,Path log)throws IOException,InterruptedException;
        void checkPort(int port)throws IOException;
        boolean httpReady(int port);
        long nanos();
        void pause()throws InterruptedException;
    }

    public static void main(String[] args){
        int exit=execute(args,System.getenv(),System.out);
        if(exit!=0)System.exit(exit);
    }
    static int execute(String[] args,Map<String,String> inherited,PrintStream out){
        try{
            if(args.length==0)return menu(inherited,out);
            if(args[0].equals("setup"))return SetupInspector.execute(Arrays.copyOfRange(args,1,args.length),inherited,out);
            if(Set.of("help","--help","-h").contains(args[0])){help(out);return 0;}
            Options options=options(args,inherited);
            Result result=options.action().equals("configure")?configure(options):run(options,new NativeOps(),inherited);
            out.println(JSON.writeValueAsString(result));
            return result.ok()?0:2;
        }catch(Failure error){out.println(JSON.writeValueAsString(new Result(false,error.code,error.getMessage(),"unconfirmed",null,null)));return 2;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();out.println(JSON.writeValueAsString(new Result(false,"INTERRUPTED","操作已中断，原记录和数据保留；先核查状态，不自动重放。","unconfirmed",null,null)));return 130;}
        catch(Exception error){out.println(JSON.writeValueAsString(new Result(false,"LOCAL_OPERATION_FAILED","本地操作未完成；原记录保留，请检查私有配置、目录权限和发行包。","unconfirmed",null,null)));return 2;}
    }
    static void help(PrintStream out){
        out.println("AI Test Workbench Windows 合成预览入口（真实 Windows 首装/停启仍待验收）");
        out.println("操作：setup 只读首装方案 / configure / doctor / up / status / stop；up --initialize 仅迁移已有两库。");
        out.println("参数：--bundle-dir --runtime-dir --config --platform-migration-config --runner-migration-config（只接受路径，不接受密码）。");
        out.println("依赖：Java21、已有获准 MySQL8.4/TLS/分权账号；不安装软件、不创建库或账号，不强杀进程。");
    }
    static int menu(Map<String,String> inherited,PrintStream out)throws Exception{
        Console console=System.console();
        if(console==null)throw failure("CONSOLE_REQUIRED","双击菜单需要交互控制台；也可从命令行使用 doctor/up/status/stop。");
        out.println("AI Test Workbench｜Windows 合成预览（不接真实业务数据）");
        while(true){
            out.println("7 首装方案（只读）  1 配置已有环境  2 检查环境  3 启动  4 状态  5 停止  6 已有两库迁移启动  0 退出");
            String choice=console.readLine("请选择：");
            if(choice==null||choice.equals("0"))return 0;
            String action=switch(choice){case "1"->"configure";case "2"->"doctor";case "3","6"->"up";case "4"->"status";case "5"->"stop";case "7"->"setup";default->null;};
            if(action==null){out.println("请选择 0–7。");continue;}
            execute(choice.equals("6")?new String[]{"up","--initialize"}:new String[]{action},inherited,out);
        }
    }
    static Options options(String[] args,Map<String,String> environment){
        if(!Set.of("configure","doctor","up","status","stop").contains(args[0]))throw failure("ARGUMENTS","未知操作；使用 help 查看统一入口。");
        Map<String,String> values=new HashMap<>();boolean initialize=false;
        for(int i=1;i<args.length;i++){
            if(args[i].equals("--initialize")){if(initialize)throw failure("ARGUMENTS","初始化参数重复。");initialize=true;continue;}
            if(!Set.of("--bundle-dir","--runtime-dir","--config","--platform-migration-config","--runner-migration-config").contains(args[i])||i+1==args.length||values.putIfAbsent(args[i],args[++i])!=null)
                throw failure("ARGUMENTS","参数缺失、重复或不受支持；密码只从私有配置读取。");
        }
        if(initialize&&!args[0].equals("up"))throw failure("ARGUMENTS","--initialize 仅用于显式初始化启动。");
        Path bundle=absolute(values.getOrDefault("--bundle-dir",System.getProperty("workbench.bundle",".")));
        String base=environment.get("LOCALAPPDATA");
        Path defaultDirectory=base==null||base.isBlank()?Path.of(System.getProperty("user.home"),".ai-test-workbench","synthetic"):Path.of(base,"AiTestWorkbench","synthetic");
        Path directory=absolute(values.getOrDefault("--runtime-dir",defaultDirectory.toString()));
        return new Options(args[0],bundle,directory,absolute(values.getOrDefault("--config",directory.resolve("runtime-private.json").toString())),
            absolute(values.getOrDefault("--platform-migration-config",directory.resolve("migration-platform-private.json").toString())),
            absolute(values.getOrDefault("--runner-migration-config",directory.resolve("migration-runner-private.json").toString())),initialize);
    }
    static Path absolute(String value){try{return Path.of(value).toAbsolutePath().normalize();}catch(Exception invalid){throw failure("PATH_INVALID","路径无效；请使用本地目录，不输入连接串或密码。");}}

    static Result run(Options options,Ops ops,Map<String,String> inherited)throws Exception{
        if(Runtime.version().feature()!=21)throw failure("JAVA_VERSION","此发行包需要 Java21；入口不会安装或下载运行时。");
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows")?"java.exe":"java").toRealPath();
        if(options.action().equals("status"))return status(options,ops,java);
        if(options.action().equals("stop")){
            if(!Files.exists(options.directory()))return Result.ok("STOPPED","服务未运行。","stopped",null);
            return locked(options.directory(),()->stop(options,ops,java));
        }
        Map<String,String> config=configuration(options.config(),false);
        validate(config,false,"workbench_platform");
        checkJar(options.jar());
        // Preflight never creates a directory or fixes existing permissions.
        if(Files.exists(options.directory()))RuntimeFiles.requirePrivateDirectory(options.directory());
        Metadata current=owned(options,ops,java);
        if(options.action().equals("doctor")){
            if(current!=null){ready(options,current,ops);}
            else ops.checkPort(Integer.parseInt(config.get("WB_PORT")));
            probe(ops,config,"schema");
            return Result.ok("PREFLIGHT_PASSED","只读检查通过；这不代表干净 Windows 首装或真实身份已验收。",current==null?"stopped":"ready",current);
        }
        Map<String,String> platform=null,runner=null;
        if(options.initialize()){
            platform=configuration(options.platformMigration(),true);runner=configuration(options.runnerMigration(),true);
            validate(platform,true,"workbench_platform");validate(runner,true,"workbench_runner");
            if(!platform.get("WB_DB_URL").equals(config.get("WB_DB_URL"))||!runner.get("WB_DB_URL").replace("/workbench_runner?","/workbench_platform?").equals(config.get("WB_DB_URL")))
                throw failure("MIGRATION_TARGET_MISMATCH","运行和两库迁移必须指向同一获准实例及 TLS 配置。");
            if(current!=null)throw failure("RUNNING_INITIALIZE_REFUSED","原服务仍运行，不能同时迁移；先核查并优雅停止。");
        }
        probe(ops,config,options.initialize()?"database":"schema");
        RuntimeFiles.createPrivateDirectory(options.directory());
        final Map<String,String> migrationPlatform=platform,migrationRunner=runner;
        return locked(options.directory(),()->{
            Metadata again=owned(options,ops,java);
            if(again!=null){
                if(options.initialize())throw failure("RUNNING_INITIALIZE_REFUSED","已有服务启动，未执行迁移。");
                ready(options,again,ops);return Result.ok("ALREADY_READY","沿用原进程，不重复启动。","ready",again);
            }
            ops.checkPort(Integer.parseInt(config.get("WB_PORT")));
            String sha=sha(options.jar());Path pinned=pin(options.jar(),options.directory(),sha);
            if(options.initialize()){
                migration(options,ops,java,pinned,migrationPlatform,null,inherited);
                migration(options,ops,java,pinned,migrationRunner,"classpath:db/runner-migration",inherited);
                probe(ops,config,"schema");
            }
            RuntimeFiles.createPrivateDirectory(Path.of(config.get("WB_BLOB_ROOT")));
            String nonce=UUID.randomUUID().toString();Path run=options.directory().resolve("runs").resolve(nonce);
            RuntimeFiles.createPrivateDirectory(run);
            Metadata intent=new Metadata(0,null,java.toString(),pinned.toString(),sha,nonce,Integer.parseInt(config.get("WB_PORT")),"launching");
            metadata(options,intent);
            Map<String,String> child=environment(inherited,config,false);
            child.put("WB_RUNTIME_READY_NONCE",nonce);child.put("WB_RUNTIME_READY_FILE",run.resolve("ready.json").toString());child.put("WB_RUNTIME_CONTROL_FILE",run.resolve("control.json").toString());
            Path log=run.resolve("service.log");RuntimeFiles.writeNewPrivateFile(log,new byte[0]);long pid;
            try{pid=ops.start(List.of(java.toString(),"-jar",pinned.toString()),child,log);}
            catch(IOException uncertain){throw failure("START_UNCONFIRMED","创建进程未得到确定回执；原启动意图保留，不能重复启动。请核查私有记录和原进程。");}
            Identity identity=ops.identity(pid);
            Metadata recorded=new Metadata(pid,identity==null||identity.startInstant()==null?null:identity.startInstant().toString(),java.toString(),pinned.toString(),sha,nonce,intent.port(),"started");metadata(options,recorded);
            if(identity==null||identity.startInstant()==null)throw failure("START_UNCONFIRMED","服务身份尚未确认；记录已保留，不自动重启或强杀。");
            long until=ops.nanos()+Duration.ofSeconds(30).toNanos();
            while(ops.nanos()<until){
                Metadata live=owned(options,ops,java);
                if(live==null)throw failure("START_EXITED","服务已退出；检查私有运行日志，未自动重试。");
                try{ready(options,live,ops);return Result.ok("READY","服务已就绪，请打开 http://127.0.0.1:"+live.port()+"/ 。","ready",live);}catch(Failure notReady){if(!notReady.code.equals("NOT_READY"))throw notReady;}
                ops.pause();
            }
            throw failure("START_UNCONFIRMED","服务就绪尚未确认；进程可能仍运行，先核查状态，不自动替代。");
        });
    }

    static Result status(Options options,Ops ops,Path java)throws Exception{
        if(!Files.exists(options.directory()))return Result.ok("STOPPED","服务未运行。","stopped",null);
        RuntimeFiles.requirePrivateDirectory(options.directory());Metadata current=owned(options,ops,java);
        if(current==null)return Result.ok("STOPPED","服务未运行，原记录保留。","stopped",null);
        ready(options,current,ops);return Result.ok("READY","服务已就绪，请打开 http://127.0.0.1:"+current.port()+"/ 。","ready",current);
    }
    static Result stop(Options options,Ops ops,Path java)throws Exception{
        Metadata current=owned(options,ops,java);
        if(current==null)return Result.ok("STOPPED","服务已停止，数据与记录保留。","stopped",null);
        Path request=options.directory().resolve("runs").resolve(current.nonce()).resolve("control.json");
        byte[] body=JSON.writeValueAsBytes(Map.of("nonce",current.nonce(),"pid",current.pid(),"action","stop"));
        if(Files.exists(request)){RuntimeFiles.requirePrivateFile(request);if(!Arrays.equals(Files.readAllBytes(request),body))throw failure("STOP_REQUEST_CONFLICT","原控制请求不一致；未覆盖或发送其他停止动作。");}
        else RuntimeFiles.writeNewPrivateFile(request,body);
        long until=ops.nanos()+Duration.ofSeconds(20).toNanos();
        while(ops.nanos()<until){
            Identity identity=ops.identity(current.pid());
            if(identity==null)return Result.ok("STOPPED","服务已优雅停止，数据库、文件和历史记录保留。","stopped",current);
            ensureIdentity(current,identity,java);ops.pause();
        }
        throw failure("STOP_UNCONFIRMED","优雅停止未确认；原请求与数据保留，不强杀、不启动替代进程。");
    }
    static Metadata owned(Options options,Ops ops,Path java)throws Exception{
        if(!Files.exists(options.metadata()))return null;
        RuntimeFiles.requirePrivateFile(options.metadata());Metadata value;
        try{
            byte[] bytes=limited(options.metadata());JsonNode node=JSON.readTree(bytes);
            if(!fields(node,Set.of("pid","startInstant","java","artifactJar","sha256","nonce","port","launchState"))
                ||!integral(node.get("pid"),0,Long.MAX_VALUE)||!integral(node.get("port"),1024,65535))throw new IllegalArgumentException();
            for(String key:List.of("java","artifactJar","sha256","nonce","launchState"))if(!node.get(key).isString()||node.get(key).stringValue().isBlank())throw new IllegalArgumentException();
            String nonce=node.get("nonce").stringValue();if(!UUID.fromString(nonce).toString().equals(nonce)||!node.get("sha256").stringValue().matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
            if(!Set.of("launching","started").contains(node.get("launchState").stringValue()))throw new IllegalArgumentException();
            if(!node.get("startInstant").isNull()){if(!node.get("startInstant").isString())throw new IllegalArgumentException();Instant.parse(node.get("startInstant").stringValue());}
            if(node.get("pid").longValue()==0&&(!node.get("startInstant").isNull()||!node.get("launchState").stringValue().equals("launching")))throw new IllegalArgumentException();
            if(node.get("pid").longValue()>0&&!node.get("launchState").stringValue().equals("started"))throw new IllegalArgumentException();
            value=JSON.readValue(bytes,Metadata.class);
            if(!Path.of(value.artifactJar()).equals(options.directory().resolve("artifacts").resolve(value.sha256()).resolve("platform-server.jar")))throw new IllegalArgumentException();
        }
        catch(Exception invalid){throw failure("PROCESS_OWNERSHIP_UNCERTAIN","原进程记录无法验证；未停止或替代进程。");}
        if(value.pid()==0)throw failure("PROCESS_OWNERSHIP_UNCERTAIN","上次创建进程的结果未知；保留记录，不能重复启动。");
        Identity identity=ops.identity(value.pid());if(identity==null)return null;
        ensureIdentity(value,identity,java);return value;
    }
    static void ensureIdentity(Metadata value,Identity identity,Path java)throws IOException{
        if(value.startInstant()==null||identity.startInstant()==null||identity.executable()==null||identity.arguments()==null
            ||value.pid()!=identity.pid()||!Instant.parse(value.startInstant()).equals(identity.startInstant())
            ||!identity.executable().toAbsolutePath().normalize().equals(java)||!Path.of(value.java()).equals(java)
            ||!identity.arguments().equals(List.of("-jar",value.artifactJar()))||!sha(Path.of(value.artifactJar())).equals(value.sha256()))
            throw failure("PROCESS_OWNERSHIP_UNCERTAIN","PID/启动代次/Java/固定制品不匹配；未停止或替代进程。");
    }
    static void ready(Options options,Metadata value,Ops ops)throws Exception{
        Path marker=options.directory().resolve("runs").resolve(value.nonce()).resolve("ready.json");
        if(!Files.exists(marker))throw failure("NOT_READY","原进程存在，但实际服务就绪尚未确认。");
        RuntimeFiles.requirePrivateFile(marker);JsonNode ready;
        try{ready=JSON.readTree(limited(marker));}catch(Exception invalid){throw failure("NOT_READY","服务就绪回执无效；未报告就绪。");}
        if(!fields(ready,Set.of("nonce","pid","port","identityMode"))||!ready.path("nonce").isString()||!ready.path("identityMode").isString()
            ||!ready.path("nonce").stringValue().equals(value.nonce())||!integral(ready.path("pid"),1,Long.MAX_VALUE)||ready.path("pid").longValue()!=value.pid()
            ||!integral(ready.path("port"),1024,65535)||ready.path("port").intValue()!=value.port()||!ready.path("identityMode").stringValue().equals("synthetic_only")||!ops.httpReady(value.port()))
            throw failure("NOT_READY","原进程的回执或合成会话未就绪；不会将其他服务当成原服务。");
    }
    static boolean fields(JsonNode node,Set<String> names){
        if(node==null||!node.isObject()||node.size()!=names.size())return false;
        for(String name:names)if(node.get(name)==null)return false;return true;
    }
    static boolean integral(JsonNode node,long from,long to){return node!=null&&node.isIntegralNumber()&&node.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(from))>=0&&node.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(to))<=0;}
    static byte[] limited(Path file)throws IOException{if(Files.size(file)>65536)throw new IOException("Private metadata too large");return Files.readAllBytes(file);}
    interface Work<T>{T run()throws Exception;}
    static <T>T locked(Path directory,Work<T> work)throws Exception{
        RuntimeFiles.requirePrivateDirectory(directory);Path lock=directory.resolve("windows-operation.lock");
        if(!Files.exists(lock))try{RuntimeFiles.writeNewPrivateFile(lock,new byte[0]);}catch(FileAlreadyExistsException race){}
        RuntimeFiles.requirePrivateFile(lock);
        try(var channel=FileChannel.open(lock,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            try(var token=channel.tryLock()){
                if(token==null)throw failure("OPERATION_BUSY","当前运行目录已有操作进行；等待结束后核查状态。");
                return work.run();
            }catch(OverlappingFileLockException busy){throw failure("OPERATION_BUSY","当前运行目录已有操作进行；等待结束后核查状态。");}
        }
    }
    static void metadata(Options options,Metadata metadata)throws IOException{
        Path temporary=options.directory().resolve("process-"+UUID.randomUUID()+".pending");RuntimeFiles.writeNewPrivateFile(temporary,JSON.writeValueAsBytes(metadata));
        if(Files.exists(options.metadata()))RuntimeFiles.requirePrivateFile(options.metadata());
        Files.move(temporary,options.metadata(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    static Path pin(Path jar,Path directory,String sha)throws IOException{
        Path parent=directory.resolve("artifacts").resolve(sha);RuntimeFiles.createPrivateDirectory(parent);Path pinned=parent.resolve("platform-server.jar");
        if(!Files.exists(pinned))RuntimeFiles.writeNewPrivateFile(pinned,Files.readAllBytes(jar));
        RuntimeFiles.requirePrivateFile(pinned);if(!sha(pinned).equals(sha))throw failure("ARTIFACT_HASH","原固定制品摘要不匹配，未覆盖原文件。");return pinned;
    }
    static String sha(Path file)throws IOException{
        try(var stream=Files.newInputStream(file)){MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];for(int n;(n=stream.read(buffer))>=0;)hash.update(buffer,0,n);return HexFormat.of().formatHex(hash.digest());}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException();}
    }
    static void checkJar(Path jar)throws IOException{
        try(var archive=new JarFile(jar.toFile())){
            for(String entry:List.of("BOOT-INF/classes/io/aitest/workbench/bootstrap/RuntimeProbe.class","BOOT-INF/classes/io/aitest/workbench/bootstrap/RuntimeReadiness.class","BOOT-INF/classes/io/aitest/workbench/bootstrap/RuntimeLifecycleControl.class","BOOT-INF/classes/static/index.html","BOOT-INF/classes/db/runner-migration/V1__runner_journal.sql"))
                if(archive.getEntry(entry)==null)throw failure("ARTIFACT_INCOMPLETE","发行Jar缺少必需入口、前端或迁移；请使用维护者提供的完整包。");
        }catch(Failure expected){throw expected;}catch(IOException invalid){throw failure("ARTIFACT_INCOMPLETE","找不到完整发行Jar；请完整解压后运行同目录入口。");}
    }
    static void probe(Ops ops,Map<String,String> config,String stage){
        RuntimeProbe.Result result=ops.probe(config,stage);
        if(!result.ok())throw failure(result.code(),result.message());
    }
    static void migration(Options options,Ops ops,Path java,Path artifact,Map<String,String> config,String location,Map<String,String> inherited)throws Exception{
        Path logs=options.directory().resolve("migration-"+UUID.randomUUID()+".log");RuntimeFiles.writeNewPrivateFile(logs,new byte[0]);
        List<String> command=new ArrayList<>(List.of(java.toString(),"-Dloader.main=io.aitest.workbench.bootstrap.Migrate","-Dloader.config.location=classpath:/workbench-disabled-loader.properties","-Dloader.home="+options.directory(),"-cp",artifact.toString(),"org.springframework.boot.loader.launch.PropertiesLauncher"));if(location!=null)command.add(location);
        if(ops.migration(command,environment(inherited,config,true),logs)!=0)throw failure("MIGRATION_FAILED","独立迁移失败；服务未启动，私有日志保留。先核查结果，不删库或盲目repair。");
    }
    static Map<String,String> configuration(Path file,boolean migration)throws IOException{
        if(!Files.exists(file))throw failure("CONFIG_MISSING","缺少私有配置；先选择配置已有环境，或提供已获准的配置文件。");
        try{RuntimeFiles.requirePrivateFile(file);}catch(IOException invalid){throw failure("CONFIG_PERMISSIONS","私有配置权限无法确认；只使用本人私有文件，不自动修改现有权限。");}JsonNode object;
        try{if(Files.size(file)>65536)throw new IllegalArgumentException();object=JSON.readTree(Files.readAllBytes(file));}catch(Exception invalid){throw failure("CONFIG_INVALID","配置JSON无效或含重复键；不回显原始配置。");}
        Set<String> required=migration?MIGRATION_KEYS:RUNTIME_KEYS;
        if(!object.isObject()||object.size()!=required.size())throw failure("CONFIG_KEYS","配置键缺失或不受支持；请使用本包模板。");
        Map<String,String> values=new HashMap<>();
        for(String key:required){JsonNode node=object.get(key);if(node==null||!node.isString()||node.stringValue().isBlank()||node.stringValue().contains("REPLACE_")||node.stringValue().indexOf('\0')>=0)throw failure("CONFIG_VALUES","先填完模板占位项，配置值须为非空字符串。");values.put(key,node.stringValue());}
        return values;
    }
    static void validate(Map<String,String> values,boolean migration,String database){
        String user=values.get(migration?"WB_MIGRATION_USER":"WB_DB_USER");
        if(!(migration?"workbench_migration":"workbench").equals(user))throw failure("CONFIG_USER","当前合成包只使用已有获准的分权账号；不能用root或交换运行/迁移身份。");
        String url=values.get("WB_DB_URL");
        if(!database.equals("workbench_platform"))url=url.replace("/workbench_runner?","/workbench_platform?");
        Map<String,String> probe=new HashMap<>(Map.of("WB_DB_URL",url,"WB_DB_USER","workbench","WB_DB_PASSWORD",values.get(migration?"WB_MIGRATION_PASSWORD":"WB_DB_PASSWORD"),"SPRING_PROFILES_ACTIVE","synthetic"));
        try{RuntimeProbe.configuration(probe);}catch(RuntimeException invalid){throw failure("CONFIG_TARGET","检查现有回环测试库、TLS受信文件及固定连接参数；不能改连真实业务库。");}
        String original=values.get("WB_DB_URL");
        try{
            URI uri=URI.create(original.substring(5));if(!uri.getPath().equals("/"+database))throw new IllegalArgumentException();
            Map<String,String> query=new HashMap<>();Set<String> allowed=Set.of("sslMode","trustCertificateKeyStoreUrl","trustCertificateKeyStorePassword","trustCertificateKeyStoreType","connectionTimeZone","forceConnectionTimeZoneToSession","connectTimeout","socketTimeout");
            for(String pair:uri.getRawQuery().split("&",-1)){String[] parts=pair.split("=",2);if(parts.length!=2)throw new IllegalArgumentException();String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8);if(!allowed.contains(key)||query.putIfAbsent(key,URLDecoder.decode(parts[1],StandardCharsets.UTF_8))!=null)throw new IllegalArgumentException();}
            if(!Set.of("VERIFY_CA","VERIFY_IDENTITY").contains(query.get("sslMode"))||!"UTC".equals(query.get("connectionTimeZone"))||!"true".equals(query.get("forceConnectionTimeZoneToSession")))throw new IllegalArgumentException();
        }catch(Exception invalid){throw failure("CONFIG_TARGET","运行/迁移数据库范围或固定TLS/UTC连接参数不匹配。");}
        if(!migration){
            if(!"synthetic".equals(values.get("SPRING_PROFILES_ACTIVE")))throw failure("PROFILE_DENIED","当前发行包仅支持合成预览，真实登录尚未实现。");
            try{int port=Integer.parseInt(values.get("WB_PORT"));if(port<1024||port>65535||!Path.of(values.get("WB_BLOB_ROOT")).isAbsolute())throw new IllegalArgumentException();}
            catch(Exception invalid){throw failure("CONFIG_VALUES","服务端口须为1024–65535，文件目录须为本地绝对路径。");}
        }
    }
    static Map<String,String> environment(Map<String,String> inherited,Map<String,String> config,boolean migration){
        Map<String,String> result=new HashMap<>();
        Set<String> unsafe=Set.of("JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS","CLASSPATH","MYSQL_PWD");
        for(var entry:inherited.entrySet())if(!unsafe.contains(entry.getKey().toUpperCase(Locale.ROOT))&&!entry.getKey().toUpperCase(Locale.ROOT).matches("(?:WB_|SPRING_|SERVER_|LOADER_).*"))result.put(entry.getKey(),entry.getValue());
        for(String key:migration?MIGRATION_KEYS:RUNTIME_KEYS)if(config.containsKey(key))result.put(key,config.get(key));
        return result;
    }

    static Result configure(Options options)throws Exception{
        Console console=System.console();if(console==null)throw failure("CONSOLE_REQUIRED","配置密码需要交互控制台，不从命令行参数或管道读取密码。");
        if(Files.exists(options.config())||Files.exists(options.platformMigration())||Files.exists(options.runnerMigration()))throw failure("CONFIG_EXISTS","已有私有配置，未覆盖；请保留原配置并由授权维护者核对修改。");
        console.printf("仅配置已有获准的本地MySQL8.4/TLS/账号；不会安装、建库、建账号或调用真实模型。%n");
        console.printf("普通Windows首装尚未验收；以下输入数量不宣称达到建议的3项预算。%n");
        String dbPort=input(console,"已有MySQL端口", "3306");int port=Integer.parseInt(dbPort);if(port<1||port>65535)throw failure("CONFIG_VALUES","数据库端口无效。");
        Path trust=absolute(input(console,"已有PKCS12受信证书库完整路径",null));if(!Files.isRegularFile(trust)||Files.isSymbolicLink(trust))throw failure("CONFIG_TLS","必须引用已有本地受信证书库，入口不下载或生成证书。");
        String trustPassword=password(console,"证书库现有密码："),runtimePassword=password(console,"workbench现有运行密码："),migrationPassword=password(console,"workbench_migration现有迁移密码：");
        String servicePort=input(console,"本地界面端口","18080");
        String url="jdbc:mysql://127.0.0.1:"+port+"/workbench_platform?sslMode=VERIFY_CA&trustCertificateKeyStoreUrl="+encode(trust.toUri().toString())+"&trustCertificateKeyStorePassword="+encode(trustPassword)+"&trustCertificateKeyStoreType=PKCS12&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        Map<String,String> runtime=Map.of("SPRING_PROFILES_ACTIVE","synthetic","WB_DB_URL",url,"WB_DB_USER","workbench","WB_DB_PASSWORD",runtimePassword,"WB_BLOB_ROOT",options.directory().resolve("blobs").toString(),"WB_PORT",servicePort);
        Map<String,String> platform=Map.of("WB_DB_URL",url,"WB_MIGRATION_USER","workbench_migration","WB_MIGRATION_PASSWORD",migrationPassword),runner=Map.of("WB_DB_URL",url.replace("/workbench_platform?","/workbench_runner?"),"WB_MIGRATION_USER","workbench_migration","WB_MIGRATION_PASSWORD",migrationPassword);
        validate(runtime,false,"workbench_platform");validate(platform,true,"workbench_platform");validate(runner,true,"workbench_runner");
        RuntimeFiles.createPrivateDirectory(options.directory());
        return locked(options.directory(),()->{
            if(Files.exists(options.config())||Files.exists(options.platformMigration())||Files.exists(options.runnerMigration()))throw failure("CONFIG_EXISTS","并发操作已建立配置；未覆盖原文件。");
            RuntimeFiles.writeNewPrivateFile(options.platformMigration(),JSON.writeValueAsBytes(platform));RuntimeFiles.writeNewPrivateFile(options.runnerMigration(),JSON.writeValueAsBytes(runner));RuntimeFiles.writeNewPrivateFile(options.config(),JSON.writeValueAsBytes(runtime));
            return Result.ok("CONFIGURED","已保存现有账号的私有配置，未连接数据库或创建任何资源。先检查环境；如获准需要迁移，显式使用 up --initialize。","stopped",null);
        });
    }
    static String input(Console console,String label,String fallback){String value=console.readLine(label+(fallback==null?"":" ["+fallback+"]")+"：");if(value==null||value.isBlank()){if(fallback==null)throw failure("CONFIG_VALUES","配置未填写完整，未写配置。");return fallback;}return value.strip();}
    static String password(Console console,String label){char[] value=console.readPassword(label);if(value==null||value.length==0)throw failure("CONFIG_VALUES","现有密码未提供，未写配置。");try{return new String(value);}finally{Arrays.fill(value,'\0');}}
    static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}

    static final class NativeOps implements Ops {
        public RuntimeProbe.Result probe(Map<String,String> environment,String stage){return quiet(()->RuntimeProbe.run(environment,stage));}
        public Identity identity(long pid){
            Optional<ProcessHandle> process=ProcessHandle.of(pid);if(process.isEmpty()||!process.get().isAlive())return null;
            ProcessHandle.Info info=process.get().info();
            return new Identity(pid,info.startInstant().orElse(null),info.command().map(Path::of).orElse(null),info.arguments().map(List::of).orElse(null));
        }
        public long start(List<String> command,Map<String,String> environment,Path log)throws IOException{return builder(command,environment,log).start().pid();}
        public int migration(List<String> command,Map<String,String> environment,Path log)throws IOException,InterruptedException{return builder(command,environment,log).start().waitFor();}
        private ProcessBuilder builder(List<String> command,Map<String,String> environment,Path log){ProcessBuilder builder=new ProcessBuilder(command).directory(log.getParent().toFile());builder.environment().clear();builder.environment().putAll(environment);return builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));}
        public void checkPort(int port)throws IOException{try(ServerSocket socket=new ServerSocket()){socket.setReuseAddress(true);socket.bind(new InetSocketAddress("127.0.0.1",port));}catch(IOException unavailable){throw failure("PORT_IN_USE","本地端口被占用；先核查原服务，不停止其他进程。");}}
        public boolean httpReady(int port){
            try(HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()){
                HttpResponse<String> result=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/session")).timeout(Duration.ofSeconds(2)).header("X-Test-Subject","admin").GET().build(),HttpResponse.BodyHandlers.ofString());
                JsonNode body=JSON.readTree(result.body());return result.statusCode()==200&&result.headers().firstValue("Cache-Control").orElse("").equals("no-store")&&body.path("identityMode").asString().equals("synthetic_only")&&body.path("state").asString().equals("active");
            }catch(Exception notReady){return false;}
        }
        public long nanos(){return System.nanoTime();}
        public void pause()throws InterruptedException{Thread.sleep(200);}
    }
    static <T>T quiet(Supplier<T> operation){PrintStream out=System.out,err=System.err;try(PrintStream sink=new PrintStream(OutputStream.nullOutputStream())){System.setOut(sink);System.setErr(sink);return operation.get();}finally{System.setOut(out);System.setErr(err);}}
}
