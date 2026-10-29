package io.aitest.workbench.execution;

import io.aitest.workbench.identity.Scope;
import io.aitest.workbench.identity.SyntheticBoundary;
import io.aitest.workbench.infrastructure.Store;
import io.aitest.workbench.material.Materials;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises real Spring profile selection, dependency ordering and scheduled bean registration.
 * The synthetic success case supplies a stand-in validated boundary; actual boundary checks
 * are separately exercised by SyntheticBoundaryTest, without a database or production service. */
class SyntheticSchedulingIsolationTest {
    @Configuration @EnableScheduling static class Scheduling {}

    private AnnotationConfigApplicationContext context(String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.register(Scheduling.class, PreparationService.class, FixtureProvider.class, ExecutionWorker.class);
        return context;
    }

    private Store dependencies(AnnotationConfigApplicationContext context, CountDownLatch ran) {
        Store db=mock(Store.class);
        when(db.queueTransaction(any())).thenAnswer(invocation->{ran.countDown(); return Map.of();});
        var materials=mock(Materials.class);
        when(materials.scope()).thenReturn("0".repeat(64));
        context.registerBean(Store.class,()->db);
        context.registerBean(Materials.class,()->materials);
        context.registerBean(Scope.class,()->mock(Scope.class));
        context.registerBean(SyntheticCheckpoints.class,()->mock(SyntheticCheckpoints.class));
        context.registerBean(QuotaLedger.class,()->mock(QuotaLedger.class));
        return db;
    }

    @Test void defaultAndProductionProfilesDoNotRegisterOrScheduleSyntheticExecution() throws Exception {
        for (var profiles : new String[][] { {}, {"production"} }) {
            var ran=new CountDownLatch(1);
            try(var context=context(profiles)) {
                Store db=dependencies(context,ran);
                context.registerBean("syntheticBoundary", Object.class, Object::new);
                context.refresh();
                assertTrue(context.getBeansOfType(FixtureProvider.class).isEmpty());
                assertTrue(context.getBeansOfType(ExecutionWorker.class).isEmpty());
                assertTrue(context.getBeansOfType(PreparationService.class).isEmpty());
                assertFalse(ran.await(250, TimeUnit.MILLISECONDS));
                verifyNoInteractions(db);
            }
        }
    }

    @Test void syntheticSchedulesWorkerAfterBoundaryInitialization() throws Exception {
        var ran=new CountDownLatch(1);
        try(var context=context("synthetic")) {
            dependencies(context,ran);
            var initialized=new java.util.concurrent.atomic.AtomicBoolean();
            context.registerBean("syntheticBoundary", Object.class, ()->{initialized.set(true); return new Object();});
            context.refresh();
            assertTrue(initialized.get());
            assertNotNull(context.getBean(FixtureProvider.class));
            assertNotNull(context.getBean(ExecutionWorker.class));
            assertNotNull(context.getBean(PreparationService.class));
            assertTrue(ran.await(3, TimeUnit.SECONDS));
        }
    }

    @Test void invalidMixedProfileFailsBeforeSyntheticBeanConstruction() {
        var ran=new CountDownLatch(1);
        try(var context=context("synthetic","production")) {
            Store db=dependencies(context,ran);
            context.registerBean("syntheticBoundary", SyntheticBoundary.class, ()->new SyntheticBoundary(context.getEnvironment(),db));
            assertThrows(org.springframework.beans.BeansException.class,context::refresh);
            assertEquals(1,ran.getCount());
            verifyNoInteractions(db);
        }
    }
}
