package io.aitest.workbench;
import io.aitest.workbench.configuration.ConfigurationService;
import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.identity.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="WB_DB_URL",matches="jdbc:mysql:.*")
class ConfigurationDraftTest {
 @Test void staleDraftCannotOverwriteAnotherTabsSavedContent(){
  var ds=new DriverManagerDataSource(System.getenv("WB_DB_URL"),System.getenv("WB_DB_USER"),System.getenv("WB_DB_PASSWORD"));var db=new Store(new JdbcTemplate(ds),new DataSourceTransactionManager(ds));var scope=new Scope(db);var service=new ConfigurationService(db,scope);var admin=scope.actor("admin");
  Map<String,Object> initial=Map.of("backend","persistent_fixture","modelCalls",false);
  var object=UUID.fromString(service.create(admin,"model","A08 draft CAS "+UUID.randomUUID(),initial,UUID.randomUUID().toString()).get("id").toString());
  service.draft(admin,object,0,io.aitest.contract.CanonicalJson.hash(db.json(initial)),Map.of("backend","persistent_fixture","modelCalls",false,"description","first tab"),UUID.randomUUID().toString());
  assertThrows(Store.Problem.class,()->service.draft(admin,object,0,io.aitest.contract.CanonicalJson.hash(db.json(initial)),Map.of("backend","persistent_fixture","modelCalls",false,"description","stale second tab"),UUID.randomUUID().toString()));
 }
}
