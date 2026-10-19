package com.agenticai.core.agents.version;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentSchemaService;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.tools.TrifectaEnforcementService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The READ, RESTORE and LABEL half of agent config versioning - everything a caller can do with the
 * history {@link AgentVersionSnapshotService} writes.
 *
 * <h2>Access control mirrors the prompt-versioning precedent exactly</h2>
 *
 * <p>{@code AgentService#listPromptVersions} uses {@code VIEWER} ("prompt history is config, same
 * visibility bar as a plain read") while {@code #revertToPromptVersion} uses {@code EDITOR} plus
 * the FREE-tier edit-lock. The same split applies here, for the same reasons and with one addition:
 * the two READ methods below are deliberately NOT edit-lock gated, so a locked FREE-tier agent's
 * configuration history stays viewable - matching {@code AgentController}'s explicit note that its
 * prompt-version LIST endpoint sits outside {@code assertEditable} while its revert endpoint does
 * not. Both MUTATING methods (restore, label) take {@code EDITOR} and the edit-lock.
 *
 * <p>Labelling is gated as a mutation rather than as a read even though it does not change what the
 * agent does. It is a tenant-visible statement about which configuration is blessed, and letting a
 * viewer move the "production" pointer would make that statement unattributable.
 */
@Service
public class AgentVersionService {

    private static final Logger log = LoggerFactory.getLogger(AgentVersionService.class);

    private final AgentService agentService;
    private final AgentRepository agentRepository;
    // Not optional on the restore path - see #restore's javadoc.
    private final AgentSchemaService agentSchemaService;
    private final AgentVersionRepository versionRepository;
    private final AgentConfigSnapshotCodec codec;
    private final AgentVersionSnapshotService snapshotService;

    /**
     * The eval gate (an {@code ObjectProvider} SPI - see {@link AgentPromotionGate}), the promote
     * audit row, and the C1 label-event / resolution-cache / outbox bookkeeping; the C1 providers
     * may be null (the original 8-arg constructor), which makes them no-ops.
     */
    private final AgentVersionLabelEffects effects;

    public AgentVersionService(
            AgentService agentService,
            AgentRepository agentRepository,
            AgentSchemaService agentSchemaService,
            AgentVersionRepository versionRepository,
            AgentConfigSnapshotCodec codec,
            AgentVersionSnapshotService snapshotService,
            ObjectProvider<AgentPromotionGate> promotionGate,
            AuditLogService auditLogService) {
        this(
                agentService,
                agentRepository,
                agentSchemaService,
                versionRepository,
                codec,
                snapshotService,
                promotionGate,
                auditLogService,
                null,
                null,
                null);
    }

    @Autowired // two public ctors: without this Spring can't pick one and dies with "no default
    // constructor"
    public AgentVersionService(
            AgentService agentService,
            AgentRepository agentRepository,
            AgentSchemaService agentSchemaService,
            AgentVersionRepository versionRepository,
            AgentConfigSnapshotCodec codec,
            AgentVersionSnapshotService snapshotService,
            ObjectProvider<AgentPromotionGate> promotionGate,
            AuditLogService auditLogService,
            ObjectProvider<VersionLabelEventRepository> labelEvents,
            ObjectProvider<AgentVersionResolutionService> resolution,
            ObjectProvider<WorkflowEventPublisher> eventPublisher) {
        this.agentService = agentService;
        this.agentRepository = agentRepository;
        this.agentSchemaService = agentSchemaService;
        this.versionRepository = versionRepository;
        this.codec = codec;
        this.snapshotService = snapshotService;
        this.effects =
                new AgentVersionLabelEffects(
                        promotionGate, auditLogService, labelEvents, resolution, eventPublisher);
    }

    /**
     * Optional (hand-built tests leave it null = restore never enforces the lethal-trifecta
     * policy). Setter injection keeps both public constructors unchanged.
     */
    private TrifectaEnforcementService trifectaEnforcement;

    @Autowired(required = false)
    void setTrifectaEnforcement(TrifectaEnforcementService trifectaEnforcement) {
        this.trifectaEnforcement = trifectaEnforcement;
    }

    /**
     * Every stored configuration of this agent, newest first. Summary rows only - see {@link
     * AgentVersionResponse}'s javadoc for why the bundle is withheld here and served by {@link
     * #get}.
     */
    @Transactional(readOnly = true)
    public List<AgentVersionResponse> list(UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return versionRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .map(AgentVersionResponse::summary)
                .toList();
    }

    /**
     * One stored configuration, decoded. The decode happens here rather than in the controller so a
     * corrupt document surfaces as a server-side failure with the version id in the message,
     * instead of as an opaque string a client is left to parse.
     */
    @Transactional(readOnly = true)
    public AgentVersionResponse get(UUID agentId, UUID versionId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        AgentVersion version = requireVersion(agentId, versionId);
        return AgentVersionResponse.full(version, codec.fromJson(version.getConfigJson()));
    }

    /**
     * Makes a past configuration the agent's current one.
     *
     * <h2>Why {@code AgentSchemaService#refresh} is called unconditionally</h2>
     *
     * <p>This is the single easiest way to get this feature subtly wrong, so it is spelled out.
     * {@code Agent.inputSchemaJson}/{@code outputSchemaJson} are PLATFORM-GENERATED and describe
     * what a caller may pass into this agent when a workflow invokes it with structured parameters.
     * Today {@code AgentSchemaService#generateInputSchema} branches on exactly one condition -
     * {@code knowledgeBaseEnabled || !allowedKnowledgeCollectionIds.isEmpty()} - to decide whether
     * a {@code knowledge_collection_ids} parameter is advertised at all. Both of those inputs are
     * in the restored bundle.
     *
     * <p>So a restore that skipped the refresh would leave the generated schema describing the
     * configuration the agent USED to have: advertising a parameter the restored agent now ignores,
     * or omitting one it now honours. Every workflow node calling this agent with structured params
     * would then be built against a lie, and the failure would surface far from the restore that
     * caused it.
     *
     * <p>It is called unconditionally rather than "only when a schema input changed" on purpose. A
     * restore can change all seven of the fields that {@code AgentService}'s existing refresh call
     * sites react to, in one shot; deciding conditionally would mean re-deriving the field-diff
     * logic those seven call sites encode implicitly, which is exactly the drift this item exists
     * to eliminate. The call is cheap - pure in-memory Jackson - and {@code AgentSchemaService}
     * already fails open to {@code "{}"} rather than blocking a save.
     *
     * <h2>A restore is itself non-destructive</h2>
     *
     * <p>{@link AgentVersionSnapshotService#snapshot} runs after the save, so the configuration
     * this restore replaced is already stored as its own version row (written when it was current)
     * and the restored state becomes a new row of its own. A restore can always be undone by
     * restoring again - the same property {@code AgentService#revertToPromptVersion} documents for
     * prompts.
     *
     * <h2>Known limitation, stated rather than hidden</h2>
     *
     * <p>{@code Agent} carries no {@code @Version} column, so this whole-entity overwrite has no
     * optimistic-locking protection: a config PATCH landing between this method's read and its save
     * is silently lost, exactly as two concurrent PATCHes already are today. This item does not
     * introduce that race and does not fix it; a restore simply widens the window from one field to
     * the whole configuration. Adding {@code @Version} means editing {@code Agent.java} and
     * auditing every writer of it.
     *
     * <h2>Lethal-trifecta enforcement</h2>
     *
     * <p>A restore rewrites the tool allow-list, so it is subject to the same {@code
     * app.tools.classification.trifecta-enforcement} policy as a direct allow-list save: in {@code
     * block} mode restoring a version that would make the trifecta worse is rejected (400) before
     * anything is written, in {@code require-approval} mode the agent is moved to SUPERVISED AFTER
     * the snapshot is applied (the snapshot carries its own autonomy tier, which must not win). The
     * agent's cached trifecta verdict is dropped either way.
     *
     * @return the agent as it now stands, so a caller needs no follow-up read.
     */
    @Transactional
    public AgentResponse restore(UUID agentId, UUID versionId) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        agentService.assertEditable(agent);

        AgentVersion version = requireVersion(agentId, versionId);
        AgentConfigSnapshot snapshot = codec.fromJson(version.getConfigJson());

        // Decided BEFORE the first write: block mode throws IllegalArgumentException (400) here.
        TrifectaEnforcementService.SaveVerdict trifecta =
                trifectaVerdictForRestore(agent, snapshot);

        AgentConfigSnapshotCodec.RestoreOutcome outcome = codec.apply(snapshot, agent);
        String forced =
                trifectaEnforcement == null
                        ? ""
                        : trifectaEnforcement.applyVerdict(agent, trifecta);
        agentSchemaService.refresh(agent);
        Agent saved = agentRepository.save(agent);

        snapshotService.snapshot(saved, restoreSummary(version, outcome) + forced);
        log.info(
                "Agent {} restored to config version {} (v{}); fields not written back: {}",
                agentId,
                version.getId(),
                version.getVersionNumber(),
                outcome.skippedFields());
        // Re-read through AgentService so the response carries the same
        // editable/modalities enrichment every other agent endpoint returns,
        // rather than a second, subtly different projection maintained here.
        return agentService.get(agentId);
    }

    /**
     * The save-time trifecta verdict for the allow-list a restore is about to write. An older
     * document without an allow-list leaves the current one untouched (see the codec), so there is
     * nothing to judge.
     */
    private TrifectaEnforcementService.SaveVerdict trifectaVerdictForRestore(
            Agent agent, AgentConfigSnapshot snapshot) {
        if (trifectaEnforcement == null || snapshot.allowedToolNames() == null) {
            return null;
        }
        return trifectaEnforcement.evaluateSave(
                agent.getId(),
                new HashSet<>(agent.getAllowedToolNames()),
                new HashSet<>(snapshot.allowedToolNames()),
                true);
    }

    /**
     * Points a label at this version, or clears it from this version when {@code label} is null.
     *
     * <p>The previous holder is cleared and FLUSHED before the new one is assigned. Without that
     * flush, Hibernate is free to order the two UPDATEs however it likes at end-of-transaction, and
     * an assign-before-clear ordering trips migration V212's partial unique index on {@code
     * (agent_id, label)} - turning a perfectly legal "move production to v7" into a constraint
     * violation. The index stays regardless: it is the backstop that makes one-holder-per-label an
     * invariant rather than a convention, and the flush is what keeps the normal path off it.
     *
     * <p>Moving a label away from a version does not delete or alter that version. Labels are
     * pointers; snapshots are facts.
     *
     * <h2>PRODUCTION goes through the eval gate</h2>
     *
     * <p>Assigning PRODUCTION is the platform's publish/promote action, and it is the point the
     * eval gate (V260) enforces: when an {@link AgentPromotionGate} provider is present and the
     * agent has a gate configured, the version must have a qualifying PASSED eval run or the
     * promote is refused with {@link PromotionGateBlockedException} (412 + the gate report). A
     * caller holding the MANAGE_AGENTS scope may override with {@code force=true} + a reason, which
     * is audited as such. Every PRODUCTION decision - pass, block, force - is written to the audit
     * log either way: "who put this config into production" is exactly the question an audit trail
     * exists to answer.
     */
    @Transactional
    public AgentVersionResponse setLabel(
            UUID agentId, UUID versionId, AgentVersionLabel label, boolean force, String reason) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        agentService.assertEditable(agent);

        AgentVersion target = requireVersion(agentId, versionId);

        if (label == null) {
            AgentVersionLabel previousLabel = target.getLabel();
            target.clearLabel();
            AgentVersion cleared = versionRepository.save(target);
            if (previousLabel != null) {
                effects.afterLabelMove(agentId, cleared, previousLabel, LabelEventAction.CLEAR, null);
            }
            return AgentVersionResponse.summary(cleared);
        }

        String gateOverrideDetail = null;
        if (label == AgentVersionLabel.PRODUCTION) {
            gateOverrideDetail = effects.enforcePromotionGate(agent, target, force, reason);
        }

        Optional<AgentVersion> currentHolder =
                versionRepository.findByAgentIdAndLabel(agentId, label);
        if (currentHolder.isPresent()) {
            if (currentHolder.get().getId().equals(target.getId())) {
                return AgentVersionResponse.summary(target);
            }
            AgentVersion previous = currentHolder.get();
            previous.clearLabel();
            versionRepository.saveAndFlush(previous);
        }
        target.assignLabel(label);
        if (label == AgentVersionLabel.PRODUCTION) {
            effects.auditPromote(agent, target, gateOverrideDetail, AuditOutcome.SUCCESS);
        }
        AgentVersion saved = versionRepository.save(target);
        effects.afterLabelMove(agentId, saved, label, LabelEventAction.PROMOTE, gateOverrideDetail);
        log.info(
                "Agent {} config label {} now points at version {} (v{})",
                agentId,
                label,
                target.getId(),
                target.getVersionNumber());
        return AgentVersionResponse.summary(saved);
    }

    // ------------------------------------------------------------------
    // C1: publish / rollback / diff
    // ------------------------------------------------------------------

    /**
     * C1 {@code publish(entityId, changeNote)} - snapshots the agent's current draft as a new
     * immutable version. Hash-dedupe lives inside {@link AgentVersionSnapshotService#snapshot}: a
     * publish that changed nothing returns the existing newest row with {@code created=false}
     * rather than padding history with an identical bundle.
     *
     * <p>Editing is {@code EDITOR} + edit-lock gated for the same reason a config PATCH is:
     * publishing snapshots the draft, and a locked FREE agent's draft cannot have changed, so a
     * locked publish is a no-op the lock still gets to refuse (matching the edit-lock aspect's
     * intent).
     */
    @Transactional
    public AgentPublishResponse publish(UUID agentId, String changeNote) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        agentService.assertEditable(agent);
        Optional<AgentVersion> appended =
                snapshotService.snapshot(
                        agent,
                        changeNote == null || changeNote.isBlank()
                                ? "Published"
                                : changeNote.trim());
        if (appended != null && appended.isPresent()) {
            return new AgentPublishResponse(AgentVersionResponse.summary(appended.get()), true);
        }
        // Deduped (or a fail-open miss): the newest row already describes
        // this exact bundle - answer it rather than pretend a write happened.
        Optional<AgentVersion> latest =
                versionRepository.findFirstByAgentIdOrderByVersionNumberDesc(agentId);
        return latest.map(v -> new AgentPublishResponse(AgentVersionResponse.summary(v), false))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Agent "
                                                + agentId
                                                + " has no version to publish - the snapshot write failed."));
    }

    /**
     * C1 {@code rollback(entityId)} - moves PRODUCTION back to the previous version. A label move,
     * never a new snapshot and never a draft write: the rollback target already exists, and the
     * live row is untouched (in explicit mode the draft stays whatever the editor left it as - a
     * rollback that silently rewrote a half-finished draft would be its own incident).
     *
     * <p>"Previous" is the highest-numbered version below the current PRODUCTION holder's -
     * deterministic and immune to label-history gaps. One stored version (or none labeled) means
     * there is nothing to roll back to, answered 409 via {@link VersionRollbackException}.
     *
     * <p>PRODUCTION moves still go through the eval gate in spirit: a rollback restores a version
     * the tenant once blessed, so it does NOT re-run {@link AgentPromotionGate} - rolling back to
     * safety is exactly the action a gate exists to make possible, and blocking it would strand a
     * tenant on a bad release. The move is audited either way.
     */
    @Transactional
    public AgentVersionResponse rollback(UUID agentId) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        agentService.assertEditable(agent);

        AgentVersion current =
                versionRepository
                        .findByAgentIdAndLabel(agentId, AgentVersionLabel.PRODUCTION)
                        .orElseThrow(() -> new VersionRollbackException("agent", agentId));

        AgentVersion previous = null;
        for (AgentVersion candidate :
                versionRepository.findByAgentIdOrderByCreatedAtDesc(agentId)) {
            if (candidate.getVersionNumber() < current.getVersionNumber()) {
                previous = candidate;
                break;
            }
        }
        if (previous == null) {
            throw new VersionRollbackException("agent", agentId);
        }

        current.clearLabel();
        versionRepository.saveAndFlush(current);
        previous.assignLabel(AgentVersionLabel.PRODUCTION);
        AgentVersion saved = versionRepository.save(previous);
        effects.auditPromote(
                agent,
                saved,
                "rolled back from v" + current.getVersionNumber(),
                AuditOutcome.SUCCESS);
        effects.afterLabelMove(
                agentId,
                saved,
                AgentVersionLabel.PRODUCTION,
                LabelEventAction.ROLLBACK,
                "rolled back from v" + current.getVersionNumber());
        log.info(
                "Agent {} rolled back: PRODUCTION v{} -> v{}",
                agentId,
                current.getVersionNumber(),
                saved.getVersionNumber());
        return AgentVersionResponse.summary(saved);
    }

    /**
     * C1 {@code diff(from, to)} - field-level bundle diff between two stored versions, addressed by
     * ordinal ({@code v3} vs {@code v7}) the same way the workflow diff endpoint already is.
     * Renders every {@link AgentConfigSnapshot} record component that differs as {@code {field,
     * from, to}} with values trimmed for display - the confirm-dialog payload for promote and
     * rollback.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> diff(UUID agentId, int fromVersionNumber, int toVersionNumber) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        AgentVersion from =
                versionRepository
                        .findByAgentIdAndVersionNumber(agentId, fromVersionNumber)
                        .orElseThrow(
                                () ->
                                        new AgentVersionNotFoundException(
                                                agentId, fromVersionNumber));
        AgentVersion to =
                versionRepository
                        .findByAgentIdAndVersionNumber(agentId, toVersionNumber)
                        .orElseThrow(
                                () -> new AgentVersionNotFoundException(agentId, toVersionNumber));
        AgentConfigSnapshot a = codec.fromJson(from.getConfigJson());
        AgentConfigSnapshot b = codec.fromJson(to.getConfigJson());

        return AgentVersionDiff.of(from, to, a, b);
    }

    private AgentVersion requireVersion(UUID agentId, UUID versionId) {
        return versionRepository
                .findByIdAndAgentId(versionId, agentId)
                .orElseThrow(() -> new AgentVersionNotFoundException(versionId, agentId));
    }

    /**
     * The {@code change_summary} written for the row a restore produces. Names the version restored
     * FROM so the timeline reads as a story, and names a partial restore explicitly - a version
     * list that said only "Restored from v3" while v3's biometric-consent flag had been refused
     * would be actively misleading.
     */
    private String restoreSummary(
            AgentVersion version, AgentConfigSnapshotCodec.RestoreOutcome outcome) {
        String base = "Restored from v" + version.getVersionNumber();
        if (outcome.isComplete()) {
            return base;
        }
        return base + " (not restored: " + String.join(", ", outcome.skippedFields()) + ")";
    }
}
