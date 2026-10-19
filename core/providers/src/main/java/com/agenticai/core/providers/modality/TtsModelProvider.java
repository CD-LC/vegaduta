package com.agenticai.core.providers.modality;

/**
 * Text-to-speech (TTS) provider options for agents - the output-side twin of
 * {@link VoiceModelProvider} (which selects the input-side, speech-to-text
 * provider). Each represents a configurable TTS backend an agent's voice
 * replies can be synthesized through.
 *
 * <p>Unlike {@link VoiceModelProvider} (where {@code ELEVENLABS} was reserved
 * for a future stage), TTS launches with BOTH providers implemented, because
 * {@code VoiceCallController} already spoke to both before this enum existed
 * - {@link #ELEVENLABS} was always tried first, {@link #SARVAM} was always
 * the fallback. This enum makes that existing, working chain explicitly
 * selectable per agent instead of hardcoded and invisible.
 *
 * <ul>
 *   <li>{@link #ELEVENLABS} - tenant BYOK (needs an active ElevenLabs
 *       credential, see {@code ElevenLabsCredentialService#isActive}).
 *       Uses the tenant's most recently cloned voice if one exists, else
 *       the public "Rachel" voice.</li>
 *   <li>{@link #SARVAM} - tenant BYOK (needs an active {@code
 *       SarvamCredential}, same credential {@link VoiceModelProvider#SARVAM}
 *       already checks for STT - one Sarvam key covers both directions).</li>
 * </ul>
 *
 * <p><b>Stage 2 (2026-08-09) - see {@code TextToSpeechProviderFactory}'s
 * javadoc for what selecting a provider here actually changes at runtime</b>:
 * the underlying ElevenLabs-then-Sarvam-then-text-only fallback chain in
 * {@code VoiceCallController#synthesizeSpeech} is untouched for any agent
 * that leaves this unset (the default, and every agent that existed before
 * this migration) - selecting a provider here only changes ordering/skips
 * the chain for that one agent, it does not replace the chain's quota
 * handling or persistence logic.
 */
public enum TtsModelProvider {
    /**
     * ElevenLabs text-to-speech. Tenant must have an active ElevenLabs BYOK
     * credential. Supports voice cloning (see {@code ElevenLabsVoiceService})
     * - if the tenant has cloned a voice, that voice is used automatically.
     */
    ELEVENLABS("elevenlabs", "eleven_multilingual_v2", "ElevenLabs",
        "High-quality voices, supports your own cloned voice", true),

    /**
     * Sarvam text-to-speech. Tenant must have an active {@code
     * SarvamCredential}. Default model matches {@code
     * SarvamTextToSpeechClient#DEFAULT_MODEL} ({@code bulbul:v3}). Currently
     * synthesizes in {@code en-IN}; per-agent target-language selection is
     * not yet wired (same limitation the existing hardcoded fallback chain
     * has - see {@code VoiceCallController#synthesizeSarvamOnly}).
     */
    SARVAM("sarvam", "bulbul:v3", "Sarvam TTS", "Indic-language voices", true);

    private final String providerId;
    private final String defaultModel;
    // UI-facing text served by GET /api/providers/modalities - see
    // VoiceModelProvider's displayName/description for why each constant
    // carries its own.
    private final String displayName;
    private final String description;
    /** See {@link VoiceModelProvider}'s {@code premium} field. */
    private final boolean premium;

    TtsModelProvider(String providerId, String defaultModel, String displayName, String description, boolean premium) {
        this.providerId = providerId;
        this.defaultModel = defaultModel;
        this.displayName = displayName;
        this.description = description;
        this.premium = premium;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public boolean isPremium() {
        return premium;
    }
}
