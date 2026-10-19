package com.agenticai.core.agents.core;

import com.agenticai.core.agents.modality.ModalityModels;
import com.agenticai.core.providers.modality.TextModelProvider;
import com.agenticai.core.providers.modality.TtsModelProvider;
import com.agenticai.core.providers.modality.VideoModelProvider;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MappedSuperclass;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code agent} row's scoping and attachment columns, mapped into {@link Agent}'s own table:
 * the tool/knowledge/code-intel/collaborator allow-lists and guardrails (each an element-collection
 * table keyed by {@code agent_id}), the per-modality provider overrides, the generated and
 * tenant-authored JSON schemas, and the creator/editor audit actors.
 *
 * <p>A {@link MappedSuperclass}, not an entity: it has no table, no entity name and no identity of
 * its own, so the schema is exactly what it was when these fields lived on {@link Agent} (pinned by
 * {@code AgentEntityMappingTest}). It exists only to keep {@link Agent} small. Every setter that
 * changes configuration calls {@link #touch()}, which stamps {@code updated_at} on the entity.
 */
@MappedSuperclass
public abstract class AgentConfigColumns {

    /** Null when every override column is null (Hibernate's rule); read through {@link #modalities()}. */
    @Embedded
    private ModalityModels modalities = ModalityModels.none();

    // Autonomy/Guardrails Strategy Stage A (migration V12) - opt-in tool
    // allow-list. Empty means "no restriction", matching every agent's
    // behavior before this column existed - see the migration's own header
    // comment for why this is opt-in rather than the strategy doc's
    // eventual default-deny posture (that activates once a tenant-admin has
    // a UI to grant tools back, which doesn't exist yet). AgentToolProvider
    // only applies this filter when non-empty.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_allowed_tool", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "tool_name")
    private Set<String> allowedToolNames = new HashSet<>();

    // Knowledge Stage F1 (migration V16) - same opt-in, empty-means-
    // unrestricted shape as allowedToolNames above, applied to knowledge
    // collections instead of MCP tools: empty means "every collection this
    // tenant has is searchable" (today's behavior, unchanged), non-empty
    // restricts search_knowledge_base to just those collections. Only
    // matters once knowledgeBaseEnabled is true.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_allowed_knowledge_collection", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "collection_id")
    private Set<UUID> allowedKnowledgeCollectionIds = new HashSet<>();

    // Migration V103 - Agent.codeWorkspaceEnabled's repo scope. Unlike
    // allowedKnowledgeCollectionIds/allowedToolNames, EMPTY here means
    // "no repo granted" (deny-all), not "no restriction" - see migration
    // V103's own header comment for why the write/execute nature of these
    // tools flips the usual opt-in-allow-list default. Only matters once
    // codeWorkspaceEnabled is true; AgentToolProvider only offers these
    // tools at all when codeWorkspaceEnabled is true, and the tool
    // callbacks themselves (not just AgentToolProvider) re-check every
    // repo_id argument against this set before ever reaching
    // CodeIntelClient, since a workspace_id used by apply/run/diff/delete
    // doesn't re-derive its own repo scope.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_allowed_codeintel_repo", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "repo_id")
    private Set<String> allowedCodeIntelRepoIds = new HashSet<>();

    // Migration V198 - knowledgeWriteEnabled's collection scope. Deny-by-default like
    // allowedCodeIntelRepoIds (empty means "none granted"); AddToKnowledgeBaseToolCallback
    // re-checks every collection_id against this set itself.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_allowed_knowledge_write_collection", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "collection_id")
    private Set<UUID> allowedKnowledgeWriteCollectionIds = new HashSet<>();

    // Migration V56 - opt-in list of OTHER agents this agent may delegate a sub-question to.
    // Empty means no delegation tool is offered (AgentToolProvider#resolveCollaboratorTools).
    // Never contains this agent's own id; a delegated-to agent gets no delegation tools (depth-1 cap).
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_collaborator", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "collaborator_agent_id")
    private Set<UUID> collaboratorAgentIds = new HashSet<>();

    // Content guardrails (safety/filtering) - opt-in per-agent configuration.
    // Empty means "no guardrails applied" (default for backwards compatibility).
    // Allowed guardrails depend on tenant plan (FREE: profanity+jailbreak,
    // PREMIUM: +pii+policy_violation, UNLIMITED: +toxicity+custom).
    // AgentService validates against plan tier before allowing changes.
    // Migration V149 backfills every pre-existing agent to empty set.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_enabled_guardrail", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "guardrail_type")
    private Set<String> enabledGuardrails = new HashSet<>();

    /** Generated and tenant-authored JSON schemas (V170, V209); see {@link AgentSchemas}. */
    @Embedded private AgentSchemas schemas = AgentSchemas.none();

    protected AgentConfigColumns() {
        // JPA
    }

    /** The per-modality provider overrides as one immutable value (never null). */
    public ModalityModels modalityModels() {
        return modalities();
    }

    ModalityModels modalities() {
        return modalities == null ? ModalityModels.none() : modalities;
    }

    /** Unmodifiable - empty means "no restriction," never null. */
    public Set<String> getAllowedToolNames() {
        return Collections.unmodifiableSet(allowedToolNames);
    }

    public void setAllowedToolNames(Set<String> allowedToolNames) {
        this.allowedToolNames = allowedToolNames == null ? new HashSet<>() : new HashSet<>(allowedToolNames);
        touch();
    }

    /** Unmodifiable - empty means "every collection this tenant has", never null. */
    public Set<UUID> getAllowedKnowledgeCollectionIds() {
        return Collections.unmodifiableSet(allowedKnowledgeCollectionIds);
    }

    public void setAllowedKnowledgeCollectionIds(Set<UUID> allowedKnowledgeCollectionIds) {
        this.allowedKnowledgeCollectionIds = allowedKnowledgeCollectionIds == null
            ? new HashSet<>() : new HashSet<>(allowedKnowledgeCollectionIds);
        touch();
    }

    /**
     * Unmodifiable - empty means "no repo granted" (deny-all), the OPPOSITE
     * convention from {@link #getAllowedKnowledgeCollectionIds()} - see
     * this field's own javadoc and migration V103's header comment for why.
     * Never null.
     */
    public Set<String> getAllowedCodeIntelRepoIds() {
        return Collections.unmodifiableSet(allowedCodeIntelRepoIds);
    }

    /** Empty/null means "no repo granted" (deny-all) - see {@link #getAllowedCodeIntelRepoIds()}. */
    public void setAllowedCodeIntelRepoIds(Set<String> allowedCodeIntelRepoIds) {
        this.allowedCodeIntelRepoIds = allowedCodeIntelRepoIds == null
            ? new HashSet<>() : new HashSet<>(allowedCodeIntelRepoIds);
        touch();
    }

    /** Empty means "no collection granted" (deny-all) - see migration V198's header comment. */
    public Set<UUID> getAllowedKnowledgeWriteCollectionIds() {
        return Collections.unmodifiableSet(allowedKnowledgeWriteCollectionIds);
    }

    /** Empty/null means "no collection granted" (deny-all) - see {@link #getAllowedKnowledgeWriteCollectionIds()}. */
    public void setAllowedKnowledgeWriteCollectionIds(Set<UUID> allowedKnowledgeWriteCollectionIds) {
        this.allowedKnowledgeWriteCollectionIds = allowedKnowledgeWriteCollectionIds == null
            ? new HashSet<>() : new HashSet<>(allowedKnowledgeWriteCollectionIds);
        touch();
    }

    /** Unmodifiable - empty means "no delegation tool offered", never null. */
    public Set<UUID> getCollaboratorAgentIds() {
        return Collections.unmodifiableSet(collaboratorAgentIds);
    }

    /** Empty/null clears every delegation permission (back to "no collaborators"). */
    public void setCollaboratorAgentIds(Set<UUID> collaboratorAgentIds) {
        this.collaboratorAgentIds = collaboratorAgentIds == null ? new HashSet<>() : new HashSet<>(collaboratorAgentIds);
        touch();
    }

    /**
     * Nullable - the speech-to-text provider this agent uses for voice turns.
     * Null means the voice endpoint uses its default fallback chain (Sidecar → Sarvam).
     * When non-null, must be paired with a valid {@link #getVoiceModel()} value.
     */
    public VoiceModelProvider getVoiceModelProvider() {
        return modalities().getVoiceModelProvider();
    }

    /**
     * Nullable - the model name within the selected voice provider
     * (e.g. "saaras:v3" for Sarvam, ignored for Sidecar). Meaningful only
     * when {@link #getVoiceModelProvider()} is non-null.
     */
    public String getVoiceModel() {
        return modalities().getVoiceModel();
    }

    /**
     * Sets both the voice provider and model together. Both must be non-null,
     * and the provider must be available on this deployment (validated by
     * AgentService before this setter is called).
     *
     * @param voiceModelProvider the provider enum (e.g. SIDECAR_WHISPER, SARVAM)
     * @param voiceModel the model name (e.g. "saaras:v3")
     */
    public void setVoiceModelProvider(VoiceModelProvider voiceModelProvider, String voiceModel) {
        this.modalities = modalities().withVoice(voiceModelProvider, voiceModel);
        touch();
    }

    /**
     * Clears the voice model provider (reverts to the default fallback chain).
     */
    public void clearVoiceModelProvider() {
        this.modalities = modalities().withVoice(null, null);
        touch();
    }

    /**
     * Text-to-speech provider explicitly selected for this agent's voice
     * replies, or null to use {@code VoiceCallController}'s default
     * ElevenLabs-then-Sarvam fallback chain. Output-side twin of {@link
     * #getVoiceModelProvider()}.
     */
    public TtsModelProvider getTtsProvider() {
        return modalities().getTtsProvider();
    }

    /**
     * The specific TTS model name within {@link #getTtsProvider()}'s
     * provider (e.g. "eleven_multilingual_v2"). Only meaningful when {@link
     * #getTtsProvider()} is non-null.
     */
    public String getTtsModel() {
        return modalities().getTtsModel();
    }

    /**
     * Sets an explicit TTS provider/model pair. Both must be set together,
     * and the provider must be available on this deployment (validated by
     * AgentService before this setter is called).
     *
     * @param ttsProvider the provider enum (e.g. ELEVENLABS, SARVAM)
     * @param ttsModel the model name (e.g. "eleven_multilingual_v2")
     */
    public void setTtsProvider(TtsModelProvider ttsProvider, String ttsModel) {
        this.modalities = modalities().withTts(ttsProvider, ttsModel);
        touch();
    }

    /**
     * Clears the TTS provider selection (reverts to the default fallback chain).
     */
    public void clearTtsProvider() {
        this.modalities = modalities().withTts(null, null);
        touch();
    }

    /**
     * Nullable - the text/LLM provider this agent uses for chat turns.
     * Null means the agent uses its primary provider/model pair (backward compatible).
     * When non-null, must be paired with a valid {@link #getTextModel()} value.
     */
    public TextModelProvider getTextModelProvider() {
        return modalities().getTextModelProvider();
    }

    /**
     * Nullable - the model name within the selected text provider
     * (e.g. "claude-opus-4-8" for Anthropic, ignored if provider is null).
     * Meaningful only when {@link #getTextModelProvider()} is non-null.
     */
    public String getTextModel() {
        return modalities().getTextModel();
    }

    /**
     * Sets both the text provider and model together. Both must be non-null,
     * and the provider must be available on this deployment (validated by
     * AgentService before this setter is called).
     *
     * @param textModelProvider the provider enum (e.g. ANTHROPIC, OPENAI)
     * @param textModel the model name (e.g. "claude-opus-4-8")
     */
    public void setTextModelProvider(TextModelProvider textModelProvider, String textModel) {
        this.modalities = modalities().withText(textModelProvider, textModel);
        touch();
    }

    /**
     * Clears the text model provider (reverts to the primary provider/model).
     */
    public void clearTextModelProvider() {
        this.modalities = modalities().withText(null, null);
        touch();
    }

    /**
     * Nullable - the video-to-text provider this agent uses for video turns.
     * Null means video processing uses its default fallback chain (if any).
     * When non-null, must be paired with a valid {@link #getVideoModel()} value.
     */
    public VideoModelProvider getVideoModelProvider() {
        return modalities().getVideoModelProvider();
    }

    /**
     * Nullable - the model name within the selected video provider
     * (e.g. "whisper-1" for OpenAI, ignored for others). Meaningful only
     * when {@link #getVideoModelProvider()} is non-null.
     */
    public String getVideoModel() {
        return modalities().getVideoModel();
    }

    /**
     * Sets both the video provider and model together. Both must be non-null,
     * and the provider must be available on this deployment (validated by
     * AgentService before this setter is called).
     *
     * @param videoModelProvider the provider enum (e.g. OPENAI_WHISPER_VIDEO, SIDECAR_WHISPER_VIDEO)
     * @param videoModel the model name (e.g. "whisper-1")
     */
    public void setVideoModelProvider(VideoModelProvider videoModelProvider, String videoModel) {
        this.modalities = modalities().withVideo(videoModelProvider, videoModel);
        touch();
    }

    /**
     * Clears the video model provider (reverts to the default fallback chain).
     */
    public void clearVideoModelProvider() {
        this.modalities = modalities().withVideo(null, null);
        touch();
    }

    /** Unmodifiable - empty means "no guardrails enabled", never null. */
    public Set<String> getEnabledGuardrails() {
        return Collections.unmodifiableSet(enabledGuardrails);
    }

    public void setEnabledGuardrails(Set<String> guardrails) {
        this.enabledGuardrails = guardrails == null ? new HashSet<>() : new HashSet<>(guardrails);
        touch();
    }

    /** Creator and last editor (V151, V393); Hibernate leaves this null while both are null. */
    @Embedded private AgentAuditActors audit = AgentAuditActors.none();

    AgentAuditActors actors() {
        return audit == null ? AgentAuditActors.none() : audit;
    }

    /** Nullable - null means this agent predates the cross-user sharing feature. */
    public String getCreatedBy() {
        return actors().createdBy();
    }

    /** Called exactly once, by {@code AgentService#create}. */
    public void setCreatedBy(String createdBy) {
        this.audit = actors().withCreatedBy(createdBy);
    }

    /** Nullable - null until the first versioned save after migration V393. */
    public String getUpdatedBy() {
        return actors().updatedBy();
    }

    /** Called by {@code AgentService#saveVersioned} on every config-mutating save. */
    public void setUpdatedBy(String updatedBy) {
        this.audit = actors().withUpdatedBy(updatedBy);
    }

    /** Nullable - null until {@code AgentSchemaService#refresh} has run at least once (see field javadoc). */
    public String getInputSchemaJson() {
        return schemas.inputSchemaJson();
    }

    /** Nullable - null until {@code AgentSchemaService#refresh} has run at least once (see field javadoc). */
    public String getOutputSchemaJson() {
        return schemas.outputSchemaJson();
    }

    public int getSchemaVersion() {
        return schemas.schemaVersion();
    }

    /** Called only by {@code AgentSchemaService#refresh} - never set directly by request DTOs. */
    public void setGeneratedSchema(String inputSchemaJson, String outputSchemaJson) {
        this.schemas = schemas.withGenerated(inputSchemaJson, outputSchemaJson);
        touch();
    }

    /**
     * The tenant-authored JSON Schema every JOB of this agent must produce, or
     * {@code null} for "no structured output required" (the default).
     *
     * <p>NOT {@link #getOutputSchemaJson()} - see this field's own comment for
     * why the two must not be conflated: that one is regenerated by {@code
     * AgentSchemaService#refresh} on every save touching tools/knowledge/model,
     * this one is never regenerated by anything.
     */
    public String getResultSchemaJson() {
        return schemas.resultSchemaJson();
    }

    /**
     * Set only by the agent-job result-schema endpoints, after the schema has
     * been compiled by {@code JobResultSchemaValidator#requireUsableSchema} -
     * never store an uncompilable schema here, or every job submitted afterwards
     * fails at coercion time with an error that points at the job rather than at
     * the configuration that actually broke it. {@code null} clears it.
     */
    public void setResultSchemaJson(String resultSchemaJson) {
        this.schemas = schemas.withResultSchema(resultSchemaJson);
        touch();
    }

    /** Stamps the entity's {@code updated_at}; called by every configuration-changing setter. */
    protected abstract void touch();
}
