package com.agenticai.core.providers;

import jakarta.validation.constraints.Size;
/** @param extraConfig tool-specific optional field - ElevenLabs voice_id, E2B code-execution template id. Blank/null is fine for tools that don't need one yet. */
public record PlatformToolCredentialActivationRequest(@Size(max = 4096) String apiKey, String extraConfig) {
}
