package com.agenticai.core.sarvam;

/**
 * Which Sarvam AI capability a {@link SarvamCallLog} row records - one value
 * per client class Stage 3 builds ({@link SarvamTranslateClient}, {@link
 * SarvamSpeechToTextClient}, {@link SarvamTextToSpeechClient}, {@link
 * SarvamTransliterationClient}, {@link SarvamLanguageIdClient}). Kept
 * separate from {@code com.agenticai.core.providers.ProviderName} - these
 * are not LLM chat providers, they're single-purpose REST endpoints, and
 * mixing them into that enum would misrepresent them as chat-completion
 * providers a {@code ChatClient} could be built for.
 */
public enum SarvamCallCapability {
    TRANSLATE,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
    TRANSLITERATION,
    LANGUAGE_ID
}
