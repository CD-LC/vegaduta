package com.agenticai.core.agents.tools.classification;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Agent-config-time check for the "lethal trifecta": one agent that holds
 * (1) a way to read private data, (2) a way to ingest untrusted content and
 * (3) a way to send data out. Any injected instruction in (2) can then steal
 * (1) through (3). Advisory by itself: it produces warnings the UI and logs can
 * surface; whether a save is blocked or the agent is forced onto approval is decided by
 * {@link TrifectaEnforcementPolicy} behind {@code app.tools.classification.trifecta-enforcement}.
 *
 * <p>The three legs are derived from {@link ToolClassification}:
 * <ul>
 *   <li>private read - sensitivity {@code READ_TENANT} or {@code CREDENTIALED}</li>
 *   <li>untrusted ingestion - trust tier {@code UNTRUSTED_EXTERNAL}, excluding EGRESS-only tools</li>
 *   <li>egress - sensitivity {@code EGRESS}</li>
 * </ul>
 * One tool may supply several legs (a Gmail tool reads untrusted mail and sends
 * it); tools without a classification cannot be judged and are reported as
 * {@code unclassifiedTools} rather than assumed safe.
 */
@Component
public class LethalTrifectaDetector {

    private static final Logger log = LoggerFactory.getLogger(LethalTrifectaDetector.class);

    static final String DETECTED_METRIC = "agent.config.lethal_trifecta";

    private final ToolClassificationRegistry registry;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public LethalTrifectaDetector(ToolClassificationRegistry registry, ObjectProvider<MeterRegistry> meterRegistry) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
    }

    /** Result of one analysis. Tool lists are the names that supply each leg. */
    public record Report(
        List<String> privateDataTools,
        List<String> untrustedContentTools,
        List<String> egressTools,
        List<String> unclassifiedTools
    ) {
        public boolean isLethalTrifecta() {
            return !privateDataTools.isEmpty() && !untrustedContentTools.isEmpty() && !egressTools.isEmpty();
        }

        /** Human-readable warning, or {@code null} when the trifecta is not present. */
        public String warning() {
            if (!isLethalTrifecta()) {
                return null;
            }
            return "This agent can read private data (" + String.join(", ", privateDataTools)
                + "), ingest untrusted content (" + String.join(", ", untrustedContentTools)
                + ") and send data out (" + String.join(", ", egressTools)
                + "). A prompt injection in the untrusted content could exfiltrate the private data. "
                + "Remove one of the three capabilities, or keep human approval on the sending tools.";
        }
    }

    /** Resolves the tool names through the registry and analyses them. Never throws. */
    public Report analyse(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return analyseDecisions(Map.of());
        }
        try {
            return analyseDecisions(registry.resolveAll(toolNames));
        } catch (RuntimeException e) {
            log.debug("Lethal-trifecta analysis skipped: classification lookup failed", e);
            return analyseDecisions(Map.of());
        }
    }

    /**
     * Like {@link #analyse} but lets a classification lookup failure propagate, so a caller that must
     * fail safe (the dispatch-time exempt-list override) can tell "no trifecta" from "could not judge".
     */
    public Report analyseStrict(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return analyseDecisions(Map.of());
        }
        return analyseDecisions(registry.resolveAll(toolNames));
    }

    /** Pure analysis over already-resolved decisions. */
    public static Report analyseDecisions(Map<String, ToolClassificationDecision> decisions) {
        Set<String> priv = new LinkedHashSet<>();
        Set<String> untrusted = new LinkedHashSet<>();
        Set<String> egress = new LinkedHashSet<>();
        List<String> unclassified = new ArrayList<>();
        for (Map.Entry<String, ToolClassificationDecision> entry : decisions.entrySet()) {
            if (entry.getValue() instanceof ToolClassificationDecision.Classified classified) {
                ToolClassification c = classified.classification();
                if (c.sensitivity() == ToolSensitivity.READ_TENANT || c.sensitivity() == ToolSensitivity.CREDENTIALED) {
                    priv.add(entry.getKey());
                }
                // An EGRESS-only tool (send/sandbox) does not ingest content back, so it
                // is not an untrusted-input leg even with an external trust tier.
                if (c.trust() == ToolTrustTier.UNTRUSTED_EXTERNAL && c.sensitivity() != ToolSensitivity.EGRESS) {
                    untrusted.add(entry.getKey());
                }
                if (c.sensitivity() == ToolSensitivity.EGRESS) {
                    egress.add(entry.getKey());
                }
            } else {
                unclassified.add(entry.getKey());
            }
        }
        return new Report(List.copyOf(priv), List.copyOf(untrusted), List.copyOf(egress), List.copyOf(unclassified));
    }

    /**
     * Analyse and, when the trifecta is present, log a warning and count it.
     * Returns the warning text or {@code null}.
     */
    public String warnIfLethal(String agentId, Collection<String> toolNames) {
        return recordIfLethal(agentId, analyse(toolNames));
    }

    /** As {@link #warnIfLethal} for a report the caller already computed. Returns the warning or {@code null}. */
    public String recordIfLethal(String agentId, Report report) {
        String warning = report == null ? null : report.warning();
        if (warning != null) {
            log.warn("Agent {} has the lethal trifecta: {}", agentId, warning);
            MeterRegistry meters = meterRegistry.getIfAvailable();
            if (meters != null) {
                try {
                    meters.counter(DETECTED_METRIC).increment();
                } catch (RuntimeException e) {
                    log.debug("Could not record metric {}", DETECTED_METRIC, e);
                }
            }
        }
        return warning;
    }
}
