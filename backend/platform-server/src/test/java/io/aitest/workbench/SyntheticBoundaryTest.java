package io.aitest.workbench;

import io.aitest.workbench.identity.SyntheticBoundary;
import io.aitest.workbench.infrastructure.Store;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticBoundaryTest {
    @Test void syntheticMarkerAloneCannotAuthorizeMissingMigrationHistory(){
        var markerOnly=new Store(null,org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class)){
            @Override public Map<String,Object> one(String sql,Object...args){return Map.of("mode_name","isolated_synthetic_only");}
        };
        assertThrows(IllegalStateException.class,()->new SyntheticBoundary(environment("synthetic"),markerOnly));
    }
    private StandardEnvironment environment(String...profiles){
        var e=new StandardEnvironment();e.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);e.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);e.setActiveProfiles(profiles);
        e.getPropertySources().addFirst(new MapPropertySource("boundary-test",new HashMap<>(Map.of("server.address","127.0.0.1","spring.datasource.url","jdbc:mysql://127.0.0.1:13306/workbench_platform"))));return e;
    }
    private void property(StandardEnvironment e,String key,String value){e.getPropertySources().addFirst(new MapPropertySource("override-"+key,Map.of(key,value)));}
    @Test void rejectsMissingProductionAndMixedProfilesBeforeOpeningDatabase(){
        assertThrows(IllegalStateException.class,()->SyntheticBoundary.validateTarget(environment()));
        assertThrows(IllegalStateException.class,()->SyntheticBoundary.validateTarget(environment("production")));
        assertThrows(IllegalStateException.class,()->SyntheticBoundary.validateTarget(environment("synthetic","production")));
        assertDoesNotThrow(()->SyntheticBoundary.validateTarget(environment("synthetic")));
    }
    @Test void rejectsPublicBindingAndRemoteOrDifferentDatabaseBeforeOpeningConnection(){
        for(String address:List.of("0.0.0.0","::","192.0.2.1")){
            var e=environment("synthetic");property(e,"server.address",address);assertThrows(IllegalStateException.class,()->SyntheticBoundary.validateTarget(e));
        }
        for(String url:List.of("jdbc:mysql://production.example/workbench_platform","jdbc:mysql://127.0.0.1/company","jdbc:mysql://u:p@127.0.0.1/workbench_platform","jdbc:h2:mem:test")){
            var e=environment("synthetic");property(e,"spring.datasource.url",url);assertThrows(IllegalStateException.class,()->SyntheticBoundary.validateTarget(e));
        }
    }
    @Test void safeNumbersRejectFractionOverflowTextAndNegative(){
        assertEquals(9_007_199_254_740_991L,Store.number(9_007_199_254_740_991L));
        for(Object n:List.of(1.5,new java.math.BigDecimal("1.000001"),9_007_199_254_740_992L,-1,"1"))assertThrows(Store.Problem.class,()->Store.number(n));
    }
}
