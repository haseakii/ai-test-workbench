package io.aitest.workbench.bootstrap;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Dedicated read-only CLI; never loads Spring, starts workers, migrates or repairs. */
public final class RuntimeProbe {
    private static final Map<String,String> QUERY_KEYS=Map.of("sslmode","sslMode","trustcertificatekeystoreurl","trustCertificateKeyStoreUrl",
        "trustcertificatekeystorepassword","trustCertificateKeyStorePassword","trustcertificatekeystoretype","trustCertificateKeyStoreType","connectiontimezone","connectionTimeZone",
        "forceconnectiontimezonetosession","forceConnectionTimeZoneToSession","connecttimeout","connectTimeout","sockettimeout","socketTimeout");
    private RuntimeProbe(){}

    public record Result(boolean ok,String code,String message,String stage,Integer platformVersion) {
        public String json(){
            return "{\"ok\":"+ok+",\"code\":\""+code+"\",\"message\":\""+message
                +"\",\"stage\":\""+stage+"\",\"platformVersion\":"+platformVersion+"}";
        }
    }
    record Config(String url,String user,String password){
        @Override public String toString(){return "RuntimeProbe.Config[redacted]";}
    }
    private static final class Invalid extends RuntimeException {
        final String code,message;
        Invalid(String code,String message){this.code=code;this.message=message;}
    }

    public static void main(String[] args){
        // JDBC/Flyway logs can contain connection URLs. This process emits only the fixed result.
        PrintStream out=System.out,err=System.err;
        Result result;
        try(var silent=new PrintStream(OutputStream.nullOutputStream())){
            System.setOut(silent);System.setErr(silent);
            result=run(System.getenv(),args.length==1?args[0]:"arguments");
        }finally{System.setOut(out);System.setErr(err);}
        out.println(result.json());
        if(!result.ok())System.exit(2);
    }

    public static Result run(Map<String,String> environment,String stage){
        return run(environment,stage,RuntimeProbe::source,SchemaReadiness::platform);
    }
    static Result run(Map<String,String> environment,String stage,Function<Config,DataSource> factory,Consumer<DataSource> schema){
        if(!Set.of("database","schema").contains(stage))return failure("arguments","PROBE_ARGUMENTS","使用 database 或 schema 检查阶段。");
        try{
            Config config=configuration(environment);
            var dataSource=readOnly(factory.apply(config));
            try(var connection=dataSource.getConnection();var statement=connection.createStatement()){
                statement.setQueryTimeout(5);
                try(var result=statement.executeQuery("SELECT VERSION(),DATABASE(),@@session.transaction_isolation,@@session.time_zone,@@session.sql_mode,@@session.default_storage_engine")){
                    if(!result.next()||!result.getString(1).startsWith("8.4.")||!"workbench_platform".equals(result.getString(2))
                        ||!"READ-COMMITTED".equals(result.getString(3))||!"+00:00".equals(result.getString(4))
                        ||!(Set.of(result.getString(5).split(",")).contains("STRICT_TRANS_TABLES")||Set.of(result.getString(5).split(",")).contains("STRICT_ALL_TABLES"))
                        ||!"InnoDB".equalsIgnoreCase(result.getString(6)))
                        return failure(stage,"DATABASE_INCOMPATIBLE","需要 MySQL 8.4、InnoDB、严格模式以及 UTC 和 READ-COMMITTED 会话。");
                }
                try(var result=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND engine<>'InnoDB'")){
                    if(!result.next()||result.getLong(1)!=0)return failure(stage,"DATABASE_INCOMPATIBLE","平台表必须使用 InnoDB；预检不会修改已有表。");
                }
                if(stage.equals("schema")){
                    try(var result=statement.executeQuery("SELECT mode_name FROM synthetic_boundary WHERE id=1")){
                        if(!result.next()||!"isolated_synthetic_only".equals(result.getString(1)))
                            return failure(stage,"DATABASE_NOT_SYNTHETIC","需要已授权的合成数据库标记；不要连接真实业务库。");
                    }catch(SQLException invalidMarker){return failure(stage,"DATABASE_NOT_SYNTHETIC","缺少合成数据库标记；请检查获准环境与迁移状态。");}
                }
            }
            if(stage.equals("schema")){
                try{schema.accept(dataSource);}
                catch(RuntimeException notReady){return failure(stage,"SCHEMA_NOT_READY","目标迁移历史或校验和未就绪；通过独立迁移步骤核查，不要清库或盲目 repair。");}
            }
            return new Result(true,"READY",stage.equals("schema")?"合成数据库和平台迁移门禁通过。":"数据库依赖检查通过；尚未声明迁移就绪。",stage,stage.equals("schema")?SchemaReadiness.PLATFORM_VERSION:null);
        }catch(Invalid invalid){return failure(stage,invalid.code,invalid.message);}
        catch(SQLException sql){
            String state=sql.getSQLState();
            if(state!=null&&state.startsWith("28"))return failure(stage,"DATABASE_AUTH","数据库认证失败；检查现有运行账号配置，不要扩大权限。");
            if(state!=null&&state.startsWith("08"))return failure(stage,"DATABASE_UNREACHABLE","无法连接获准的本地 MySQL；检查服务、端口及受信证书。");
            if(state!=null&&state.startsWith("42"))return failure(stage,"DATABASE_PERMISSION","运行账号读取检查被拒绝；核对已批准的数据库与权限范围。");
            return failure(stage,"DATABASE_CHECK_FAILED","数据库只读检查失败；检查私有运行日志与已批准配置。");
        }catch(Exception invalid){return failure(stage,"PROBE_FAILED","预检未完成；检查 Java、受信 JDBC 依赖和本地配置。");}
    }
    private static Result failure(String stage,String code,String message){return new Result(false,code,message,stage,null);}

