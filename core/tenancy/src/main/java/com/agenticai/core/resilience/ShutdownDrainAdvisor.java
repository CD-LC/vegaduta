package com.agenticai.core.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/**
 * Observability + a config sanity check for graceful shutdown; it changes NOTHING about how the
 * process stops.
 *
 * <p>How shutdown actually works (Spring Boot 3.4.4, verified against the reference docs, see
 * docs/plans/CORE-RESILIENCE-AUDIT.md section 5): on SIGTERM the JVM shutdown hook closes the
 * context; with {@code server.shutdown=graceful} (the Boot 3.4 default, pinned explicitly in
 * application.yml) Tomcat stops accepting new requests, lets in-flight ones - including open SSE
 * chat streams - finish for up to {@code spring.lifecycle.timeout-per-shutdown-phase}, and the
 * readiness state moves to REFUSING_TRAFFIC so {@code /actuator/health/readiness} stops reporting
 * UP. {@code app.shutdown.drain-seconds} is the one knob that feeds that timeout.
 *
 * <p>The failure this class guards against is a configuration mismatch: if the container's
 * termination grace period (Azure Container Apps {@code terminationGracePeriodSeconds}, default
 * 30) is shorter than the drain window, the platform SIGKILLs the process mid-drain and the
 * "graceful" setting was decorative. Core cannot read the platform setting, so the operator
 * states it in {@code app.shutdown.container-grace-seconds}; 0 (default) means "not stated" and
 * no comparison is made.
 */
public final class ShutdownDrainAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ShutdownDrainAdvisor.class);

    /** Seconds the platform needs on top of the drain for the remaining shutdown phases. */
    static final int GRACE_MARGIN_SECONDS = 10;

    private final ApplicationAvailability availability;
    private final int drainSeconds;
    private final int containerGraceSeconds;
    private final String serverShutdownMode;

    public ShutdownDrainAdvisor(
        ApplicationAvailability availability, int drainSeconds, int containerGraceSeconds, String serverShutdownMode
    ) {
        this.availability = availability;
        this.drainSeconds = drainSeconds;
        this.containerGraceSeconds = containerGraceSeconds;
        this.serverShutdownMode = serverShutdownMode == null ? "" : serverShutdownMode.trim();
    }

    /**
     * Pure check, unit-tested: a human-readable problem with the drain configuration, or null when
     * it is coherent.
     */
    static String assess(int drainSeconds, int containerGraceSeconds, String serverShutdownMode) {
        if (!"graceful".equalsIgnoreCase(serverShutdownMode)) {
            return "server.shutdown is '" + serverShutdownMode + "', not 'graceful': in-flight requests and SSE chat "
                + "turns are cut at SIGTERM and app.shutdown.drain-seconds has no effect";
        }
        if (containerGraceSeconds > 0 && drainSeconds + GRACE_MARGIN_SECONDS > containerGraceSeconds) {
            return "app.shutdown.drain-seconds=" + drainSeconds + " plus a " + GRACE_MARGIN_SECONDS
                + "s margin exceeds the stated container grace period of " + containerGraceSeconds
                + "s (terminationGracePeriodSeconds): the platform will SIGKILL core before the drain finishes. "
                + "Raise the grace period to at least " + (drainSeconds + GRACE_MARGIN_SECONDS) + "s or lower the drain";
        }
        return null;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            String problem = assess(drainSeconds, containerGraceSeconds, serverShutdownMode);
            if (problem != null) {
                log.warn("Graceful shutdown misconfigured: {}", problem);
            } else {
                log.info("Graceful shutdown: server.shutdown={}, drain up to {}s, container grace {}",
                    serverShutdownMode, drainSeconds,
                    containerGraceSeconds > 0 ? containerGraceSeconds + "s" : "not stated");
            }
        } catch (RuntimeException e) {
            log.debug("shutdown drain check skipped", e);
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void onClosing() {
        try {
            log.info("Shutdown requested: draining in-flight requests for up to {}s (readiness state: {})",
                drainSeconds, availability == null ? "unknown" : availability.getReadinessState());
        } catch (RuntimeException e) {
            log.debug("shutdown drain log skipped", e);
        }
    }
}
