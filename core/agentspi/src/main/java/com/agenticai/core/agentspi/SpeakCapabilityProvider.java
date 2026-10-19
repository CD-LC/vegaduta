package com.agenticai.core.agentspi;

/**
 * A {@link CapabilityProvider} that produces a {@link SpeakBackend}. A thin, capability-specific
 * marker so Spring can collect exactly the text-to-speech candidates (via {@code
 * List<SpeakCapabilityProvider>}) without them being mixed into some other capability's list - see
 * {@link CapabilityProvider}'s javadoc for why this indirection exists at all.
 *
 * <p>Implementations, by priority: {@code elevenlabs.agenttools.ElevenLabsSpeakCapabilityProvider}
 * (10, tenant's cloned voice), {@code sarvam.agenttools.SarvamSpeakCapabilityProvider} (20,
 * tenant's Sarvam key), {@code elevenlabs.agenttools.PlatformElevenLabsSpeakCapabilityProvider}
 * (30) and {@code sarvam.agenttools.PlatformSarvamSpeakCapabilityProvider} (40), the quota-capped
 * platform fallbacks.
 */
public interface SpeakCapabilityProvider extends CapabilityProvider<SpeakBackend> {}
