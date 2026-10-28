package io.aitest.workbench.execution;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;private final ZoneId zone;
    MutableClock(String value){this(new AtomicReference<>(Instant.parse(value)),ZoneOffset.UTC);}
    private MutableClock(AtomicReference<Instant> instant,ZoneId zone){this.instant=instant;this.zone=zone;}
    void set(String value){instant.set(Instant.parse(value));}
    @Override public ZoneId getZone(){return zone;}
    @Override public Clock withZone(ZoneId zone){return new MutableClock(instant,zone);}
    @Override public Instant instant(){return instant.get();}
}
