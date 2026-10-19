package com.agenticai.core.providers.capability;

import com.agenticai.core.providers.ProviderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A stored, encrypted credential for one provider, tagged with the {@code
 * Capability} values (TTS, STT, TRANSLATE, CHAT, ...) it can serve.
 *
 * <p>Phase 1 scaffolding for {@code docs/GENERIC-PROVIDER-CAPABILITY-REGISTRY-2026-08-09.md}
 * section 3.1 - the unified table that extends {@code LlmProviderCredential}'s
 * proven single-row-per-provider shape (§1.1 of that doc) with the two things
 * it can't express: a credential serving more than one capability (Sarvam's
 * TTS/STT/TRANSLATE need, §1.2), and a typed slot for per-provider second-step
 * config (ElevenLabs' selected voice_id, §3.1).
 *
 * <p><b>First live provider: TypeSafe/Jev (V352).</b> {@code JevDecisionService}
 * resolves tenant keys from here ({@code provider_key=TYPESAFE}, {@code
 * capabilities={DECISION}}) ahead of the doc's original ElevenLabs/Sarvam
 * ordering - V352 backfills {@code jev_credential} rows into this table and
 * retires the Jev-specific entity. {@code elevenlabs_credential} and {@code
 * sarvam_credential} remain the tables {@code SpeakBackendRegistry} resolves
 * against until their own cutover phase lands.
 *
 * <p>{@code providerKey} is deliberately a plain string, not an enum - the
 * design doc describes it as "a superset enum" of every provider the platform
 * knows about (LLM chat providers, TTS/STT-only providers, future modalities),
 * which does not exist yet as a single type ({@link com.agenticai.core.providers.ProviderName}
 * only covers LLM chat providers). Introducing that superset enum is a
 * decision for whichever phase actually starts writing to this table, not
 * this additive scaffolding pass.
 *
 * <p>{@code encryptedSecret} is ciphertext only (AES-256-GCM via {@link
 * com.agenticai.core.crypto.EncryptionService}, same convention as {@link
 * com.agenticai.core.providers.LlmProviderCredential#getEncryptedApiKey()}) -
 * for multi-field credentials (e.g. a Vertex-shaped project/location/
 * service-account blob) this is a single-column-encoded blob, the same
 * technique {@code VertexCredential}'s javadoc documents.
 */
@Entity
@Table(
    name = "provider_credential",
    uniqueConstraints = @UniqueConstraint(columnNames = "provider_key")
)
public class ProviderCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "provider_key", nullable = false, length = 32)
    private String providerKey;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "capabilities", nullable = false, columnDefinition = "varchar(32)[]")
    private List<String> capabilities;

    @Column(name = "encrypted_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedSecret;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProviderStatus status;

    /** Raw JSON text, e.g. {@code {"voiceId": "..."}} - not deserialized into a Java shape here since every provider's extra config is different; callers own parsing. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_config", columnDefinition = "jsonb")
    private String extraConfig;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProviderCredential() {
        // JPA
    }

    public ProviderCredential(
        String providerKey,
        List<String> capabilities,
        String encryptedSecret,
        ProviderStatus status,
        String extraConfig
    ) {
        this.providerKey = providerKey;
        this.capabilities = new ArrayList<>(capabilities);
        this.encryptedSecret = encryptedSecret;
        this.status = status;
        this.extraConfig = extraConfig;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Mirrors {@link com.agenticai.core.providers.LlmProviderCredential#reactivate}. */
    public void reactivate(String encryptedSecret, List<String> capabilities, ProviderStatus status) {
        this.encryptedSecret = encryptedSecret;
        this.capabilities = new ArrayList<>(capabilities);
        this.status = status;
        this.updatedAt = Instant.now();
    }

    /** Mirrors {@link com.agenticai.core.providers.LlmProviderCredential#deactivate} - marks INACTIVE rather than deleting, so an admin can see what was configured before. */
    public void deactivate() {
        this.status = ProviderStatus.INACTIVE;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public List<String> getCapabilities() {
        return Collections.unmodifiableList(capabilities);
    }

    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    public ProviderStatus getStatus() {
        return status;
    }

    public String getExtraConfig() {
        return extraConfig;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
