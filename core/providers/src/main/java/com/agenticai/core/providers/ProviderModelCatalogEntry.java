package com.agenticai.core.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One cached model entry for a provider's dropdown - see migration
 * {@code V66__provider_model_catalog.sql} (renumbered from V60 - see
 * application.yml's Flyway {@code locations} comment) for why this cache exists instead
 * of a live API call per page load, and {@link ProviderModelCatalogService}
 * for how the cache gets refreshed. Lives in {@code control_plane}, same as
 * {@link PlatformHostedLlmProvider} - model availability is platform-wide,
 * not tenant-scoped.
 */
@Entity
@Table(name = "provider_model_catalog", schema = "control_plane")
public class ProviderModelCatalogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_name", nullable = false, length = 64)
    private ProviderName providerName;

    @Column(name = "model_id", nullable = false)
    private String modelId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "context_window")
    private Integer contextWindow;

    // Chat attachment gating: "consumers should be able to attach audio and
    // other files if agents model supports those files in chat" - direct
    // feedback, July 2026. The chat/playground upload control reads these
    // per the agent's resolved provider+model before offering an attach
    // button for a given modality, instead of a blanket upload-anything UI.
    // See migration V61 and ProviderModelCatalogService's SEED_MODELS javadoc
    // for how each provider's flags were determined.
    @Column(name = "supports_image_input", nullable = false)
    private boolean supportsImageInput;

    @Column(name = "supports_audio_input", nullable = false)
    private boolean supportsAudioInput;

    @Column(name = "supports_video_input", nullable = false)
    private boolean supportsVideoInput;

    // "What can this model do" blurb for the model picker - see
    // ProviderModelResponse's javadoc for the full design (LLM-generated,
    // cached here once per model; ProviderModelResponse.from() falls back to
    // a deterministic template whenever this is null/blank, so the DTO field
    // is never empty even before generation has run for a given row). Column
    // added by V178 - see that migration for why this table (control_plane,
    // not tenant) took the next free version number.
    @Column(name = "capability_summary", columnDefinition = "TEXT")
    private String capabilitySummary;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProviderModelCatalogEntry() {
        // JPA
    }

    public ProviderModelCatalogEntry(ProviderName providerName, String modelId, String displayName, Integer contextWindow) {
        this(providerName, modelId, displayName, contextWindow, false, false, false);
    }

    public ProviderModelCatalogEntry(
            ProviderName providerName,
            String modelId,
            String displayName,
            Integer contextWindow,
            boolean supportsImageInput,
            boolean supportsAudioInput,
            boolean supportsVideoInput) {
        this.providerName = providerName;
        this.modelId = modelId;
        this.displayName = displayName;
        this.contextWindow = contextWindow;
        this.supportsImageInput = supportsImageInput;
        this.supportsAudioInput = supportsAudioInput;
        this.supportsVideoInput = supportsVideoInput;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public ProviderName getProviderName() {
        return providerName;
    }

    public String getModelId() {
        return modelId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public boolean isSupportsImageInput() {
        return supportsImageInput;
    }

    public boolean isSupportsAudioInput() {
        return supportsAudioInput;
    }

    public boolean isSupportsVideoInput() {
        return supportsVideoInput;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getCapabilitySummary() {
        return capabilitySummary;
    }

    /**
     * Only setter on this otherwise-immutable-after-construction entity -
     * every other field is replace-not-merge (see {@link
     * ProviderModelCatalogRepository}'s javadoc), but the capability-summary
     * generation job needs to update just this one column on an existing
     * row without re-running the provider refresh that produced it.
     */
    public void setCapabilitySummary(String capabilitySummary) {
        this.capabilitySummary = capabilitySummary;
    }
}
