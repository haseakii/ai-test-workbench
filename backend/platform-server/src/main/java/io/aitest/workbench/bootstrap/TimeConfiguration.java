package io.aitest.workbench.bootstrap;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {
    /** Production and synthetic runtime use UTC; tests inject their own Clock instance. */
    @Bean public Clock workbenchClock(){return Clock.systemUTC();}
}
