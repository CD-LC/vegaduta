package com.agenticai.core.agents.core;

import com.agenticai.core.agents.budget.BudgetPolicy;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A configured agent: system prompt + which provider/model it talks to,
 * plus (Stage 4) MCP tool access and (Stage 6) an optional knowledge base
 * attachment.
 *
 * <p>The allow-lists, guardrails, per-modality overrides, schemas and audit actors are declared on
 * {@link AgentConfigColumns}, a mapped superclass: same {@code agent} table and child tables, same
 * columns, same entity name.
 */
@Entity
@Table(name = "agent")
public class Agent extends AgentConfigColumns {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Optimistic lock (JAVA-007, migration tenant/V365.2): every UPDATE is
     * {@code WHERE id = ? AND version = ?}, so a concurrent writer's save fails
     * with {@code ObjectOptimisticLockingFailureException} (HTTP 409) instead
     * of silently overwriting the other writer's columns. A wrapper type on
     * purpose: {@code null} marks a never-persisted entity for Spring Data.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    private String systemPrompt;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_name", nullable = false, length = 32)
    private ProviderName providerName;

    @Column(nullable = false, length = 128)
    private String model;

    // Field groups (embedded value objects). Same columns as before - the names are declared on
    // the value objects' fields - so there is no migration. The entity keeps its flat accessors
    // (getMaxTokens, isE2bEnabled, ...) as thin delegates; the objects themselves are immutable by
    // convention and are replaced, never mutated, so Hibernate's dirty check sees every change.
    @Embedded
    private GenerationParams generation = GenerationParams.of(0, 0, 0);

    @Embedded
    private FallbackPolicy fallback = FallbackPolicy.none();

    @Embedded
    private CachingPolicy caching = CachingPolicy.off();

    @Embedded
    private CapabilityFlags capabilities = CapabilityFlags.none();

    @Embedded
    private BudgetPolicy budget = BudgetPolicy.none();

    // Per-agent overrides of the runtime limits (migration V671): tool-step ceiling, timeouts, tool-output
    // cap, retrieval top-k/min-score, continuation passes. Null (every column null) = nothing overridden,
    // the tenant defaults and then the platform settings apply, so the column's existence changes no
    // pre-existing agent. Like the run cap, deliberately NOT part of AgentConfigSnapshot: a hidden A/B
    // treatment clone runs under its control agent's limits (see AgentProjection).
    @Embedded
    private RuntimeLimits runtimeLimits;

    // #815 Governance follow-up - see AgentAutonomyTier's javadoc. Never
    // null; SEMI_AUTONOMOUS both here and in migration V74's backfill, so
    // this column's existence changes zero pre-existing agent behavior.
    @Enumerated(EnumType.STRING)
    @Column(name = "autonomy_tier", nullable = false, length = 32)
    private AgentAutonomyTier autonomyTier = AgentAutonomyTier.SEMI_AUTONOMOUS;

    // Direct feedback: "in agents there should be simple and manual tuning
    // selection" - see TuningMode's javadoc. Never null; defaults to MANUAL
    // both here and in migration V64's backfill, so no pre-existing agent's
    // generation behavior changes by this column's mere existence.
    @Enumerated(EnumType.STRING)
    @Column(name = "tuning_mode", nullable = false, length = 16)
    private TuningMode tuningMode;

    // Edge Wave 2, EDGE-B (migration V190) - server-declared routing policy
    // for on-device turns; see EdgeRoutingPolicy's javadoc for the values and
    // the client-side-enforcement limitation. NULLABLE on purpose, unlike
    // autonomyTier/tuningMode above: null means "no policy - device-local
    // preferences decide", the exact pre-existing behavior, so no backfill
    // exists and no pre-existing agent changes behavior.
    @Enumerated(EnumType.STRING)
    @Column(name = "edge_routing_policy", length = 16)
    private EdgeRoutingPolicy edgeRoutingPolicy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Migration V35 - null means "inherit the tenant's current default",
    // see that migration's header comment. When resolved to SIMPLE (either
    // set here directly or inherited), AgentService forces
    // knowledgeBaseEnabled on and allowedToolNames empty at creation time,
    // and the web console hides the manual tool/knowledge-restriction
    // editors for this agent - see ExperienceMode's javadoc.
    @Enumerated(EnumType.STRING)
    @Column(name = "experience_mode", length = 16)
    private ExperienceMode experienceMode;

