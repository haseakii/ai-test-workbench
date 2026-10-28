package io.aitest.workbench.bootstrap;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeProbeTest {
    private final String url="jdbc:mysql://127.0.0.1:13306/workbench_platform?sslMode=VERIFY_CA&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    private Map<String,String> environment(){return new HashMap<>(Map.of("WB_DB_URL",url,"WB_DB_USER","workbench","WB_DB_PASSWORD","private-runtime-password","SPRING_PROFILES_ACTIVE","synthetic"));}
    private RuntimeProbe.Result withoutConnection(Map<String,String> environment,String stage){return RuntimeProbe.run(environment,stage,config->{fail("Invalid configuration must not open a database connection");return null;},source->fail("Unexpected schema inspection"));}
    private record Fake(DataSource source,Connection connection,Statement statement,ResultSet facts,ResultSet marker){}
    private Fake fake()throws Exception{
        var source=mock(DataSource.class);var connection=mock(Connection.class);var statement=mock(Statement.class);var facts=mock(ResultSet.class);var engine=mock(ResultSet.class);var marker=mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(call->{String sql=call.getArgument(0);if(sql.startsWith("SELECT VERSION"))return facts;if(sql.startsWith("SELECT COUNT"))return engine;if(sql.startsWith("SELECT mode_name"))return marker;throw new SQLException("Unexpected SQL","42000");});
        when(facts.next()).thenReturn(true);when(facts.getString(1)).thenReturn("8.4.13");when(facts.getString(2)).thenReturn("workbench_platform");when(facts.getString(3)).thenReturn("READ-COMMITTED");when(facts.getString(4)).thenReturn("+00:00");when(facts.getString(5)).thenReturn("STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION");when(facts.getString(6)).thenReturn("InnoDB");
        when(engine.next()).thenReturn(true);when(engine.getLong(1)).thenReturn(0L);when(marker.next()).thenReturn(true);when(marker.getString(1)).thenReturn("isolated_synthetic_only");
        return new Fake(source,connection,statement,facts,marker);
    }
    private RuntimeProbe.Result run(Fake fake,String stage,Consumer<DataSource> schema){return RuntimeProbe.run(environment(),stage,config->fake.source(),schema);}

    @Test void rejectsUnknownStageWithoutOpeningDatabase(){assertEquals("PROBE_ARGUMENTS",withoutConnection(environment(),"repair").code());}
    @Test void requiresAllExplicitRuntimeConfiguration(){
        for(String key:List.of("WB_DB_URL","WB_DB_USER","WB_DB_PASSWORD","SPRING_PROFILES_ACTIVE")){
            var env=environment();env.remove(key);assertEquals("CONFIG_MISSING",withoutConnection(env,"database").code());
        }
    }
    @Test void rejectsProductionOrCombinedProfilesBeforeConnecting(){
        for(String profile:List.of("production","synthetic,production"," synthetic ")){
            var env=environment();env.put("SPRING_PROFILES_ACTIVE",profile);assertEquals("CONFIG_PROFILE",withoutConnection(env,"database").code());
        }
    }
    @Test void rejectsRootAndMalformedRuntimeUsers(){
        for(String user:List.of("root","ROOT","workbench root","workbench@localhost")){
            var env=environment();env.put("WB_DB_USER",user);assertEquals("CONFIG_USER",withoutConnection(env,"database").code());
        }
    }
    @Test void rejectsRemoteDifferentDatabaseAndEmbeddedCredentials(){
        for(String candidate:List.of("jdbc:mysql://production.invalid/workbench_platform?sslMode=VERIFY_CA","jdbc:mysql://127.0.0.1/workbench_runner?sslMode=VERIFY_CA","jdbc:mysql://u:p@127.0.0.1/workbench_platform?sslMode=VERIFY_CA","jdbc:mysql://127.0.0.1/workbench_platform?sslMode=VERIFY_CA#fragment","jdbc:h2:mem:test")){
            var env=environment();env.put("WB_DB_URL",candidate);assertEquals("CONFIG_TARGET",withoutConnection(env,"database").code());
        }
    }
    @Test void rejectsIdentityOverridesDynamicDriversAndDuplicateParameters(){
        for(String suffix:List.of("&user=root","&password=secret","&socketFactory=unexpected.Factory","&queryInterceptors=unexpected.Code","&sessionVariables=sql_mode=''","&sslMode=DISABLED","&unknown=x")){
            var env=environment();env.put("WB_DB_URL",url+suffix);assertEquals("CONFIG_TARGET",withoutConnection(env,"database").code());
        }
    }
    @Test void tlsVerificationCannotBeDisabled(){
        for(String mode:List.of("PREFERRED","REQUIRED","DISABLED","")){
            var env=environment();env.put("WB_DB_URL","jdbc:mysql://127.0.0.1:13306/workbench_platform?sslMode="+mode);assertEquals("CONFIG_TLS",withoutConnection(env,"database").code());
        }
    }
    @Test void ownsFiniteTimeoutsWithoutExposingCredentialConfig(){
        var env=environment();env.put("WB_DB_URL",url+"&connectTimeout=0&socketTimeout=0&trustCertificateKeyStorePassword=url-private-secret");
        var config=RuntimeProbe.configuration(env);
        assertTrue(config.url().endsWith("connectTimeout=3000&socketTimeout=5000"));assertFalse(config.url().contains("Timeout=0"));
        assertFalse(config.toString().contains("secret"));assertFalse(config.toString().contains("private-runtime-password"));
    }
    @Test void canonicalizesTlsPropertyNamesAndRejectsRemoteTruststores(){
        var env=environment();env.put("WB_DB_URL","jdbc:mysql://127.0.0.1/workbench_platform?SSLMODE=verify_ca");
        assertTrue(RuntimeProbe.configuration(env).url().contains("sslMode=VERIFY_CA"));
        env.put("WB_DB_URL",url+"&trustCertificateKeyStoreUrl=https://unexpected.invalid/trust.p12");
        assertEquals("CONFIG_TLS",withoutConnection(env,"database").code());
    }
    @Test void databaseProbeIsReadOnlyAndDoesNotClaimSchemaReady()throws Exception{
        var fake=fake();var result=run(fake,"database",source->fail("Dependency probe must not inspect or migrate schema"));
        assertTrue(result.ok());assertNull(result.platformVersion());
        verify(fake.connection()).setReadOnly(true);verify(fake.connection()).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(fake.statement()).execute("SET SESSION time_zone='+00:00'");
        verify(fake.statement(),never()).executeUpdate(anyString());verify(fake.connection(),never()).commit();verify(fake.connection(),never()).rollback();
        verify(fake.statement(),never()).executeQuery("SELECT mode_name FROM synthetic_boundary WHERE id=1");
    }
    @Test void schemaProbeUsesReadOnlyConnectionsAndCurrentPlatformGate()throws Exception{
        var fake=fake();var inspections=new AtomicInteger();
        var result=run(fake,"schema",source->{inspections.incrementAndGet();try(var ignored=source.getConnection()){}catch(SQLException failure){throw new AssertionError(failure);}});
        assertTrue(result.ok());assertEquals(SchemaReadiness.PLATFORM_VERSION,result.platformVersion());assertEquals(1,inspections.get());
        verify(fake.connection(),times(2)).setReadOnly(true);verify(fake.connection(),times(2)).close();
    }
    @Test void rejectsIncompatibleVersionEngineIsolationTimezoneAndStrictMode()throws Exception{
        for(var mutation:Map.of(1,"8.0.45",2,"company",3,"REPEATABLE-READ",4,"SYSTEM",5,"NO_ENGINE_SUBSTITUTION",6,"MyISAM").entrySet()){
            var fake=fake();when(fake.facts().getString(mutation.getKey())).thenReturn(mutation.getValue());
            assertEquals("DATABASE_INCOMPATIBLE",run(fake,"database",source->fail("Not schema ready")).code());
        }
    }
    @Test void schemaRequiresExistingSyntheticMarkerAndNeverCreatesIt()throws Exception{
        var fake=fake();when(fake.marker().getString(1)).thenReturn("production");
        assertEquals("DATABASE_NOT_SYNTHETIC",run(fake,"schema",source->fail("Wrong marker must stop before schema validation")).code());
        verify(fake.statement(),never()).executeUpdate(anyString());
    }
    @Test void schemaFailuresReturnActionableFixedErrorWithoutRepairOrSecrets()throws Exception{
        var result=run(fake(),"schema",source->{throw new IllegalStateException("jdbc:mysql://private.invalid?password=hidden-secret");});
        assertFalse(result.ok());assertEquals("SCHEMA_NOT_READY",result.code());assertTrue(result.message().contains("不要清库"));assertFalse(result.json().contains("hidden-secret"));assertFalse(result.json().contains("jdbc:"));
    }
    @Test void authenticationConnectionAndPermissionFailuresStayDistinctAndRedacted()throws Exception{
        for(var entry:Map.of("28000","DATABASE_AUTH","08S01","DATABASE_UNREACHABLE","42000","DATABASE_PERMISSION","HY000","DATABASE_CHECK_FAILED").entrySet()){
            var source=mock(DataSource.class);when(source.getConnection()).thenThrow(new SQLException("private-secret jdbc:mysql://private/path",entry.getKey()));
            var result=RuntimeProbe.run(environment(),"database",config->source,db->fail("Unexpected schema call"));
            assertEquals(entry.getValue(),result.code());assertFalse(result.json().contains("private-secret"));assertFalse(result.json().contains("jdbc:"));
        }
    }
    @Test void closesConnectionWhenSessionConfigurationFails()throws Exception{
        var fake=fake();doThrow(new SQLException("private-secret","08S01")).when(fake.connection()).setReadOnly(true);
        assertEquals("DATABASE_UNREACHABLE",run(fake,"database",source->fail("Unexpected schema call")).code());verify(fake.connection()).close();
    }
}
