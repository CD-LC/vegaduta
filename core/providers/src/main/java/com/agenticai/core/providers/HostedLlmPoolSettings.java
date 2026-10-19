package com.agenticai.core.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Singleton master switch for the whole {@link PlatformHostedLlmProvider} pool - direct feedback:
 * "there should be a control in admin platform, to turn on or off, the platform use for llm - it
 * should not always be platform llm". Before this existed, the only way to stop FREE-tier chat
 * traffic from drawing on the hosted pool was to individually disable or delete every {@link
 * PlatformHostedLlmProvider} row; this is one obvious flip a platform-admin can toggle instead,
 * without touching those rows.
 *
 * <p>When {@link #enabled} is {@code false}, {@link
 * PlatformHostedLlmProviderService#resolveTopPriorityEnabled} returns empty regardless of what any
 * individual provider row says - {@code FreeTierChatClientService}/{@code AssistService}/{@code
 * WorkflowGraphGeneratorService} all already treat "no row available" as {@code
 * FreeTierProviderPoolEmptyException} (503, "platform configuration gap"), so disabling this pauses
 * every FREE-tier AI feature with the exact same, already-correct error path - no new failure mode
 * to handle.
 *
 * <p>Always {@code id = 1L}, same singleton-row convention as {@code
 * com.agenticai.core.platform.TranslationJobSettings} (see that class's javadoc). Lives in {@code
 * control_plane}, same as {@link PlatformHostedLlmProvider} - see that entity's javadoc for why
 * this module (not {@code core.platform}) is where platform-wide provider config lives.
 */
@com.agenticai.core.security.PlatformConfigEntity
@Entity
@Table(name = "hosted_llm_pool_settings", schema = "control_plane")
public class HostedLlmPoolSettings {

    /** Fixed singleton id - see this class's javadoc. */
    public static final long SINGLETON_ID = 1L;

    @Id private Long id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    protected HostedLlmPoolSettings() {
        // JPA
    }

    public HostedLlmPoolSettings(boolean enabled) {
        this.id = SINGLETON_ID;
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public void setEnabled(boolean enabled, String updatedBy) {
        this.enabled = enabled;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
