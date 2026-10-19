package com.agenticai.core.agents.version;

import com.agenticai.core.agents.core.Agent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The three operations that make a config version useful: CAPTURE a live
 * {@link Agent} into an {@link AgentConfigSnapshot}, SERIALIZE that bundle to the
 * single {@code agent_version.config_json} document, and APPLY a decoded bundle
 * back onto a live agent.
 *
 * <p>Deliberately a separate collaborator rather than static methods on
 * {@link AgentConfigSnapshot} or logic inside {@link AgentVersionService}: capture
 * is needed by the WRITE path ({@link AgentVersionSnapshotService}, injected into
 * {@code AgentService}) and apply is needed by the RESTORE path
 * ({@link AgentVersionService}, which itself depends on {@code AgentService}).
 * Putting both in either service would create a bean cycle; putting them here
 * means neither service has to know the other exists.
 *
 * <p>This class contains the entire field-by-field inventory of what an agent's
 * configuration IS. When {@link Agent} grows a field, {@link #capture} and
 * {@link #apply} are the two places that must learn about it - and a field added
 * to only one of them is caught by
 * {@code AgentConfigSnapshotCodecTest#captureThenApplyRoundTripsEveryRestorableField}.
 */
@Component
public class AgentConfigSnapshotCodec {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigSnapshotCodec.class);

    private final ObjectMapper objectMapper;

    public AgentConfigSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * What {@link #apply} actually did.
     *
     * <p>Exists because a restore is genuinely allowed to be partial, and a
     * partial restore that reports itself as complete is the failure mode this
     * whole item was built to eliminate. Two things can legitimately land in
     * {@code skippedFields}:
     *
     * <ul>
     *   <li>{@code biometricConsentEnabled} - when the snapshot had it ON and the live
     *       agent has it OFF. Consent is never re-granted by a rollback.
     *   <li>anything the stored document simply did not contain, because it was
     *       written by a build that predates that field.
     * </ul>
     *
     * <p>{@code name} is deliberately NOT in this list (adversarial-review fix,
     * 2026-08-17). It is never restorable - {@link Agent} has no setter for it
     * (see {@link AgentConfigSnapshot}'s {@code @param name}) - but that is a
     * structural fact true of every restore ever performed, not something THIS
     * restore hit. The first cut recorded it unconditionally, which made
     * {@link #isComplete} permanently false and stamped every {@code
     * change_summary}, including perfectly clean rollbacks, as "(not restored:
     * name)" - a constant caveat carries no information and made the one field
     * built to flag genuine partial restores unable to distinguish them.
     * "Names never roll back" is an API-level contract stated once on the
     * snapshot record, not a per-row event.
     *
     * @param skippedFields never null; empty means every restorable field was written.
     */
    public record RestoreOutcome(List<String> skippedFields) {

        public boolean isComplete() {
            return skippedFields.isEmpty();
        }
    }

    /**
     * Reads every CONFIG field off {@code agent}. Pure - touches nothing, saves
     * nothing.
     *
     * <p>All six {@code @ElementCollection} sets are {@code FetchType.EAGER} on
     * {@link Agent}, so a snapshot of an already-loaded agent costs no extra
     * query. They are sorted here so that two captures of an unchanged
     * configuration produce byte-identical JSON - {@link AgentVersionSnapshotService}
     * relies on that to avoid appending a duplicate row for a save that changed
     * nothing.
     */
    public AgentConfigSnapshot capture(Agent agent) {
        return new AgentConfigSnapshot(
            AgentConfigSnapshot.CURRENT_FORMAT_VERSION,

            agent.getName(),
            agent.getSystemPrompt(),
            agent.getProviderName(),
            agent.getModel(),

            agent.getMaxTokens(),
            agent.getTemperature(),
            agent.getTopP(),

            agent.getFallbackProviderName(),
            agent.getFallbackModel(),

            agent.getAutonomyTier(),
            agent.getTuningMode(),
            agent.getReasoningDepth(),
            agent.getExperienceMode(),
            agent.getEdgeRoutingPolicy(),

            agent.isKnowledgeBaseEnabled(),
            agent.isSarvamEnabled(),
            agent.isE2bEnabled(),
            agent.isCodeWorkspaceEnabled(),
            agent.isKnowledgeWriteEnabled(),
            agent.isShareMemoryWithTenant(),
            agent.isBiometricConsentEnabled(),
            agent.isResponseCachingEnabled(),
            agent.isSemanticResponseCachingEnabled(),

            agent.isCostRoutingEnabled(),
            agent.getEconomyProviderName(),
            agent.getEconomyModel(),
            agent.getCostRoutingJevThreshold(),

            agent.getVoiceModelProvider(),
            agent.getVoiceModel(),
            agent.getVideoModelProvider(),
            agent.getVideoModel(),
            agent.getTextModelProvider(),
            agent.getTextModel(),
            agent.getTtsProvider(),
            agent.getTtsModel(),

            agent.getResultSchemaJson(),

            sortedStrings(agent.getAllowedToolNames()),
            sortedUuids(agent.getAllowedKnowledgeCollectionIds()),
            sortedStrings(agent.getAllowedCodeIntelRepoIds()),
            sortedUuids(agent.getAllowedKnowledgeWriteCollectionIds()),
            sortedUuids(agent.getCollaboratorAgentIds()),
            sortedStrings(agent.getEnabledGuardrails()),

            agent.isXaiServerToolsEnabled(),

            agent.getTopK(),
            agent.getStopSequences(),
            agent.getFrequencyPenalty(),
            agent.getPresencePenalty(),
            agent.getSeed()
        );
    }

    /**
     * Writes every restorable field of {@code snapshot} onto {@code agent}.
     *
     * <p>Does NOT save, and does NOT regenerate the agent's derived JSON schemas -
     * both are the caller's job, and {@link AgentVersionService#restore} documents
     * why the schema regeneration in particular is not optional.
     *
     * <p><b>Absent means "leave alone", never "clear".</b> A {@code null} component
     * in a decoded document means the field did not exist when that row was
     * written. For the six collections that distinction is exact (capture always
     * writes them, empty list included), and it matters: two of the six are
     * DENY-ALL when empty and gate write/execute reach, so mistaking absent for
     * empty would silently revoke grants; the other four are UNRESTRICTED when
     * empty, so the same mistake would silently widen them. Every skip is
     * recorded in the returned {@link RestoreOutcome} and logged, because a
     * silently partial rollback that a UI reports as complete is worse than no
     * rollback at all.
     *
     * <p><b>{@code biometricConsentEnabled} is restored in one direction only.</b>
     * Turning it OFF from a snapshot is always applied - narrowing consent is
     * safe by definition. Turning it ON is refused and reported as skipped. This
     * is the one field whose underlying data ({@code Agent}'s own javadoc) is
     * biometric special-category data under GDPR Art.9 and sensitive personal
     * data under India's DPDP Act; a rollback button silently re-enabling face
     * processing because some older configuration had it on is a compliance
     * event, not a UX wrinkle. The tenant re-grants it deliberately, through
     * {@code AgentService#setBiometricConsentEnabled}, or not at all.
     *
     * <p><b>Paired fields move together.</b> provider+model, fallback
     * provider+model, cost-routing flag+provider+model, and each of the four
     * modality provider+model pairs are written through {@link Agent}'s combined
     * setters, which already enforce "both or neither" - a provider with a stale
     * model string from a different provider is not a valid state, and this is
     * not the place to invent one. Where a pair is only half-present in an older
     * document, the whole pair is skipped rather than half-written.
     */
    public RestoreOutcome apply(AgentConfigSnapshot snapshot, Agent agent) {
        List<String> skipped = new ArrayList<>();

        // name is intentionally NOT recorded here even though it never restores
        // (Agent has no name setter - constructor-only): it is a structural fact
        // of every restore, not a property of this one, and recording it
        // unconditionally made RestoreOutcome#isComplete permanently false.
        // See RestoreOutcome's javadoc for the full reasoning.

        if (snapshot.systemPrompt() != null) {
            agent.setSystemPrompt(snapshot.systemPrompt());
        } else {
            skipped.add("systemPrompt");
        }

        if (snapshot.providerName() != null && snapshot.model() != null) {
            agent.setProviderModel(snapshot.providerName(), snapshot.model());
        } else {
            skipped.add("providerName/model");
        }

        if (snapshot.maxTokens() != null && snapshot.temperature() != null && snapshot.topP() != null) {
            agent.setGenerationParams(snapshot.maxTokens(), snapshot.temperature(), snapshot.topP());
        } else {
            skipped.add("maxTokens/temperature/topP");
        }

        if (snapshot.stopSequences() != null) {
            agent.setSamplingParams(
                snapshot.topK(),
                snapshot.stopSequences(),
                snapshot.frequencyPenalty(),
                snapshot.presencePenalty(),
                snapshot.seed()
            );
        } else {
            skipped.add("samplingParams");
        }

        // Null fallbackProviderName is a legitimate stored value ("no fallback
        // configured"), so unlike the pairs above there is nothing to skip here -
        // Agent#setFallbackProvider clears the model itself when the provider is
        // null, which is exactly the state being restored.
        agent.setFallbackProvider(snapshot.fallbackProviderName(), snapshot.fallbackModel());

        if (snapshot.autonomyTier() != null) {
            agent.setAutonomyTier(snapshot.autonomyTier());
        } else {
            skipped.add("autonomyTier");
        }
        if (snapshot.tuningMode() != null) {
            agent.setTuningMode(snapshot.tuningMode());
        } else {
            skipped.add("tuningMode");
        }
        if (snapshot.reasoningDepth() != null) {
            agent.setReasoningDepth(snapshot.reasoningDepth());
        } else {
            skipped.add("reasoningDepth");
        }

        // experienceMode and edgeRoutingPolicy: null is a REAL configured state
        // ("inherit the tenant default" / "device preferences decide"), not an
        // absence, so both are written unconditionally. The cost of that choice is
        // stated plainly: a document written before either field existed restores
        // as "cleared" rather than "untouched". Both clear to the platform's own
        // pre-existing default behavior, so the blast radius is a preference reset,
        // not a permission change.
        agent.setExperienceMode(snapshot.experienceMode());
        agent.setEdgeRoutingPolicy(snapshot.edgeRoutingPolicy());

        applyFlag("knowledgeBaseEnabled", snapshot.knowledgeBaseEnabled(), agent::setKnowledgeBaseEnabled, skipped);
        applyFlag("sarvamEnabled", snapshot.sarvamEnabled(), agent::setSarvamEnabled, skipped);
        applyFlag("e2bEnabled", snapshot.e2bEnabled(), agent::setE2bEnabled, skipped);
        applyFlag("codeWorkspaceEnabled", snapshot.codeWorkspaceEnabled(), agent::setCodeWorkspaceEnabled, skipped);
        applyFlag("knowledgeWriteEnabled", snapshot.knowledgeWriteEnabled(), agent::setKnowledgeWriteEnabled, skipped);
        applyFlag("shareMemoryWithTenant", snapshot.shareMemoryWithTenant(), agent::setShareMemoryWithTenant, skipped);
        applyFlag("responseCachingEnabled", snapshot.responseCachingEnabled(), agent::setResponseCachingEnabled, skipped);
        applyFlag("xaiServerToolsEnabled", snapshot.xaiServerToolsEnabled(), agent::setXaiServerToolsEnabled, skipped);
        applyFlag(
            "semanticResponseCachingEnabled",
            snapshot.semanticResponseCachingEnabled(),
            agent::setSemanticResponseCachingEnabled,
            skipped
        );

        applyBiometricConsent(snapshot, agent, skipped);

        if (snapshot.costRoutingEnabled() != null) {
            agent.setCostRouting(
                snapshot.costRoutingEnabled(),
                snapshot.economyProviderName(),
                snapshot.economyModel(),
                snapshot.costRoutingJevThreshold()
            );
        } else {
            skipped.add("costRoutingEnabled/economyProviderName/economyModel/costRoutingJevThreshold");
        }

        // The four modality pairs. A null provider is a legitimate stored state
        // ("use the default fallback chain") and each setter clears its own model
        // when handed a null provider, so all four are written unconditionally -
        // same reasoning as setFallbackProvider above.
        agent.setVoiceModelProvider(snapshot.voiceModelProvider(), snapshot.voiceModel());
        agent.setVideoModelProvider(snapshot.videoModelProvider(), snapshot.videoModel());
        agent.setTextModelProvider(snapshot.textModelProvider(), snapshot.textModel());
        agent.setTtsProvider(snapshot.ttsProvider(), snapshot.ttsModel());

        // Tenant-authored, nullable, and null means "no structured output
        // required" - a real target state, so it is written unconditionally. See
        // AgentConfigSnapshot's @param resultSchemaJson for the known gap this
        // leaves (the agent-job endpoints that write it produce no version row).
        agent.setResultSchemaJson(snapshot.resultSchemaJson());

        applyStrings("allowedToolNames", snapshot.allowedToolNames(), agent::setAllowedToolNames, skipped);
        applyUuids(
            "allowedKnowledgeCollectionIds",
            snapshot.allowedKnowledgeCollectionIds(),
            agent::setAllowedKnowledgeCollectionIds,
            skipped
        );
        applyStrings(
            "allowedCodeIntelRepoIds",
            snapshot.allowedCodeIntelRepoIds(),
            agent::setAllowedCodeIntelRepoIds,
            skipped
        );
        applyUuids(
            "allowedKnowledgeWriteCollectionIds",
            snapshot.allowedKnowledgeWriteCollectionIds(),
            agent::setAllowedKnowledgeWriteCollectionIds,
            skipped
        );
        applyUuids("collaboratorAgentIds", snapshot.collaboratorAgentIds(), agent::setCollaboratorAgentIds, skipped);
        applyStrings("enabledGuardrails", snapshot.enabledGuardrails(), agent::setEnabledGuardrails, skipped);

        if (skipped.size() > 1) {
            // size() > 1 because "name" is always present and is not news.
            log.info(
                "Agent {} restored from a config snapshot with {} field(s) not written back: {}",
                agent.getId(), skipped.size(), skipped
            );
        }
        return new RestoreOutcome(List.copyOf(skipped));
    }

    /**
     * @return the bundle as the single JSON document stored in
     *     {@code agent_version.config_json}.
     * @throws IllegalStateException if Jackson cannot serialize the bundle. This
     *     is the one place in this feature that does NOT fail open, and the
     *     asymmetry is deliberate: {@link AgentVersionSnapshotService} catches it
     *     and degrades to "no version row written" so a tenant's config save still
     *     succeeds, whereas silently storing a truncated or empty document would
     *     produce a version row that LOOKS restorable and is not. A snapshot that
     *     doesn't exist is a visible gap; a corrupt one is a trap.
     */
    public String toJson(AgentConfigSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize agent config snapshot", e);
        }
    }

    /**
     * @throws IllegalStateException if the stored document cannot be parsed -
     *     surfaced rather than swallowed, because the caller is a RESTORE and
     *     applying a half-decoded bundle would write an arbitrary subset of a
     *     tenant's configuration.
     */
    public AgentConfigSnapshot fromJson(String configJson) {
        try {
            return objectMapper.readValue(configJson, AgentConfigSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read stored agent config snapshot", e);
        }
    }

    /**
     * ON is never restored, OFF always is - see {@link #apply}'s javadoc for the
     * GDPR Art.9 / DPDP reasoning. Kept as its own method so the rule is
     * greppable and so the "already on, nothing to do" case is visibly distinct
     * from the "refused to turn on" case: only the latter is a partial restore.
     */
    private void applyBiometricConsent(AgentConfigSnapshot snapshot, Agent agent, List<String> skipped) {
        Boolean snapshotted = snapshot.biometricConsentEnabled();
        if (snapshotted == null) {
            skipped.add("biometricConsentEnabled");
            return;
        }
        if (!snapshotted) {
            agent.setBiometricConsentEnabled(false);
            return;
        }
        if (agent.isBiometricConsentEnabled()) {
            return;
        }
        skipped.add("biometricConsentEnabled");
        log.info(
            "Agent {}: config restore did NOT re-enable biometric consent - the snapshot had it on, the agent "
                + "has it off, and re-granting consent for special-category data is an explicit tenant action, "
                + "not a side effect of a rollback.",
            agent.getId()
        );
    }

    private void applyFlag(String field, Boolean value, java.util.function.Consumer<Boolean> setter, List<String> skipped) {
        if (value == null) {
            skipped.add(field);
            return;
        }
        setter.accept(value);
    }

    private void applyStrings(
        String field,
        List<String> values,
        java.util.function.Consumer<Set<String>> setter,
        List<String> skipped
    ) {
        if (values == null) {
            skipped.add(field);
            return;
        }
        setter.accept(new LinkedHashSet<>(values));
    }

    private void applyUuids(
        String field,
        List<UUID> values,
        java.util.function.Consumer<Set<UUID>> setter,
        List<String> skipped
    ) {
        if (values == null) {
            skipped.add(field);
            return;
        }
        setter.accept(new LinkedHashSet<>(values));
    }

    private List<String> sortedStrings(Set<String> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    private List<UUID> sortedUuids(Set<UUID> values) {
        return values == null ? List.of() : values.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }
}
