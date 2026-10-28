package io.aitest.workbench.bootstrap;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import java.util.*;
import java.util.stream.IntStream;

/** Read-only startup gate. Migration credentials, repair and DDL are never used here. */
public final class SchemaReadiness {
    public static final int PLATFORM_VERSION=7;
    private SchemaReadiness(){}
    public static void platform(DataSource dataSource){verify(dataSource,"classpath:db/migration","flyway_schema_history",PLATFORM_VERSION);}
    public static void verify(DataSource dataSource,String location,String historyTable,int target){
        try{
            Objects.requireNonNull(dataSource);
            if(target<1||!historyTable.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException();
            var flyway=Flyway.configure().dataSource(dataSource).locations(location).table(historyTable)
                .cleanDisabled(true).ignoreMigrationPatterns(new String[0]).load();
            var applied=flyway.info().applied();
            var versions=Arrays.stream(applied).filter(m->!(m.getState()==MigrationState.BASELINE&&m.getVersion()!=null&&m.getVersion().getVersion().equals("0"))).map(m->{
                if(m.getState()!=MigrationState.SUCCESS||m.getVersion()==null)throw new IllegalStateException();
                return m.getVersion().getVersion();
            }).toList();
            var expected=IntStream.rangeClosed(1,target).mapToObj(Integer::toString).toList();
            if(!versions.equals(expected)||!flyway.validateWithResult().validationSuccessful)throw new IllegalStateException();
        }catch(Exception failure){
            // Do not expose JDBC URLs, SQL or credentials in a readiness response/log message.
            throw new IllegalStateException("Schema not ready: exact successful migration history and checksums required; run the separate migration process.");
        }
    }
}