    static Config configuration(Map<String,String> environment){
        for(String key:Set.of("WB_DB_URL","WB_DB_USER","WB_DB_PASSWORD","SPRING_PROFILES_ACTIVE"))
            if(environment.get(key)==null||environment.get(key).isBlank())throw new Invalid("CONFIG_MISSING","缺少数据库运行配置；请按模板填写仓库外的私有配置。");
        if(!"synthetic".equals(environment.get("SPRING_PROFILES_ACTIVE")))throw new Invalid("CONFIG_PROFILE","当前入口只支持显式 synthetic 模式。");
        String user=environment.get("WB_DB_USER");
        if(user.equalsIgnoreCase("root")||!user.matches("[a-zA-Z0-9_-]{1,32}"))throw new Invalid("CONFIG_USER","使用已授权的平台最小权限运行账号，不能使用 root。");
        String url=environment.get("WB_DB_URL");URI target;
        try{
            if(!url.startsWith("jdbc:mysql://"))throw new IllegalArgumentException();
            target=URI.create(url.substring(5));
            if(!"127.0.0.1".equals(target.getHost())||target.getUserInfo()!=null||target.getFragment()!=null
                ||!"/workbench_platform".equals(target.getPath())||(target.getPort()!=-1&&(target.getPort()<1||target.getPort()>65535)))throw new IllegalArgumentException();
        }catch(IllegalArgumentException invalid){throw new Invalid("CONFIG_TARGET","仅允许已授权的回环 workbench_platform 数据库。");}
        var parameters=new HashMap<String,String>();var forwarded=new StringBuilder();
        try{
            if(target.getRawQuery()!=null)for(String pair:target.getRawQuery().split("&",-1)){
                String[] parts=pair.split("=",2);if(parts.length!=2)throw new IllegalArgumentException();
                String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
                if(!QUERY_KEYS.containsKey(key)||parameters.putIfAbsent(key,URLDecoder.decode(parts[1],StandardCharsets.UTF_8))!=null)throw new IllegalArgumentException();
                // CLI owns short connection/read timeouts, never trusts a URL's unbounded timeout.
                // Canonical spelling matters: unknown-cased JDBC property keys can otherwise be ignored.
                if(!Set.of("connecttimeout","sockettimeout").contains(key))forwarded.append(QUERY_KEYS.get(key)).append('=').append(key.equals("sslmode")?parameters.get(key).toUpperCase(java.util.Locale.ROOT):parts[1]).append('&');
            }
        }catch(IllegalArgumentException invalid){throw new Invalid("CONFIG_TARGET","JDBC 参数无效或包含未允许的覆盖项；请使用配置模板。");}
        if(!Set.of("VERIFY_CA","VERIFY_IDENTITY").contains(parameters.getOrDefault("sslmode","").toUpperCase(java.util.Locale.ROOT)))
            throw new Invalid("CONFIG_TLS","MySQL TLS 必须验证受信 CA 或身份；不要关闭证书校验。");
        if(parameters.containsKey("trustcertificatekeystoreurl")){
            try{var trust=URI.create(parameters.get("trustcertificatekeystoreurl"));if(!"file".equals(trust.getScheme())||trust.getAuthority()!=null||trust.getPath()==null||!trust.getPath().startsWith("/"))throw new IllegalArgumentException();}
            catch(IllegalArgumentException invalid){throw new Invalid("CONFIG_TLS","证书信任库必须引用已配置的本地受信文件。");}
        }
        String bounded="jdbc:mysql://127.0.0.1"+(target.getPort()==-1?"":":"+target.getPort())+"/workbench_platform?"+forwarded+"connectTimeout=3000&socketTimeout=5000";
        return new Config(bounded,user,environment.get("WB_DB_PASSWORD"));
    }
    private static DataSource source(Config config){return new DriverManagerDataSource(config.url(),config.user(),config.password());}
    private static DataSource readOnly(DataSource delegate){
        return new AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException{
                var connection=delegate.getConnection();
                try{
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    try(var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.execute("SET SESSION time_zone='+00:00'");}
                    connection.setReadOnly(true);
                    return connection;
                }catch(SQLException failure){connection.close();throw failure;}
            }
            @Override public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("Alternate credentials are not supported","28000");}
        };
    }
}
