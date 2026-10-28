package io.aitest.workbench;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.*;
import java.nio.file.*;

/** Only launched by MigrationReadinessTest against its unique prefix and existing approved DB. */
public final class MigrationProbeProcess {
    public static void main(String[] args){
        if(args.length!=3||!args[1].matches("a01_[a-f0-9]{12}_history"))throw new IllegalArgumentException();
        var callback=new Callback(){
            public boolean supports(Event e,Context c){return e==Event.AFTER_EACH_MIGRATE;}
            public boolean canHandleInTransaction(Event e,Context c){return true;}
            public String getCallbackName(){return "a01_owned_process_checkpoint";}
            public void handle(Event e,Context c){try{Files.writeString(Path.of(args[2]),Long.toString(ProcessHandle.current().pid()),StandardOpenOption.CREATE_NEW);Thread.sleep(60000);}catch(Exception error){throw new IllegalStateException("Owned checkpoint failed");}}
        };
        Flyway.configure().dataSource(System.getenv("WB_DB_URL"),System.getenv("WB_MIGRATION_USER"),System.getenv("WB_MIGRATION_PASSWORD"))
            .locations("filesystem:"+args[0]).table(args[1]).cleanDisabled(true).callbacks(callback).load().migrate();
    }
}
