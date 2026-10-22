package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantLimits;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads/writes a tenant's "AI Model Control" overrides - {@code Tenant#aiModelPreferences}'s {@code
 * {featureKey: {providerName, model}}} JSON bag (migration V185). The tenant-scoped, JSON-bag
 * counterpart of {@link PlatformSettingsService} (platform-wide, flat key-value) - see this class's
 * difference from that one: those settings are global; these are one bag per tenant, keyed by
 * {@link AiModelFeature}.
 *
 * <p>Consumed by {@code TenantAiModelPreferenceController} (read/write surface) and by {@code
 * com.agenticai.core.chat.gateway.LlmGatewayService} (the actual routing decision point - see
 * {@link AiModelFeature}'s javadoc for exactly which feature key takes effect today).
 *
 * <p>No caching, same "admin/settings-panel usage volumes, not a hot path for WRITES" posture
 * {@link PlatformSettingsService} states for itself - but unlike that class, {@link #resolve} IS
 * called on every gated LLM call, so it stays a single indexed-by-primary-key row read (no N+1, no
 * table scan) rather than anything requiring a cache to be fast enough.
 */
@Service
public class TenantAiModelPreferenceService {

    private static final Logger log = LoggerFactory.getLogger(TenantAiModelPreferenceService.class);

    /**
     * Duplicated (not imported) from {@code com.agenticai.core.providers.ProviderName} - see {@link
     * AiModelPreference}'s javadoc for why core/tenancy cannot depend on core/providers. Kept in
     * sync by hand; a provider added there without being added here just means a tenant can't yet
     * target it from this settings page (fails closed with a 400, not a silently-ignored override)
     * until this list is updated - never a startup failure.
     */
    static final Set<String> KNOWN_PROVIDER_NAMES =
            Set.of(
                    "ANTHROPIC",
                    "OPENAI",
                    "AZURE_OPENAI",
                    "GOOGLE_VERTEX",
                    "AWS_BEDROCK",
                    "OLLAMA",
                    "OPENROUTER",
                    "GROK",
                    "GEMINI",
                    "SARVAM",
                    "MISTRAL",
                    "DEEPSEEK",
                    "GROQ",
                    "META",
                    "LOCAL_OPENAI",
                    "PLATFORM_HOSTED");

    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;
    private final TenantLimits tenantLimits;

    public TenantAiModelPreferenceService(
            TenantRepository tenantRepository,
            ObjectMapper objectMapper,
            TenantLimits tenantLimits) {
        this.tenantRepository = tenantRepository;
        this.objectMapper = objectMapper;
        this.tenantLimits = tenantLimits;
    }

    /**
     * All stored overrides for this tenant, keyed by {@code AiModelFeature#key()} (or any other
     * string - see {@link #upsert}'s javadoc for why unrecognized keys are still stored, not
     * rejected). Empty map (never null) if the tenant has no row, no stored bag, or a corrupt one -
     * same "callers never handle missing/corrupt as a hard failure" contract {@code
     * EdgeAgentsConfigController#resolveManifest} and {@code PlatformSettingsService}'s getters
     * already follow.
     */
    public Map<String, AiModelPreference> getAll(String tenantId) {
        Optional<Tenant> tenant = tenantRepository.findBySchemaName(tenantId);
        if (tenant.isEmpty()) {
            return Map.of();
        }
        return parse(tenant.get().getAiModelPreferences(), tenantId);
    }

    /**
     * The routing-time read: {@code LlmGatewayService#resolveClient}'s lookup. Tries {@code
     * featureKey} first; if that key has no stored override AND {@code featureKey} isn't already
     * {@link AiModelFeature#DEFAULT}'s key, falls back to the {@code DEFAULT} bucket - so a tenant
     * who only ever sets one, general override still gets it applied everywhere, without having to
     * configure every individual feature row. A blank/null {@code featureKey} is treated as {@code
     * DEFAULT} outright.
     */
    public Optional<AiModelPreference> resolve(String tenantId, String featureKey) {
        if (tenantId == null) {
            return Optional.empty();
        }
        String key =
                (featureKey == null || featureKey.isBlank())
                        ? AiModelFeature.DEFAULT.key()
                        : featureKey;
        Map<String, AiModelPreference> all = getAll(tenantId);
        AiModelPreference direct = all.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        if (!key.equals(AiModelFeature.DEFAULT.key())) {
            return Optional.ofNullable(all.get(AiModelFeature.DEFAULT.key()));
        }
        return Optional.empty();
    }

    /**
     * Upserts (or, with {@code preference == null}, clears) the override for one {@code
     * featureKey}, leaving every other stored key untouched - read-modify-write on the whole bag,
     * same one-full-replace-per-call posture {@link Tenant#updateAiModelPreferences} documents.
     *
     * <p>A write ({@code preference != null}) must name a known {@link AiModelFeature} key; an
     * unknown key would be stored as an orphan row nothing ever reads. Clearing ({@code preference
     * == null}) accepts any key so an orphan stored earlier (or a feature retired from the enum)
     * can still be removed.
     *
     * @throws ResponseStatusException 400 if {@code featureKey} is blank, or a write names an
     *     unknown feature, or {@code preference} is non-null with a blank {@code model} or a {@code
     *     providerName} that isn't one of {@link #KNOWN_PROVIDER_NAMES}.
     * @throws IllegalStateException if no {@code Tenant} row exists for {@code tenantId} - would
     *     mean {@code SecurityConfig} let an unprovisioned tenant through, same invariant {@code
     *     TenantSettingsController} relies on.
     */
    public Map<String, AiModelPreference> upsert(
            String tenantId, String featureKey, AiModelPreference preference) {
        if (featureKey == null || featureKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "featureKey must not be blank.");
        }
        if (preference != null) {
            if (AiModelFeature.fromKey(featureKey) == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown featureKey '"
                                + featureKey
                                + "'. Allowed values: "
                                + knownFeatureKeys()
                                + ".");
            }
            if (preference.model() == null || preference.model().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "model must not be blank.");
            }
            if (preference.providerName() == null
                    || !KNOWN_PROVIDER_NAMES.contains(preference.providerName())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "providerName must be one of " + KNOWN_PROVIDER_NAMES + ".");
            }
            // Any provider other than the platform-hosted pool is a BYOK
            // credential swap - gate it server-side on the tenant's actual
            // plan the same way ProviderCredentialResolver/TenantSettingsController
            // already do, so a FREE-tier tenant can't reach a paid capability
            // just because the UI's upgrade-lock is only a client-side hint.
            if (!"PLATFORM_HOSTED".equals(preference.providerName())
                    && !tenantLimits.byokAllowed(tenantId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Your plan does not allow bring-your-own-key model overrides.");
            }
        }
        Tenant tenant =
                tenantRepository
                        .findBySchemaName(tenantId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No Tenant row for schema '" + tenantId + "'"));
        Map<String, AiModelPreference> current =
                new LinkedHashMap<>(parse(tenant.getAiModelPreferences(), tenantId));
        if (preference == null) {
            current.remove(featureKey);
        } else {
            current.put(featureKey, preference);
        }
        tenant.updateAiModelPreferences(serialize(current, tenantId));
        tenantRepository.save(tenant);
        return current;
    }

    private static List<String> knownFeatureKeys() {
        return Arrays.stream(AiModelFeature.values()).map(AiModelFeature::key).toList();
    }

    private Map<String, AiModelPreference> parse(String stored, String tenantId) {
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    stored, new TypeReference<LinkedHashMap<String, AiModelPreference>>() {});
        } catch (JacksonException e) {
            log.warn(
                    "Tenant '{}' has unparseable ai_model_preferences JSON - treating as no overrides configured. {}",
                    tenantId,
                    e.getMessage());
            return Map.of();
        }
    }

    private String serialize(Map<String, AiModelPreference> preferences, String tenantId) {
        if (preferences.isEmpty()) {
            return null; // Clears the column back to "no overrides" rather than storing "{}".
        }
        try {
            return objectMapper.writeValueAsString(preferences);
        } catch (JacksonException e) {
            // Should never happen serializing our own record type - surfacing this as a
            // 500 (rather than silently dropping the write) is the correct failure mode.
            throw new IllegalStateException(
                    "Failed to serialize ai_model_preferences for tenant '" + tenantId + "'", e);
        }
    }
}
