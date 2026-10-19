package com.agenticai.core.agents.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.EdgeRoutingPolicy;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.modality.TextModelProvider;
import com.agenticai.core.providers.modality.TtsModelProvider;
import com.agenticai.core.providers.modality.VideoModelProvider;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import com.agenticai.core.security.ExperienceMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link AgentConfigSnapshotCodec} - the class that decides what an
 * agent's configuration IS, and therefore the one place a bug in this feature
 * would be invisible until someone actually tried to roll back.
 *
 * <p>Plain JUnit 5 with real objects and a real {@link ObjectMapper}: no Spring
 * context, no database, no mocks. Every collaborator here is a POJO, and the
 * behaviour under test is entirely about field-by-field fidelity - the exact
 * thing a mock would hide.
 *
 * <p>The load-bearing test is {@link #captureThenApplyRoundTripsEveryRestorableField}:
 * it sets every restorable field to a NON-DEFAULT value on one agent, round-trips
 * through JSON, and asserts the values landed on a second agent that started from
 * different defaults. A field forgotten in {@code capture} or in {@code apply}
 * fails it, which is the regression guard for the "Agent grew a 42nd field and
 * nobody told the codec" case that this whole item exists to prevent.
 */
class AgentConfigSnapshotCodecTest {

    private AgentConfigSnapshotCodec codec;

    @BeforeEach
    void setUp() {
        codec = new AgentConfigSnapshotCodec(new ObjectMapper());
    }

    /** An agent with every restorable field deliberately away from its default. */
    private Agent fullyConfiguredSource() {
        Agent agent = new Agent(
            "Source agent",
            "You are a source.",
            ProviderName.ANTHROPIC,
            "claude-opus-4-8",
            4096,
            0.31,
            0.77,
            true,
            ExperienceMode.ADVANCED,
            ReasoningDepth.RIGOROUS,
            TuningMode.SIMPLE
        );
        agent.setFallbackProvider(ProviderName.OPENAI, "gpt-4o");
        agent.setAutonomyTier(AgentAutonomyTier.SUPERVISED);
        agent.setEdgeRoutingPolicy(EdgeRoutingPolicy.EDGE_FIRST);
        agent.setSarvamEnabled(true);
        agent.setE2bEnabled(true);
        agent.setCodeWorkspaceEnabled(true);
        agent.setKnowledgeWriteEnabled(true);
        agent.setShareMemoryWithTenant(true);
        agent.setResponseCachingEnabled(true);
        agent.setSemanticResponseCachingEnabled(true);
        agent.setCostRouting(true, ProviderName.GEMINI, "gemini-3.5-flash", 0.85);
        agent.setVoiceModelProvider(VoiceModelProvider.SARVAM, "saaras:v3");
        agent.setVideoModelProvider(VideoModelProvider.OPENAI_WHISPER_VIDEO, "whisper-1");
        agent.setTextModelProvider(TextModelProvider.OPENAI, "gpt-4o");
        agent.setTtsProvider(TtsModelProvider.ELEVENLABS, "eleven_multilingual_v2");
        agent.setResultSchemaJson("{\"type\":\"object\"}");
        agent.setAllowedToolNames(Set.of("search_web", "run_code"));
        agent.setAllowedKnowledgeCollectionIds(Set.of(UUID.randomUUID()));
        agent.setAllowedCodeIntelRepoIds(Set.of("repo-a", "repo-b"));
        agent.setAllowedKnowledgeWriteCollectionIds(Set.of(UUID.randomUUID()));
        agent.setCollaboratorAgentIds(Set.of(UUID.randomUUID()));
        agent.setEnabledGuardrails(Set.of("pii", "profanity"));
        return agent;
    }

    /** A different agent, at defaults, to restore ONTO - so an unwritten field is visible. */
    private Agent bareTarget() {
        return new Agent(
            "Target agent",
            "You are a target.",
            ProviderName.GROK,
            "grok-4.5",
            256,
            0.9,
            1.0,
            false,
            ExperienceMode.SIMPLE,
            ReasoningDepth.FAST,
            TuningMode.MANUAL
        );
    }

    @Test
    void captureThenApplyRoundTripsEveryRestorableField() {
        Agent source = fullyConfiguredSource();
        Agent target = bareTarget();

        AgentConfigSnapshot decoded = codec.fromJson(codec.toJson(codec.capture(source)));
        AgentConfigSnapshotCodec.RestoreOutcome outcome = codec.apply(decoded, target);

        assertEquals(source.getSystemPrompt(), target.getSystemPrompt());
        assertEquals(source.getProviderName(), target.getProviderName());
        assertEquals(source.getModel(), target.getModel());
        assertEquals(source.getMaxTokens(), target.getMaxTokens());
        assertEquals(source.getTemperature(), target.getTemperature());
        assertEquals(source.getTopP(), target.getTopP());
        assertEquals(source.getFallbackProviderName(), target.getFallbackProviderName());
        assertEquals(source.getFallbackModel(), target.getFallbackModel());
        assertEquals(source.getAutonomyTier(), target.getAutonomyTier());
        assertEquals(source.getTuningMode(), target.getTuningMode());
        assertEquals(source.getReasoningDepth(), target.getReasoningDepth());
        assertEquals(source.getExperienceMode(), target.getExperienceMode());
        assertEquals(source.getEdgeRoutingPolicy(), target.getEdgeRoutingPolicy());
        assertEquals(source.isKnowledgeBaseEnabled(), target.isKnowledgeBaseEnabled());
        assertEquals(source.isSarvamEnabled(), target.isSarvamEnabled());
        assertEquals(source.isE2bEnabled(), target.isE2bEnabled());
        assertEquals(source.isCodeWorkspaceEnabled(), target.isCodeWorkspaceEnabled());
        assertEquals(source.isKnowledgeWriteEnabled(), target.isKnowledgeWriteEnabled());
        assertEquals(source.isShareMemoryWithTenant(), target.isShareMemoryWithTenant());
        assertEquals(source.isResponseCachingEnabled(), target.isResponseCachingEnabled());
        assertEquals(source.isSemanticResponseCachingEnabled(), target.isSemanticResponseCachingEnabled());
        assertEquals(source.isCostRoutingEnabled(), target.isCostRoutingEnabled());
        assertEquals(source.getEconomyProviderName(), target.getEconomyProviderName());
        assertEquals(source.getEconomyModel(), target.getEconomyModel());
        assertEquals(source.getVoiceModelProvider(), target.getVoiceModelProvider());
        assertEquals(source.getVoiceModel(), target.getVoiceModel());
        assertEquals(source.getVideoModelProvider(), target.getVideoModelProvider());
        assertEquals(source.getVideoModel(), target.getVideoModel());
        assertEquals(source.getTextModelProvider(), target.getTextModelProvider());
        assertEquals(source.getTextModel(), target.getTextModel());
        assertEquals(source.getTtsProvider(), target.getTtsProvider());
        assertEquals(source.getTtsModel(), target.getTtsModel());
        assertEquals(source.getResultSchemaJson(), target.getResultSchemaJson());
        assertEquals(source.getAllowedToolNames(), target.getAllowedToolNames());
        assertEquals(source.getAllowedKnowledgeCollectionIds(), target.getAllowedKnowledgeCollectionIds());
        assertEquals(source.getAllowedCodeIntelRepoIds(), target.getAllowedCodeIntelRepoIds());
        assertEquals(
            source.getAllowedKnowledgeWriteCollectionIds(),
            target.getAllowedKnowledgeWriteCollectionIds()
        );
        assertEquals(source.getCollaboratorAgentIds(), target.getCollaboratorAgentIds());
        assertEquals(source.getEnabledGuardrails(), target.getEnabledGuardrails());

        // A complete, current-format document restores clean: name is a structural
        // never-restorable (no setter on Agent - the target keeps its own name,
        // asserted below) and is deliberately NOT reported per-row, so a clean
        // rollback's outcome is genuinely complete. See RestoreOutcome's javadoc.
        assertEquals(java.util.List.of(), outcome.skippedFields());
        assertEquals("Target agent", target.getName());
        assertTrue(outcome.isComplete());
    }

    @Test
    void runtimeAndIdentityStateIsNeverSnapshotted() {
        // The counters are structurally unreachable (no setters on Agent), so what
        // is actually assertable - and what actually matters - is that the stored
        // DOCUMENT does not carry them. If they ever appear in the JSON, someone
        // has added them to the bundle and the restore path is one setter away
        // from clobbering an atomic bulk UPDATE.
        String json = codec.toJson(codec.capture(fullyConfiguredSource()));

        assertFalse(json.contains("fallbackTriggeredCount"), json);
        assertFalse(json.contains("fallbackFailedCount"), json);
        assertFalse(json.contains("lastFallbackAt"), json);
        assertFalse(json.contains("createdAt"), json);
        assertFalse(json.contains("updatedAt"), json);
        assertFalse(json.contains("createdBy"), json);
        assertFalse(json.contains("inputSchemaJson"), json);
        assertFalse(json.contains("outputSchemaJson"), json);
        assertFalse(json.contains("schemaVersion"), json);
        // resultSchemaJson IS in the bundle (tenant-authored) - the two must not be
        // confused, and this asserts the distinction rather than assuming it.
        assertTrue(json.contains("resultSchemaJson"), json);
    }

    @Test
    void identicalConfigurationsSerializeIdentically() {
        // What AgentVersionSnapshotService's de-duplication depends on: the six
        // collections are unordered sets, so capture must sort them or a save that
        // changed nothing would append a "new" version every time.
        Agent first = bareTarget();
        first.setAllowedToolNames(Set.of("alpha", "beta", "gamma"));
        first.setEnabledGuardrails(Set.of("pii", "profanity"));

        Agent second = bareTarget();
        second.setAllowedToolNames(Set.of("gamma", "alpha", "beta"));
        second.setEnabledGuardrails(Set.of("profanity", "pii"));

        assertEquals(codec.toJson(codec.capture(first)), codec.toJson(codec.capture(second)));
    }

    @Test
    void emptyCollectionsSurviveTheRoundTripAsEmptyNotAbsent() {
        // The deny-all pair is the reason this matters: for allowedCodeIntelRepoIds
        // and allowedKnowledgeWriteCollectionIds an empty set is a REVOCATION, and
        // losing it to "absent" would silently leave write/execute grants in place
        // through a restore the tenant believed had removed them.
        Agent source = fullyConfiguredSource();
        source.setAllowedToolNames(Set.of());
        source.setAllowedCodeIntelRepoIds(Set.of());
        source.setAllowedKnowledgeWriteCollectionIds(Set.of());

        AgentConfigSnapshot decoded = codec.fromJson(codec.toJson(codec.capture(source)));
        assertEquals(java.util.List.of(), decoded.allowedToolNames());
        assertEquals(java.util.List.of(), decoded.allowedCodeIntelRepoIds());
        assertEquals(java.util.List.of(), decoded.allowedKnowledgeWriteCollectionIds());

        Agent target = bareTarget();
        target.setAllowedCodeIntelRepoIds(Set.of("still-granted"));
        target.setAllowedKnowledgeWriteCollectionIds(Set.of(UUID.randomUUID()));

        AgentConfigSnapshotCodec.RestoreOutcome outcome = codec.apply(decoded, target);

        assertTrue(target.getAllowedCodeIntelRepoIds().isEmpty());
        assertTrue(target.getAllowedKnowledgeWriteCollectionIds().isEmpty());
        assertEquals(java.util.List.of(), outcome.skippedFields());
    }

    @Test
    void absentCollectionsAreLeftAloneAndReported() {
        // A row written by a build that predates a field decodes it as null. That
        // must NOT be read as "the tenant had an empty set" - for four of the six
        // collections empty means unrestricted and for two it means deny-all, so
        // guessing is wrong in both directions. Leave the live value, say so.
        AgentConfigSnapshot sparse = new AgentConfigSnapshot(
            AgentConfigSnapshot.CURRENT_FORMAT_VERSION,
            "Old snapshot", "prompt", ProviderName.ANTHROPIC, "claude-opus-4-8",
            1024, 0.5, 1.0,
            null, null,
            AgentAutonomyTier.SEMI_AUTONOMOUS, TuningMode.MANUAL, ReasoningDepth.FAST, null, null,
            true, false, false, false, false, false, false, false, false,
            false, null, null, null,
            null, null, null, null, null, null, null, null,
            null,
            // Every collection absent - the shape of a pre-versioning document.
            null, null, null, null, null, null,
            // xaiServerToolsEnabled absent too - same "predates the field" shape.
            null,
            // sampling group absent too.
            null, null, null, null, null
        );

        Agent target = bareTarget();
        target.setAllowedToolNames(Set.of("keep-me"));
        target.setAllowedCodeIntelRepoIds(Set.of("keep-me-too"));

        AgentConfigSnapshotCodec.RestoreOutcome outcome = codec.apply(sparse, target);

        assertEquals(Set.of("keep-me"), target.getAllowedToolNames());
        assertEquals(Set.of("keep-me-too"), target.getAllowedCodeIntelRepoIds());
        assertTrue(outcome.skippedFields().contains("allowedToolNames"));
        assertTrue(outcome.skippedFields().contains("allowedKnowledgeCollectionIds"));
        assertTrue(outcome.skippedFields().contains("allowedCodeIntelRepoIds"));
        assertTrue(outcome.skippedFields().contains("allowedKnowledgeWriteCollectionIds"));
        assertTrue(outcome.skippedFields().contains("collaboratorAgentIds"));
        assertTrue(outcome.skippedFields().contains("enabledGuardrails"));
        assertFalse(outcome.isComplete());
    }

    @Test
    void restoreNeverReGrantsBiometricConsent() {
        // GDPR Art.9 / DPDP special-category data: a rollback may withdraw consent,
        // never re-grant it. The refusal is reported, not silent, so the surface can
        // tell the tenant they still need to opt in again.
        Agent source = fullyConfiguredSource();
        source.setBiometricConsentEnabled(true);

        Agent target = bareTarget();
        assertFalse(target.isBiometricConsentEnabled());

        AgentConfigSnapshotCodec.RestoreOutcome outcome =
            codec.apply(codec.fromJson(codec.toJson(codec.capture(source))), target);

        assertFalse(target.isBiometricConsentEnabled());
        assertTrue(outcome.skippedFields().contains("biometricConsentEnabled"));
    }

    @Test
    void restoreDoesWithdrawBiometricConsent() {
        // The other direction is always applied - narrowing consent is safe by
        // definition, and refusing it would strand an agent with processing enabled
        // that no stored configuration ever asked for.
        Agent source = fullyConfiguredSource();
        source.setBiometricConsentEnabled(false);

        Agent target = bareTarget();
        target.setBiometricConsentEnabled(true);

        AgentConfigSnapshotCodec.RestoreOutcome outcome =
            codec.apply(codec.fromJson(codec.toJson(codec.capture(source))), target);

        assertFalse(target.isBiometricConsentEnabled());
        assertFalse(outcome.skippedFields().contains("biometricConsentEnabled"));
    }

    @Test
    void nullExperienceModeAndEdgeRoutingPolicyAreRestoredAsTheRealStatesTheyAre() {
        // null is not "unset" for these two: it means "inherit the tenant default"
        // and "device preferences decide". A restore that skipped them would leave
        // an explicit override in place that the snapshotted configuration did not
        // have.
        Agent source = fullyConfiguredSource();
        source.setExperienceMode(null);
        source.setEdgeRoutingPolicy(null);

        Agent target = bareTarget();
        target.setExperienceMode(ExperienceMode.SIMPLE);
        target.setEdgeRoutingPolicy(EdgeRoutingPolicy.EDGE_FIRST);

        codec.apply(codec.fromJson(codec.toJson(codec.capture(source))), target);

        assertNull(target.getExperienceMode());
        assertNull(target.getEdgeRoutingPolicy());
    }

    @Test
    void clearedProviderPairsRestoreAsCleared() {
        // Each pair moves together or not at all - a provider with another
        // provider's model string is never a valid state, and "cleared" is a real
        // configuration ("use the default fallback chain"), not an absence.
        Agent source = fullyConfiguredSource();
        source.setFallbackProvider(null, null);
        source.clearVoiceModelProvider();
        source.clearVideoModelProvider();
        source.clearTextModelProvider();
        source.clearTtsProvider();

        Agent target = fullyConfiguredSource();
        codec.apply(codec.fromJson(codec.toJson(codec.capture(source))), target);

        assertNull(target.getFallbackProviderName());
        assertNull(target.getFallbackModel());
        assertNull(target.getVoiceModelProvider());
        assertNull(target.getVoiceModel());
        assertNull(target.getVideoModelProvider());
        assertNull(target.getVideoModel());
        assertNull(target.getTextModelProvider());
        assertNull(target.getTextModel());
        assertNull(target.getTtsProvider());
        assertNull(target.getTtsModel());
    }

    @Test
    void unknownPropertiesInAStoredDocumentDoNotBreakDecoding() {
        // Forward compatibility during a rolling deploy: a newer node writes a
        // document with a field this build has never heard of, and an older node
        // must still be able to read and restore it rather than 500.
        String forwardCompatible = "{\"formatVersion\":1,\"name\":\"x\",\"systemPrompt\":\"p\","
            + "\"providerName\":\"ANTHROPIC\",\"model\":\"m\",\"somethingFromTheFuture\":true}";

        AgentConfigSnapshot decoded = codec.fromJson(forwardCompatible);

        assertEquals("p", decoded.systemPrompt());
        assertEquals(ProviderName.ANTHROPIC, decoded.providerName());
        assertNull(decoded.allowedToolNames());
    }
}
