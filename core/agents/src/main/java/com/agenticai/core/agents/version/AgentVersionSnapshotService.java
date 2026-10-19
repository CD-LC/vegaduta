package com.agenticai.core.agents.version;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.FeatureFlagService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The WRITE half of agent config versioning: appends one {@link AgentVersion} row
 * describing the configuration an agent has right now.
 *
 * <h2>Why this is a separate class from {@link AgentVersionService}</h2>
 *
 * <p>Purely to keep the bean graph acyclic, and the shape is worth stating so
 * nobody "simplifies" it back into a cycle. {@code AgentService} must call the
 * write path (every config change appends a version), and the RESTORE path must
 * call {@code AgentService} (it needs {@code requireAccess} and the FREE-tier
 * edit-lock). Merging the two halves into one bean would make
 * {@code AgentService} and that bean mutually dependent. So: this class depends on
 * nothing but a repository and a codec, and {@link AgentVersionService} - which
 * does depend on {@code AgentService} - depends on this one.
 *
 * <h2>Fail-open, deliberately</h2>
 *
 * <p>{@link #snapshot} never throws. Versioning is an enhancement layered onto a
 * config save; if it breaks, the tenant's actual edit must still succeed. That is
 * the same posture {@code TaskPlanningService}, {@code ReflexionService} and
 * {@code AnswerConsistencyChecker} already take for their own optional work, and
 * the same posture {@code AgentSchemaService#writeString} takes when JSON
 * generation fails during a save. The failure is logged at WARN with the agent id,
 * because a silently missing version row is exactly the kind of gap that only
 * surfaces later, when someone tries to roll back and finds nothing there.
 *
 * <p>Note the asymmetry with {@link AgentConfigSnapshotCodec#toJson}, which does
 * throw: a version row that does not exist is a visible gap, while one holding a
 * truncated document is a trap that looks restorable. Fail open on writing,
 * never on corrupting.
 *
 * <h2>Auto-promote (compatibility mode)</h2>
 *
 * <p>C1's behavioral pivot ("runtime traffic reads only labeled versions") is
 * gated on the {@code versioning_explicit_publish} tenant flag. While it is OFF
 * - the default - every appended snapshot is also assigned the PRODUCTION label,
 * which keeps PRODUCTION == the live row exactly the way the pre-versioning
 * platform behaved ("save means live"). When the flag is ON the label is left
 * alone: a save changes only the draft, and nothing reaches runtime until a
 * human promotes a version. The label move goes through the same
 * event+cache-eviction path an explicit promote uses, so resolution caches can
 * never serve a stale production bundle.
 */
@Service
public class AgentVersionSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(AgentVersionSnapshotService.class);

    private final AgentVersionRepository repository;
    private final AgentConfigSnapshotCodec codec;
    private final ObjectProvider<VersionLabelEventRepository> labelEvents;
    private final ObjectProvider<AgentVersionResolutionService> resolution;
    private final ObjectProvider<WorkflowEventPublisher> eventPublisher;
    private final ObjectProvider<FeatureFlagService> featureFlags;

    public AgentVersionSnapshotService(
        AgentVersionRepository repository,
        AgentConfigSnapshotCodec codec,
        ObjectProvider<VersionLabelEventRepository> labelEvents,
        ObjectProvider<AgentVersionResolutionService> resolution,
        ObjectProvider<WorkflowEventPublisher> eventPublisher,
        ObjectProvider<FeatureFlagService> featureFlags
    ) {
        this.repository = repository;
        this.codec = codec;
        this.labelEvents = labelEvents;
        this.resolution = resolution;
        this.eventPublisher = eventPublisher;
        this.featureFlags = featureFlags;
    }

    /**
     * A no-op instance for unit tests that construct {@code AgentService} by hand
     * and have no interest in versioning.
     *
     * <p>Exists so this feature could be added to {@code AgentService} without
     * changing the constructor those tests already call - see
     * {@code AgentService}'s own note on its two constructors. Deliberately
     * explicit ({@code null} repository plus a guard) rather than relying on the
     * fail-open catch below to swallow an NPE: "disabled" and "broken" should not
     * look the same in the logs.
     */
    public static AgentVersionSnapshotService disabled() {
        return new AgentVersionSnapshotService(null, null, null, null, null, null);
    }

    /**
     * Appends a version row for {@code agent}'s CURRENT configuration, unless that
     * configuration is byte-identical to the newest row already stored.
     *
     * <p>Called AFTER the agent has been saved, so the row it writes genuinely
     * describes a state the agent had - see {@link AgentVersion}'s javadoc for why
     * this table's convention is the opposite of {@code PromptVersion}'s
     * snapshot-the-old-value-first.
     *
     * <p>The de-duplication is not an optimization; it is what keeps the table
     * honest. Several {@code AgentService} methods save unconditionally even when
     * the request asked for the value already in place (toggling a flag to what it
     * already was, re-sending an unchanged allow-list), and a version list padded
     * with identical entries makes the real changes harder to find, not easier.
     * The comparison works because {@link AgentConfigSnapshotCodec#capture} sorts
     * every collection, so an unchanged configuration always serializes to the same
     * bytes.
     *
     * @param changeSummary short, human-readable "what caused this" written by the
     *     call site, e.g. "Changed reasoning depth". Never caller-supplied text.
     * @return the appended row, or empty when the bundle was a byte-identical
     *     duplicate (explicit publish uses this to answer "nothing changed")
     *     or when the write failed open.
     */
    public Optional<AgentVersion> snapshot(Agent agent, String changeSummary) {
        if (repository == null) {
            return Optional.empty();
        }
        try {
            String configJson = codec.toJson(codec.capture(agent));
            Optional<AgentVersion> latest = repository.findFirstByAgentIdOrderByVersionNumberDesc(agent.getId());
            if (latest.isPresent() && configJson.equals(latest.get().getConfigJson())) {
                log.debug(
                    "Agent {} saved with no config change ({}) - no new version row appended",
                    agent.getId(), changeSummary
                );
                return Optional.empty();
            }
            int nextVersionNumber = latest.map(v -> v.getVersionNumber() + 1).orElse(1);
            AgentVersion saved = repository.save(AgentVersion.snapshot(
                agent.getId(),
                nextVersionNumber,
                configJson,
                agent.getName(),
                agent.getProviderName() == null ? null : agent.getProviderName().name(),
                agent.getModel(),
                changeSummary,
                TenantContext.current().map(TenantContext::getSubject).orElse(null)
            ));
            autoPromote(agent.getId(), saved);
            return Optional.of(saved);
        } catch (RuntimeException e) {
            log.warn(
                "Could not append a config version for agent {} ({}) - the agent's own save is unaffected, "
                    + "but this configuration will not be restorable.",
                agent.getId(), changeSummary, e
            );
            return Optional.empty();
        }
    }

    /**
     * Compatibility-mode label move - see this class's javadoc. Under
     * explicit publish (the flag is on) this does nothing: edits stay on
     * the draft until a human promotes. Every collaborator is optional so
     * the write path stays fail-open in hand-built tests.
     */
    private void autoPromote(UUID agentId, AgentVersion version) {
        FeatureFlagService flags = featureFlags == null ? null : featureFlags.getIfAvailable();
        if (flags != null) {
            try {
                String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
                if (FeatureFlagService.TREATMENT.equals(
                        flags.evaluateActiveFlags(tenantId)
                            .get(AgentVersionResolutionService.EXPLICIT_PUBLISH_FLAG))) {
                    return; // explicit mode: drafts never touch labels
                }
            } catch (RuntimeException e) {
                log.debug("Explicit-publish flag evaluation failed for agent {} - auto mode", agentId, e);
            }
        }
        try {
            Optional<AgentVersion> holder =
                repository.findByAgentIdAndLabel(agentId, AgentVersionLabel.PRODUCTION);
            if (holder.isPresent()) {
                if (holder.get().getId().equals(version.getId())) {
                    return;
                }
                AgentVersion previous = holder.get();
                previous.clearLabel();
                repository.saveAndFlush(previous);
            }
            version.assignLabel(AgentVersionLabel.PRODUCTION);
            repository.save(version);
            recordLabelEvent(agentId, version, LabelEventAction.ASSIGN, "auto-publish on save");
            AgentVersionResolutionService resolver = resolution == null ? null : resolution.getIfAvailable();
            if (resolver != null) {
                resolver.evict(agentId);
            }
            WorkflowEventPublisher publisher = eventPublisher == null ? null : eventPublisher.getIfAvailable();
            if (publisher != null) {
                publisher.publish("version.promoted", agentId.toString(), Map.of(
                    "entityType", "AGENT",
                    "entityId", agentId.toString(),
                    "versionId", version.getId().toString(),
                    "versionNumber", version.getVersionNumber(),
                    "label", "PRODUCTION",
                    "automatic", true
                ));
            }
        } catch (RuntimeException e) {
            log.warn("Could not auto-assign PRODUCTION for agent {} v{} - the version row itself is stored",
                agentId, version.getVersionNumber(), e);
        }
    }

    private void recordLabelEvent(UUID agentId, AgentVersion version, LabelEventAction action, String detail) {
        VersionLabelEventRepository events = labelEvents == null ? null : labelEvents.getIfAvailable();
        if (events == null) {
            return;
        }
        try {
            events.save(VersionLabelEvent.of(
                VersionEntityType.AGENT, agentId, AgentVersionLabel.PRODUCTION.name(), version.getId(),
                action, TenantContext.current().map(TenantContext::getSubject).orElse(null), detail));
        } catch (RuntimeException e) {
            log.warn("Could not record label event for agent {} v{}", agentId, version.getVersionNumber(), e);
        }
    }
}
