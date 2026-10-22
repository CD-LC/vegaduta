package com.agenticai.core.workflow;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Restoring a version snapshot onto the live definition and resolving which graph a run executes
 * (PRODUCTION snapshot vs live), split out of {@link WorkflowService}. Named to avoid clashing with
 * the {@code workflowVersionService} bean of the {@code version} package.
 */
@Service
class WorkflowVersionRestoreService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowVersionRestoreService.class);

    private final OrchestratorService orchestratorService;
    private final ObjectMapper objectMapper;
    private final com.agenticai.core.workflow.version.WorkflowVersionService versionService;
    private final WorkflowAccessGuard guard;
    private final WorkflowGraphCodec codec;
    private final WorkflowCrudService crudService;

    WorkflowVersionRestoreService(
            OrchestratorService orchestratorService,
            ObjectMapper objectMapper,
            com.agenticai.core.workflow.version.WorkflowVersionService versionService,
            WorkflowAccessGuard guard,
            WorkflowGraphCodec codec,
            WorkflowCrudService crudService) {
        this.orchestratorService = orchestratorService;
        this.objectMapper = objectMapper;
        this.versionService = versionService;
        this.guard = guard;
        this.codec = codec;
        this.crudService = crudService;
    }

    /**
     * C1: the graph a NEW run executes - the PRODUCTION-labeled snapshot when one exists, else the
     * live definition's (see {@code WorkflowVersionService#resolveRunGraphJson}). Both execution
     * paths (sync {@link #run} and {@code WorkflowBackgroundRunService}) must go through this so a
     * promote/rollback actually changes what runs, and the stamped {@code
     * workflow_run.workflow_version} - resolved with the same rule in {@code
     * WorkflowExecutionEngine#prepareRun} - always names the graph that ran.
     */
    public WorkflowGraphDto resolveRunGraph(WorkflowDefinition definition) {
        return codec.readGraph(versionService.resolveRunGraphJson(definition));
    }

    /**
     * C1 pin semantics for RESUMED runs: the graph of the version the run stamped at start, so a
     * pause/resume (or any long-running continuation) never silently switches to a newer PRODUCTION
     * mid-run. Falls back to the live definition when the run predates stamping or its snapshot is
     * gone - the pre-V273 behavior.
     */
    public String resolvePinnedGraphJson(
            WorkflowDefinition definition, Integer pinnedVersionNumber) {
        if (pinnedVersionNumber == null) {
            return versionService.resolveRunGraphJson(definition);
        }
        return versionService
                .getOptional(definition.getId(), pinnedVersionNumber)
                .map(com.agenticai.core.workflow.version.WorkflowVersion::getGraphJson)
                .orElse(definition.getGraphJson());
    }

    /**
     * Version history (V273): restores a snapshot's graph/name/description onto the live definition
     * and snapshots the restore itself, so the history reads "...v3, Restored from v3" rather than
     * silently rewriting the past. The definition's CURRENT status is kept - restoring a graph must
     * never silently activate or archive a workflow; status changes go through {@link #update} like
     * always. EDITOR access, same bar as update - a restore IS an update.
     *
     * <p>C1: the restore is now the WHOLE snapshot, not just the graph - the V342 {@code
     * details_json} document (schedule, reasoning/tuning modes, caching/cost-routing, economy
     * model, execution limits, retries) is applied onto the live row too, via the same guarded
     * setters the settings endpoints use. A V273-era row with no details_json restores graph +
     * name/description only, exactly as before.
     */
    @Transactional
    public WorkflowDefinition restoreVersion(UUID id, int versionNumber) {
        WorkflowDefinition definition = guard.requireAccess(id, PermissionLevel.EDITOR);
        guard.assertEditable(definition);
        com.agenticai.core.workflow.version.WorkflowVersion version =
                versionService.get(id, versionNumber);
        // An ACTIVE definition must stay executable after the restore -
        // same gate update() applies when activating, since a restored
        // graph is exactly as untrusted as a freshly typed one.
        if (definition.getStatus() == WorkflowStatus.ACTIVE) {
            orchestratorService.validate(codec.readGraph(version.getGraphJson()), id);
        }
        definition.update(
                version.getWorkflowName(),
                version.getDescription(),
                version.getGraphJson(),
                definition.getStatus());
        crudService.pruneRemovedPins(id, codec.readGraph(version.getGraphJson()));
        applyVersionDetails(definition, version.getDetailsJson());
        versionService.snapshot(definition, "Restored from v" + versionNumber);
        return definition;
    }

    /**
     * Applies a snapshot's {@code details_json} onto the live definition (V342/C1) - the sibling of
     * {@link com.agenticai.core.workflow.version.WorkflowVersionService#detailsJsonOf}. Defensive
     * by design: a missing key leaves the current value alone, an unparseable document or enum name
     * logs a warning and is skipped rather than failing the whole restore - restoring the graph
     * (the thing runs actually execute) must never be blocked by a secondary knob's serialization
     * going stale.
     *
     * <p>Schedule is the subtle one: {@code enableSchedule} needs a fresh {@code nextRunAt} (the
     * persisted one is a point in the past by the time anyone restores), so a stored enabled
     * schedule is re-armed off {@link #computeNextRun} exactly like {@link #enableSchedule} does,
     * and a stored disabled one lands via {@code disableSchedule} AFTER the cron write so "disabled
     * but configured" round-trips too.
     */
    private void applyVersionDetails(WorkflowDefinition definition, String detailsJson) {
        if (detailsJson == null || detailsJson.isBlank()) {
            return;
        }
        try {
            tools.jackson.databind.JsonNode details = objectMapper.readTree(detailsJson);
            if (details.hasNonNull("reasoningDepth")) {
                definition.setReasoningDepth(
                        ReasoningDepth.valueOf(details.get("reasoningDepth").asString()));
            }
            if (details.hasNonNull("tuningMode")) {
                definition.setTuningMode(TuningMode.valueOf(details.get("tuningMode").asString()));
            }
            if (details.has("stepCachingEnabled")) {
                definition.setStepCachingEnabled(details.get("stepCachingEnabled").asBoolean());
            }
            if (details.has("costRoutingEnabled")
                    || details.has("economyProviderName")
                    || details.hasNonNull("economyModel")) {
                ProviderName provider =
                        details.hasNonNull("economyProviderName")
                                ? ProviderName.valueOf(
                                        details.get("economyProviderName").asString())
                                : null;
                definition.setCostRouting(
                        details.path("costRoutingEnabled")
                                .asBoolean(definition.isCostRoutingEnabled()),
                        provider,
                        details.hasNonNull("economyModel")
                                ? details.get("economyModel").asString()
                                : definition.getEconomyModel());
            }
            if (details.has("maxStepsPerRun") || details.has("stepTimeoutSeconds")) {
                definition.setExecutionLimits(
                        details.path("maxStepsPerRun").asInt(definition.getMaxStepsPerRun()),
                        details.path("stepTimeoutSeconds")
                                .asInt(definition.getStepTimeoutSeconds()));
            }
            if (details.has("maxRetryAttempts")) {
                definition.setMaxRetryAttempts(
                        details.get("maxRetryAttempts").asInt(definition.getMaxRetryAttempts()));
            }
            applyVersionSchedule(definition, details);
        } catch (Exception e) {
            log.warn(
                    "Could not apply details_json of a workflow version onto {} - restored graph +"
                            + " name/description only",
                    definition.getId(),
                    e);
        }
    }

    /**
     * The schedule half of {@link #applyVersionDetails} - see that method's javadoc for why
     * nextRunAt is recomputed, never restored verbatim.
     */
    private void applyVersionSchedule(
            WorkflowDefinition definition, tools.jackson.databind.JsonNode details) {
        if (!details.has("scheduleCron") && !details.has("scheduleEnabled")) {
            return;
        }
        String cron =
                details.hasNonNull("scheduleCron")
                        ? WorkflowCronSupport.normalize(details.get("scheduleCron").asString())
                        : definition.getScheduleCron();
        if (cron != null) {
            try {
                CronExpression parsed = CronExpression.parse(cron);
                definition.enableSchedule(cron, WorkflowCronSupport.nextRun(parsed, Instant.now()));
            } catch (IllegalArgumentException e) {
                log.warn(
                        "Snapshot schedule cron '{}' no longer parses for workflow {} - leaving"
                                + " current schedule alone",
                        cron,
                        definition.getId());
            }
        }
        if (!details.path("scheduleEnabled").asBoolean(definition.isScheduleEnabled())) {
            definition.disableSchedule();
        }
    }
}
