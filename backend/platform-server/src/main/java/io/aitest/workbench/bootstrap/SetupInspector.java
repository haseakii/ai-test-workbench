package io.aitest.workbench.bootstrap;

import io.aitest.contract.CanonicalJson;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only installation review. Intentionally has no downloader, process runner or SQL executor. */
public final class SetupInspector {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final List<String> INPUTS=List.of("dependencies.json","bootstrap-actions.json","bootstrap.sql.template","initialize.ini.template","my.ini.template");
    private static final Set<String> IDS=Set.of("java","mysql","msvc");
    private SetupInspector(){}
    public record Check(String id,String state,String message){}
    public record Input(String file,long bytes,String sha256){}
    public record Report(String format,String code,boolean readOnly,boolean canInitialize,boolean executionAuthorized,
        String recommendedRoute,String proposedDataPath,String hostOs,int javaFeature,List<Check> checks,List<Input> inputs,
        JsonNode routes,JsonNode fixedScope,JsonNode actions,JsonNode requiredApprovals,String planSha256){}
    static final class Invalid extends RuntimeException {
        final String code;
        Invalid(String code,String message){super(message);this.code=code;}
    }

    public static void main(String[] args){int code=execute(args,System.getenv(),System.out);if(code!=0)System.exit(code);}
    static int execute(String[] args,Map<String,String> environment,PrintStream out){
        try{
            Path bundle=Path.of(System.getProperty("workbench.bundle",".")).toAbsolutePath().normalize();
            Path target=null;boolean machineReadable=false;Set<String> seen=new HashSet<>();
            for(int i=0;i<args.length;i++){
                String key=args[i];if(!seen.add(key))throw new Invalid("SETUP_ARGUMENTS","首装预检参数重复。");
                if(key.equals("--json")){machineReadable=true;continue;}
                if(!Set.of("--bundle-dir","--runtime-dir").contains(key)||++i>=args.length)throw new Invalid("SETUP_ARGUMENTS","首装预检只接受本地目录与 --json，不接受密码或执行选项。");
                Path value=Path.of(args[i]).toAbsolutePath().normalize();
                if(key.equals("--bundle-dir"))bundle=value;else target=value;
            }
            String base=environment.get("LOCALAPPDATA");
            if(target==null)target=base==null||base.isBlank()?Path.of(System.getProperty("user.home"),".ai-test-workbench","portable-proposed"):Path.of(base,"AiTestWorkbench","portable");
            Report report=inspect(bundle,target.toAbsolutePath().normalize(),System.getProperty("os.name","unknown"));
            if(machineReadable)out.println(JSON.writeValueAsString(report));
            else human(report,out);
            return 2; // This batch never authorizes a dependency run or an initialization.
        }catch(Invalid invalid){out.println(JSON.writeValueAsString(Map.of("code",invalid.code,"message",invalid.getMessage(),"readOnly",true,"canInitialize",false)));return 2;}
        catch(Exception failure){out.println(JSON.writeValueAsString(Map.of("code","SETUP_INSPECTION_FAILED","message","首装预检未完成；未创建目录或初始化，请核对方案文件和读取权限。","readOnly",true,"canInitialize",false)));return 2;}
    }

