package com.agenticai.core.providers;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V499 - the tool-calling grade of a hosted-pool row ({@link ToolCallGrade}): when to measure it,
 * what to keep when the probe cannot answer, and how a {@link ToolCallGrade#NONE} row is kept out
 * of the chain. Split from {@link PlatformHostedLlmProviderService} for the file-size ratchet.
 *
 * <p>The grade is advisory (chain ordering, an admin badge): nothing here ever fails a save.
 */
final class HostedPoolToolCallGrading {

    private static final Logger log = LoggerFactory.getLogger(HostedPoolToolCallGrading.class);

    /** Null when no probe is on the classpath (the chat module provides it): grades stay unmeasured. */
    private final ToolCallCapabilityProbe probe;

    HostedPoolToolCallGrading(ToolCallCapabilityProbe probe) {
        this.probe = probe;
    }

    /**
     * What one save learned: the grade (V499), the router mode the server supports (V500) and the
     * model's size in billions of parameters when the server reports it (V501); each null when
     * unknown. {@link #UNMEASURED} when the probe could not answer.
     */
    record Measurement(ToolCallGrade grade, ToolRouterMode routerMode, Double parameterBillions) {
        boolean measured() {
            return grade != null;
        }
    }

    static final Measurement UNMEASURED = new Measurement(null, null, null);

    /**
     * The probe's measurement for (endpoint, model), or {@link #UNMEASURED} when no probe is wired,
     * it could not reach the server, or it threw.
     */
    Measurement measure(String baseUrl, String completionsPath, String model, String apiKeyOrNull) {
        if (probe == null) {
            return UNMEASURED;
        }
        try {
            Optional<ToolCallCapabilityProbe.Result> result =
                    probe.probe(baseUrl, completionsPath, model, apiKeyOrNull);
            if (result.isEmpty() || result.get().grade() == null) {
                log.info("Tool-call grade of model '{}' at {} could not be measured.", model, baseUrl);
                return UNMEASURED;
            }
            ToolCallCapabilityProbe.Result measured = result.get();
            log.info(
                    "Tool-call grade of model '{}' at {}: {} ({} server{}).",
                    model,
                    baseUrl,
                    measured.grade(),
                    measured.serverKind(),
                    measured.parameterBillions() == null
                            ? ""
                            : String.format(java.util.Locale.ROOT, ", %.1fB parameters", measured.parameterBillions()));
            return new Measurement(
                    measured.grade(), ToolRouterMode.forServer(measured.serverKind()), measured.parameterBillions());
        } catch (RuntimeException e) {
            log.warn("Tool-call probe of model '{}' at {} failed; keeping the stored grade.", model, baseUrl, e);
            return UNMEASURED;
        }
    }

    /** Measures a freshly resolved create request and writes the result onto the new {@code entity}. */
    void measureInto(
            PlatformHostedLlmProvider entity,
            PlatformHostedLlmProviderService.ResolvedRequest resolved,
            String apiKeyOrNull) {
        apply(entity, measure(resolved.baseUrl(), resolved.completionsPath(), resolved.model(), apiKeyOrNull));
    }

    /** Writes a measurement onto the row; an unmeasured result leaves every stored value as it is. */
    static void apply(PlatformHostedLlmProvider entity, Measurement measurement) {
        if (measurement == null || !measurement.measured()) {
            return;
        }
        entity.setToolCallGrade(measurement.grade());
        entity.setToolRouterMode(measurement.routerMode());
        if (measurement.parameterBillions() != null) {
            entity.setParameterBillions(measurement.parameterBillions());
        }
    }

    /**
     * An edited row is re-measured when the endpoint or model changed (the measurement belongs to
     * that pair, so the stored values are cleared first) or when it was never measured; otherwise
     * the stored values stay and no model call is made, so a label/priority/enabled edit stays cheap.
     * Applied BEFORE {@code entity.apply(...)} moves the row to the new endpoint, so the old values
     * are still there to compare against.
     */
    void forUpdateInto(
            PlatformHostedLlmProvider entity,
            PlatformHostedLlmProviderService.ResolvedRequest resolved,
            String apiKeyOrNull) {
        boolean endpointOrModelChanged =
                !Objects.equals(entity.getBaseUrl(), resolved.baseUrl())
                        || !Objects.equals(entity.getModel(), resolved.model())
                        || !Objects.equals(entity.getCompletionsPath(), resolved.completionsPath());
        if (!endpointOrModelChanged && entity.getToolCallGrade() != null) {
            return;
        }
        if (endpointOrModelChanged) {
            entity.setToolCallGrade(null);
            entity.setToolRouterMode(null);
            entity.setParameterBillions(null);
        }
        measureInto(entity, resolved, apiKeyOrNull);
    }

    /**
     * A row graded {@link ToolCallGrade#NONE} (chat only) is left out while any other enabled row
     * exists: an agent turn that offers tools would otherwise land on a model that narrates tool runs
     * instead of making them. With no alternative the rows are returned as they are, so a pool of one
     * small model still answers plain chat.
     */
    static List<PlatformHostedLlmProvider> withoutChatOnlyRowsWhenAvoidable(
            List<PlatformHostedLlmProvider> enabledRows) {
        List<PlatformHostedLlmProvider> usable =
                enabledRows.stream().filter(row -> !row.isChatOnly()).toList();
        if (usable.isEmpty() || usable.size() == enabledRows.size()) {
            return enabledRows;
        }
        for (PlatformHostedLlmProvider skipped : enabledRows) {
            if (skipped.isChatOnly()) {
                log.debug(
                        "Hosted pool row '{}' ({}) is graded NONE (chat only) - skipped while {} other"
                                + " row(s) can make tool calls.",
                        skipped.getLabel(),
                        skipped.getModel(),
                        usable.size());
            }
        }
        return usable;
    }
}