    // Agent Intelligence research doc (July 2026), migration V43 - see
    // ReasoningDepth's javadoc. Never null; defaults to FAST (today's
    // unchanged behavior) both here and in the migration's backfill, so no
    // pre-existing agent's behavior changes by this column's mere existence.
    @Enumerated(EnumType.STRING)
    @Column(name = "reasoning_depth", nullable = false, length = 16)
    private ReasoningDepth reasoningDepth;

    // A/B experiments on agent versions (migration V265) - non-null exactly
    // when this row is a HIDDEN treatment clone created by
    // com.agenticai.core.agents.experiment.AgentExperimentService#create,
    // pointing at the base (customer-visible) agent it was cloned from. Set
    // once, at construction, via the dedicated constructor overload below -
    // never exposed through a setter, so no ordinary agent-update DTO can
    // ever turn a real agent into a clone or vice versa. AgentService#listAll
    // filters out every row where this is non-null: a clone is
    // implementation detail for AgentChatService's traffic routing, never a
    // customer-visible agent in its own right.
    @Column(name = "experiment_clone_of")
    private UUID experimentCloneOf;

    protected Agent() {
        // JPA
    }

    public Agent(
        String name,
        String systemPrompt,
        ProviderName providerName,
        String model,
        int maxTokens,
        double temperature,
        double topP,
        boolean knowledgeBaseEnabled,
        ExperienceMode experienceMode
    ) {
        this(name, systemPrompt, providerName, model, maxTokens, temperature, topP,
            knowledgeBaseEnabled, experienceMode, ReasoningDepth.FAST);
    }

    public Agent(
        String name,
        String systemPrompt,
        ProviderName providerName,
        String model,
        int maxTokens,
        double temperature,
        double topP,
        boolean knowledgeBaseEnabled,
        ExperienceMode experienceMode,
        ReasoningDepth reasoningDepth
    ) {
        this(name, systemPrompt, providerName, model, maxTokens, temperature, topP,
            knowledgeBaseEnabled, experienceMode, reasoningDepth, TuningMode.MANUAL);
    }

