package com.agenticai.core.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicit, reviewed opt-out for a {@code @Scheduled} method that runs on EVERY
 * core replica on purpose, i.e. one that is NOT wrapped in
 * {@link PgAdvisoryJobLock#tryRunExclusive}.
 *
 * <p>Every {@code @Scheduled} method must either run inside the advisory lock
 * (the class then holds a {@link PgAdvisoryJobLock} field) or carry this
 * annotation, on the method or on its class. The guard is
 * {@code ScheduledJobsAreLockedOrAnnotatedTest} in {@code core/app}: adding a
 * new scheduled job with neither fails the build. Without it, a second core
 * replica silently double-runs the job (two replicas both sweeping, both
 * ingesting email, both stopping the same experiment).
 *
 * <p>Legitimate reasons are narrow: the job only touches state that is local
 * to this JVM (an in-memory buffer, a local session map, a per-replica
 * lease heartbeat), so every replica must run its own copy and cross-replica
 * exclusion would be wrong. A job that works on shared rows (the database,
 * Redis, object storage) does not qualify: lock it.
 *
 * <p>{@link #reason()} is mandatory and is read by a reviewer, so say WHICH
 * per-JVM state the job owns.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface AllowUnlockedSchedule {

    /** Why this job must run on every replica (name the per-JVM state it owns). */
    String reason();
}