    static Report inspect(Path bundle,Path target,String hostOs)throws IOException{
        rejectLinks(bundle);rejectLinks(target);
        Path directory=Files.isDirectory(bundle.resolve("setup"))?bundle.resolve("setup"):bundle.resolve("backend/distribution/setup");
        rejectLinks(directory);
        var inputs=new ArrayList<Input>();Map<String,byte[]> data=new HashMap<>();
        for(String name:INPUTS){
            Path file=directory.resolve(name);rejectLinks(file);
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>256*1024)throw new Invalid("SETUP_INPUT_MISSING","完整首装方案文件缺失或超限；不要自行去除模板守卫。");
            byte[] bytes=Files.readAllBytes(file);inputs.add(new Input(name,bytes.length,CanonicalJson.hashBytes(bytes)));data.put(name,bytes);
        }
        JsonNode catalog=object(data.get("dependencies.json")),plan=object(data.get("bootstrap-actions.json"));
        if(!catalog.path("format").asString().equals("workbench-portable-dependencies-v1")
            ||!catalog.path("recommendedRoute").asString().equals("portable-private-mysql")
            ||!exactFalse(catalog,"allowedToExecute")||!exactFalse(catalog,"dependencyDownloadAllowed")||!exactFalse(catalog,"dependencyBundlingAllowed")
            ||!plan.path("format").asString().equals("workbench-portable-bootstrap-plan-v1")
            ||!exactFalse(plan,"executable")||!exactFalse(plan,"executorIncluded")||!exactFalse(plan,"executionAuthorized"))
            throw new Invalid("SETUP_PLAN_NOT_READONLY","方案必须为未授权的只读准备，不能通过修改JSON启用执行。");
        JsonNode components=catalog.get("components");
        if(components==null||!components.isArray()||components.size()!=3)throw new Invalid("SETUP_CATALOG_INVALID","依赖目录结构无效。");
        var checks=new ArrayList<Check>();var seen=new HashSet<String>();
        for(JsonNode component:components){
            String id=component.path("id").asString();if(!IDS.contains(id)||!seen.add(id))throw new Invalid("SETUP_CATALOG_INVALID","依赖目录存在未知或重复项。");
            JsonNode artifact=component.get("artifact");
            if(artifact==null||!artifact.isObject()||!artifact.path("approvalStatus").asString().equals("not_approved"))
                throw new Invalid("SETUP_ARTIFACT_NOT_APPROVED","本准备批次不接受软件批准标志，仍需核对官方版本、摘要和具体授权。");
            if(id.equals("msvc")){
                checks.add(new Check("native-runtime","UNVERIFIED","MSVC/UCRT 是否已具备、所选MySQL是否含所需DLL尚未实机确认；不能自动安装或复制System32。"));continue;
            }
            String expected=id.equals("java")?"runtime/java/bin/java.exe":"runtime/mysql/bin/mysqld.exe";
            if(!component.path("expectedRelativeExecutable").asString().equals(expected))throw new Invalid("SETUP_CATALOG_INVALID","依赖只能使用约定的应用私有相对路径。");
            Path executable=bundle.resolve(expected);rejectLinks(executable);
            boolean present=Files.isRegularFile(executable,LinkOption.NOFOLLOW_LINKS);
            checks.add(new Check(id+"-files",present?"UNVERIFIED":"MISSING",present?"已检测文件，但未核验版本/来源/摘要且未获运行授权；不会启动。":"尚未包含受信Windows依赖；不会下载或运行。"));
            checks.add(new Check(id+"-artifact","BLOCKED","官方Windows精确版本、包摘要及重分发确认尚未完成；参考Linux版本不能代替。"));
        }
        checks.add(new Check("windows-host",hostOs.toLowerCase(Locale.ROOT).startsWith("windows")?"UNVERIFIED":"MISSING","需要用户指定的Windows验收机器；本次方案输出不证明主机、NTFS权限或进程能力已验收。"));
        Path parent=target;while(parent!=null&&!Files.exists(parent,LinkOption.NOFOLLOW_LINKS))parent=parent.getParent();
        String filesystem=parent==null?"unknown":Files.getFileStore(parent).type();
        checks.add(new Check("data-filesystem","UNVERIFIED","目标需本地NTFS/私有ACL；只读观察到的文件系统为 "+filesystem+"，未修改任何权限。"));
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS))throw new Invalid("SETUP_TARGET_OCCUPIED","目标不是普通目录；未写入或改用其他路径。");
            try(var listing=Files.list(target)){
                checks.add(new Check("new-data",listing.findAny().isPresent()?"BLOCKED":"UNVERIFIED","已有目录不能自动接纳为新实例；须核对拥有者和无原有数据，不能清空后重试。"));
            }
        }else checks.add(new Check("new-data","UNVERIFIED","当前建议路径不存在；未创建，也未选择或批准用户的某台机器。"));
        checks.add(new Check("secret-storage","UNVERIFIED","新凭据须Windows当前用户DPAPI和私有ACL；系统PowerShell/策略需可用，失败不回落明文。"));
        checks.add(new Check("bootstrap","BLOCKED","初始化执行器未接通，模板不可直接运行；新实例/账号/TLS/凭据/端口范围均待明确批准。"));
        if(!plan.path("actions").isArray()||!plan.path("requiredApprovals").isArray()||!plan.path("fixedScope").isObject()||!catalog.path("routes").isObject())
            throw new Invalid("SETUP_PLAN_INVALID","首装动作/确认范围不完整。");
        // Hash the entire reviewed source inputs and target, not a mutable summary alone.
        var scope=new LinkedHashMap<String,Object>();scope.put("format","workbench-portable-review-v1");scope.put("route","portable-private-mysql");
        scope.put("target",target.toString());scope.put("hostOs",hostOs);scope.put("inputs",inputs);scope.put("executionAuthorized",false);
        String hash=CanonicalJson.hash(JSON.writeValueAsString(scope));
        return new Report("workbench-portable-review-v1","SETUP_PREPARATION_ONLY",true,false,false,"portable-private-mysql",
            target.toString(),hostOs,Runtime.version().feature(),List.copyOf(checks),List.copyOf(inputs),
            catalog.get("routes"),plan.get("fixedScope"),plan.get("actions"),plan.get("requiredApprovals"),hash);
    }
    private static JsonNode object(byte[] data){try{JsonNode value=JSON.readTree(data);if(!value.isObject())throw new IllegalArgumentException();return value;}catch(Exception failure){throw new Invalid("SETUP_JSON_INVALID","方案JSON无效、重复字段或含尾随内容；未执行任何动作。");}}
    private static boolean exactFalse(JsonNode node,String field){return node.has(field)&&node.get(field).isBoolean()&&!node.get(field).booleanValue();}
    private static void rejectLinks(Path path)throws IOException{
        if(path==null||!path.isAbsolute()||!path.equals(path.normalize())||path.toString().startsWith("\\\\"))throw new Invalid("SETUP_PATH_INVALID","仅检查本地规范路径，不使用UNC或路径逃逸。");
        for(Path current=path;current!=null;current=current.getParent())if(Files.isSymbolicLink(current))throw new Invalid("SETUP_PATH_INVALID","方案或目标路径包含链接，未读取或初始化。");
    }
    private static void human(Report report,PrintStream out){
        out.println("Windows 首装准备（只读，不会下载、安装或初始化）");
        out.println("当前查看的建议数据目录（未创建，未选择Windows机器）："+report.proposedDataPath());
        out.println("推荐一条主路线：完整便携包 → 解压 → 双击 → 确认首次私有实例范围。");
        out.println("目标是3个用户动作、无需手填数据库密码；目前未完成软件核验/授权及Windows验收，不能称为已实现三步首装。");
        for(Check check:report.checks())out.println("["+check.state()+"] "+check.message());
        out.println("已有Java/MySQL/TLS/账号仅作兼容：仍有6提示、4必填，并保留管理员准备工作，不能当作简单搭建完成。");
        out.println("后续需确认：具体Windows机器/私有数据目录，官方软件包与摘要，创建两库三分权账号及TLS/DPAPI保护的范围。");
        out.println("不会修改PATH/服务/防火墙/已有数据库；初始化结果未知时保留原数据与拥有者，不自动重试或清理。");
    }
}