    public Agent(
        String name,
        String systemPrompt,
        ProviderName providerName,
        String model,
        int maxTokens,
        double temperature,
        double topP,
        boolean knowledgeBaseEnabled,
        ExperienceMode experienceMode,
        ReasoningDepth reasoningDepth,
        TuningMode tuningMode
    ) {
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.providerName = providerName;
        this.model = model;
        this.generation = GenerationParams.of(maxTokens, temperature, topP);
        this.capabilities = CapabilityFlags.none().withKnowledgeBase(knowledgeBaseEnabled);
        this.experienceMode = experienceMode;
        this.reasoningDepth = reasoningDepth != null ? reasoningDepth : ReasoningDepth.FAST;
        this.tuningMode = tuningMode != null ? tuningMode : TuningMode.MANUAL;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * The ONLY way to create a treatment clone - see {@link #experimentCloneOf}'s
     * javadoc. {@code experimentCloneOf} is required non-null here (a clone
     * created any other way would be indistinguishable from a real agent and
     * would leak into {@code AgentService#listAll}); a normal agent keeps using
     * the constructor above, which leaves the field null.
     */
    public Agent(
        String name,
        String systemPrompt,
        ProviderName providerName,
        String model,
        int maxTokens,
        double temperature,
        double topP,
        boolean knowledgeBaseEnabled,
        ExperienceMode experienceMode,
        ReasoningDepth reasoningDepth,
        TuningMode tuningMode,
        UUID experimentCloneOf
    ) {
        this(name, systemPrompt, providerName, model, maxTokens, temperature, topP,
            knowledgeBaseEnabled, experienceMode, reasoningDepth, tuningMode);
        this.experimentCloneOf = java.util.Objects.requireNonNull(
            experimentCloneOf, "experimentCloneOf is required for this constructor - use the base constructor for a real agent.");
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public ProviderName getProviderName() {
        return providerName;
    }

    public String getModel() {
        return model;
    }

    public int getMaxTokens() {
        return generation.getMaxTokens();
    }

    public double getTemperature() {
        return generation.getTemperature();
    }

    public double getTopP() {
        return generation.getTopP();
    }

    /** Nullable - null means "no fallback configured," the default for every agent. */
    public ProviderName getFallbackProviderName() {
        return fallback.getProviderName();
    }

    /** Nullable, only meaningful alongside a non-null {@link #getFallbackProviderName()}. */
    public String getFallbackModel() {
        return fallback.getModel();
    }

    /**
     * @param fallbackProviderName null clears the fallback (reverts to
     *     today's behavior: a provider failure surfaces to the user).
     * @param fallbackModel required whenever fallbackProviderName is
     *     non-null - AgentChatService needs a concrete model id to build
     *     the fallback ChatClient, same as the primary provider/model pair.
     */
    public void setFallbackProvider(ProviderName fallbackProviderName, String fallbackModel) {
        this.fallback = fallback.withFallback(fallbackProviderName, fallbackModel);
        this.updatedAt = Instant.now();
    }

    /** How many times this agent's fallback provider has been attempted (whether or not it succeeded). */
    public long getFallbackTriggeredCount() {
        return fallback.getTriggeredCount();
    }

    /** How many of those attempts also failed - the fallback couldn't rescue the turn either. */
    public long getFallbackFailedCount() {
        return fallback.getFailedCount();
    }

    /** Nullable - null means this agent's fallback has never fired. */
    public Instant getLastFallbackAt() {
        return fallback.getLastFallbackAt();
    }

    /**
     * Unlimited-plan capability ("users should be able to...change the
     * models") - lets a customer switch which provider/model this existing
     * agent talks to, after creation. Both always update together, same
     * "never partially" reasoning as {@link #setGenerationParams} - a
     * providerName with a stale model string from a different provider is
     * not a valid state. Gated by {@code AgentService#assertEditable} at the
     * call site, not here (this entity has no tenant/plan awareness).
     */
    public void setProviderModel(ProviderName providerName, String model) {
        this.providerName = providerName;
        this.model = model;
        this.updatedAt = Instant.now();
    }

    /**
     * Consumer-facing capability - "consumers should be able to edit the
     * agent prompt". Same shape as {@link #setProviderModel}: gated by
     * {@code AgentService#assertEditable} at the call site, not here. Until
     * this setter existed, systemPrompt was write-once (set only via the
     * constructor at creation) - AgentWorkbench.js's own persona card
     * explicitly said "editing an existing agent's persona isn't supported
     * yet," which was literally true at the entity level, not just a UI gap.
     */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        this.updatedAt = Instant.now();
    }

    /** Never null - see {@link AgentAutonomyTier}'s javadoc for why SEMI_AUTONOMOUS is always the floor. */
    public AgentAutonomyTier getAutonomyTier() {
        return autonomyTier;
    }

    public void setAutonomyTier(AgentAutonomyTier autonomyTier) {
        this.autonomyTier = autonomyTier != null ? autonomyTier : AgentAutonomyTier.SEMI_AUTONOMOUS;
        this.updatedAt = Instant.now();
    }

    /**
     * Lets a customer tune this agent's generation behavior after creation
     * (temperature/topP/maxTokens), not just at creation time - see
     * {@code AgentService#updateGenerationParams}. All three always update
     * together (never partially) so the entity never sits in a state where
     * only some of a single edit took effect.
     */
    public void setGenerationParams(int maxTokens, double temperature, double topP) {
        this.generation = generation.withCore(maxTokens, temperature, topP);
        this.updatedAt = Instant.now();
    }

    public Integer getTopK() {
        return generation.getTopK();
    }

    /** Never null. */
    public List<String> getStopSequences() {
        return generation.getStopSequences();
    }

    public Double getFrequencyPenalty() {
        return generation.getFrequencyPenalty();
    }

    public Double getPresencePenalty() {
        return generation.getPresencePenalty();
    }

    public Integer getSeed() {
        return generation.getSeed();
    }

    /** Replaces all five extra sampling controls at once (null / empty = unset). */
    public void setSamplingParams(
        Integer topK, List<String> stopSequences, Double frequencyPenalty, Double presencePenalty, Integer seed
    ) {
        this.generation = generation.withSampling(topK, stopSequences, frequencyPenalty, presencePenalty, seed);
        this.updatedAt = Instant.now();
    }

    /** Never null - see {@link TuningMode}'s javadoc for why MANUAL is always the floor, never null. */
    public TuningMode getTuningMode() {
        return tuningMode;
    }

    public void setTuningMode(TuningMode tuningMode) {
        this.tuningMode = tuningMode != null ? tuningMode : TuningMode.MANUAL;
        this.updatedAt = Instant.now();
    }

    /** Nullable - null means "no server policy, device preferences decide" (see field javadoc). */
    public EdgeRoutingPolicy getEdgeRoutingPolicy() {
        return edgeRoutingPolicy;
    }

    /** Null CLEARS the policy (back to device-preference routing) - a legitimate state, not a default. */
    public void setEdgeRoutingPolicy(EdgeRoutingPolicy edgeRoutingPolicy) {
        this.edgeRoutingPolicy = edgeRoutingPolicy;
        this.updatedAt = Instant.now();
    }

    /** Nullable - null means "use the platform run cap" (see field comment). */
    public java.math.BigDecimal getRunBudgetUsd() {
        return budget.getRunBudgetUsd();
    }

    /** Null CLEARS the per-agent cap (back to the platform cap). */
    public void setRunBudgetUsd(java.math.BigDecimal runBudgetUsd) {
        this.budget = budget.withRunBudgetUsd(runBudgetUsd);
        this.updatedAt = Instant.now();
    }

    public boolean isKnowledgeBaseEnabled() {
        return capabilities.isKnowledgeBaseEnabled();
    }

    /** Null means "inherit the tenant's current default" - see this field's own javadoc. */
    public ExperienceMode getExperienceMode() {
        return experienceMode;
    }

    public void setExperienceMode(ExperienceMode experienceMode) {
        this.experienceMode = experienceMode;
        this.updatedAt = Instant.now();
    }

    public void setKnowledgeBaseEnabled(boolean knowledgeBaseEnabled) {
        this.capabilities = capabilities.withKnowledgeBase(knowledgeBaseEnabled);
        this.updatedAt = Instant.now();
    }

    public boolean isSarvamEnabled() {
        return capabilities.isSarvamEnabled();
    }

    public void setSarvamEnabled(boolean sarvamEnabled) {
        this.capabilities = capabilities.withSarvam(sarvamEnabled);
        this.updatedAt = Instant.now();
    }

    public boolean isE2bEnabled() {
        return capabilities.isE2bEnabled();
    }

    public void setE2bEnabled(boolean e2bEnabled) {
        this.capabilities = capabilities.withE2b(e2bEnabled);
        this.updatedAt = Instant.now();
    }

    public boolean isXaiServerToolsEnabled() {
        return capabilities.isXaiServerToolsEnabled();
    }

    public void setXaiServerToolsEnabled(boolean xaiServerToolsEnabled) {
        this.capabilities = capabilities.withXaiServerTools(xaiServerToolsEnabled);
        this.updatedAt = Instant.now();
    }

    public boolean isCodeWorkspaceEnabled() {
        return capabilities.isCodeWorkspaceEnabled();
    }

    public void setCodeWorkspaceEnabled(boolean codeWorkspaceEnabled) {
        this.capabilities = capabilities.withCodeWorkspace(codeWorkspaceEnabled);
        this.updatedAt = Instant.now();
    }

    public boolean isKnowledgeWriteEnabled() {
        return capabilities.isKnowledgeWriteEnabled();
    }

    public void setKnowledgeWriteEnabled(boolean knowledgeWriteEnabled) {
        this.capabilities = capabilities.withKnowledgeWrite(knowledgeWriteEnabled);
        this.updatedAt = Instant.now();
    }

    public boolean isResponseCachingEnabled() {
        return caching.isResponseCachingEnabled();
    }

    public void setResponseCachingEnabled(boolean responseCachingEnabled) {
        this.caching = caching.withResponseCaching(responseCachingEnabled);
        this.updatedAt = Instant.now();
    }

    public boolean isSemanticResponseCachingEnabled() {
        return caching.isSemanticResponseCachingEnabled();
    }

    public void setSemanticResponseCachingEnabled(boolean semanticResponseCachingEnabled) {
        this.caching = caching.withSemanticResponseCaching(semanticResponseCachingEnabled);
        this.updatedAt = Instant.now();
    }

    public boolean isCostRoutingEnabled() {
        return budget.isCostRoutingEnabled();
    }

    public ProviderName getEconomyProviderName() {
        return budget.getEconomyProviderName();
    }

    public String getEconomyModel() {
        return budget.getEconomyModel();
    }

    public Double getCostRoutingJevThreshold() {
        return budget.getCostRoutingJevThreshold();
    }

    /**
     * Same "both together or neither" shape as {@link
     * #setFallbackProvider(ProviderName, String)} - {@code
     * economyProviderName} null clears the economy-model pairing regardless
     * of what {@code costRoutingEnabled} is set to (a dangling model with no
     * provider is never a valid state), and {@code economyModel} is only
     * meaningful when a provider is also set. {@code jevThreshold} is the
     * per-agent Jev sensitivity override - null inherits the tenant/platform
     * default (see the {@code costRoutingJevThreshold} field javadoc).
     */
    public void setCostRouting(boolean costRoutingEnabled, ProviderName economyProviderName, String economyModel, Double jevThreshold) {
        this.budget = budget.withCostRouting(costRoutingEnabled, economyProviderName, economyModel, jevThreshold);
        this.updatedAt = Instant.now();
    }

    public boolean isShareMemoryWithTenant() {
        return capabilities.isShareMemoryWithTenant();
    }

    public void setShareMemoryWithTenant(boolean shareMemoryWithTenant) {
        this.capabilities = capabilities.withShareMemoryWithTenant(shareMemoryWithTenant);
        this.updatedAt = Instant.now();
    }

    public boolean isBiometricConsentEnabled() {
        return capabilities.isBiometricConsentEnabled();
    }

    public void setBiometricConsentEnabled(boolean biometricConsentEnabled) {
        this.capabilities = capabilities.withBiometricConsent(biometricConsentEnabled);
        this.updatedAt = Instant.now();
    }

    /** Never null - see this field's own javadoc for why FAST is always the floor, never null. */
    public ReasoningDepth getReasoningDepth() {
        return reasoningDepth;
    }

    public void setReasoningDepth(ReasoningDepth reasoningDepth) {
        this.reasoningDepth = reasoningDepth != null ? reasoningDepth : ReasoningDepth.FAST;
        this.updatedAt = Instant.now();
    }

    /** The generation and sampling controls as one immutable value. */
    public GenerationParams generationParams() {
        return generation;
    }

    /** The provider-fallback configuration and counters as one immutable value. */
    public FallbackPolicy fallbackPolicy() {
        return fallback;
    }

    /** The response-cache opt-ins as one immutable value. */
    public CachingPolicy cachingPolicy() {
        return caching;
    }

    /** The boolean capability opt-ins as one immutable value. */
    public CapabilityFlags capabilityFlags() {
        return capabilities;
    }

    /** The agent's own runtime-limit overrides; never null, empty when nothing is overridden. */
    public RuntimeLimits getRuntimeLimits() {
        return runtimeLimits == null ? RuntimeLimits.none() : runtimeLimits;
    }

    /** Replaces the agent's runtime-limit overrides; null or empty clears them all. */
    public void setRuntimeLimits(RuntimeLimits limits) {
        this.runtimeLimits = limits == null || limits.isEmpty() ? null : limits;
    }

    /** The run cap and cost routing as one immutable value. */
    public BudgetPolicy budgetPolicy() {
        return budget;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Nullable - non-null exactly when this row is a hidden A/B-experiment
     * treatment clone, holding the id of the base agent it was cloned from.
     * Null for every ordinary agent. See this field's own javadoc.
     */
    public UUID getExperimentCloneOf() {
        return experimentCloneOf;
    }

    /** Optimistic-lock version; {@code null} until first persisted. */
    public Long getVersion() {
        return version;
    }

    @Override
    protected void touch() {
        this.updatedAt = Instant.now();
    }
}
