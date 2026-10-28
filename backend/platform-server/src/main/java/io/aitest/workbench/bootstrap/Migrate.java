package io.aitest.workbench.bootstrap;

import org.flywaydb.core.Flyway;

/** Run separately from the Web process with task-local migration credentials. */
public final class Migrate {
    public static void main(String[] args) {
        String url=System.getenv("WB_DB_URL"),user=System.getenv("WB_MIGRATION_USER"),password=System.getenv("WB_MIGRATION_PASSWORD");
        if(url==null||user==null||password==null)throw new IllegalStateException("Explicit migration configuration required");
        String location=args.length==0?"classpath:db/migration":args[0];
        var result=Flyway.configure().dataSource(url,user,password).locations(location).cleanDisabled(true).load().migrate();
        System.out.println("Migrations applied: "+result.migrationsExecuted);
    }
}
