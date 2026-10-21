# Multi-Provider Voice/Video/Text Architecture Foundation

**Date**: 2026-07-28 (Stage 1: STT); updated 2026-08-09 (Stage 2: TTS, partial)  
**Scope**: Agent modality provider selection (Stage 1: voice input/STT, with stubs for video/text; Stage 2: voice output/TTS, partially implemented - see that section for exact scope)  
**Status**: Stage 1 (STT) complete. Stage 2 (TTS) PARTIALLY implemented: the pluggable provider layer, per-agent selection, validation, and web UI are done and tested; the live-call streaming path (`VoiceCallController#turn`) does not yet consult an agent's TTS selection - see "Stage 2" below for the precise boundary and what remains for whoever picks this up next.

## Overview

This document describes the architectural foundation enabling agents to explicitly select which provider handles each modality (voice/video/text), rather than using hardcoded fallback chains or silently inheriting from tenant configuration.

### Key Design Principles

1. **Pluggable providers**: New providers can be added without changing core logic. Each provider implements a simple interface and self-registers.
2. **Explicit selection**: Agents have visible, editable provider choices per modality (no silent inheritance).
3. **Availability validation**: Selected providers are validated at request time. Unavailable providers are rejected with clear error messages.
4. **Graceful degradation**: Null provider values preserve legacy behavior (default fallback chains).
5. **Premium feature gating**: Provider selection is restricted to PREMIUM+ tenants (FREE tier always uses platform defaults).

## Architecture Components

### 1. Provider Enums (`core/providers/modality/`)

#### VoiceModelProvider
Enumeration of speech-to-text (STT) providers an agent can route audio through.

```
SIDECAR_WHISPER   → Local Whisper (requires sidecar + knowledge profile)
SARVAM            → Sarvam AI (requires tenant SarvamCredential)
ELEVENLABS        → ElevenLabs STT (reserved for future, requires BYOK credential)
VIDEO_PROVIDER... → Reserved for future video-to-text providers
```

**Key methods:**
- `getProviderId()` - human-readable provider name ("sidecar-whisper", "sarvam", etc.)
- `getDefaultModel()` - provider's default model if not specified by agent
- `isBeta()` - flags experimental providers

#### VideoModelProvider & TextModelProvider
Stubs for future expansion. Defined now so schema is in place.

### 2. SpeechToTextProvider Interface

Abstraction all voice providers implement:

```java
interface SpeechToTextProvider {
    String transcribe(byte[] audioBytes, String filename) throws SpeechToTextException;
    String transcribe(byte[] audioBytes, String filename, String model, String languageCode) throws SpeechToTextException;
    boolean isAvailable();
    String getUnavailabilityReason();
    VoiceModelProvider getProvider();
}
```

**Implementation contract:**
- All methods must be thread-safe
- `transcribe()` throws typed exceptions (see exception hierarchy below)
- `isAvailable()` returns false if provider can't currently transcribe
- Providers are stateless (no per-call configuration stored)

### 3. Provider Implementations

#### SidecarWhisperProvider (`core/providers/modality/SidecarWhisperProvider.java`)
Routes voice through the sidecar's local Whisper instance.

- **Availability check**: `Optional<SidecarClient>` is present AND sidecar is responsive
- **Unavailable reason**: "Knowledge profile inactive" or "Sidecar service down"
- **Error handling**: Distinguishes between audio quality issues (SpeechToTextTranscriptionException) and provider failures (SpeechToTextProviderException)

#### SarvamSpeechToTextProvider (`core/sarvam/.../sarvam/SarvamSpeechToTextProvider.java`)
Routes voice through Sarvam AI's speech-to-text API.

- **Availability check**: Tenant has an active SarvamCredential
- **Unavailable reason**: "Sarvam API key not configured"
- **Model support**: Accepts model names like "saaras:v3" (default) or "saarika:v2.5"
- **Language support**: Auto-detects by default, or accepts BCP-47 codes ("hi-IN", "en-US", etc.)
- **Extra modes**: Supports "transcribe", "translate", "verbatim", "translit", "codemix" via the model parameter

### 4. Exception Hierarchy

All providers throw subclasses of `SpeechToTextException`:

```
SpeechToTextException (base)
├── SpeechToTextTranscriptionException
│   └── Audio quality issue (empty audio, unsupported format, no speech detected)
│   └── HTTP 400 response, user should retry with clearer audio
└── SpeechToTextProviderException
    └── Provider unavailable or error (API unreachable, rate limited, invalid credentials)
    └── HTTP 503 or fallback to another provider
    └── Has isRetryable() flag (true = try next provider, false = permanent failure)
```

### 5. SpeechToTextProviderFactory (`core/providers/modality/SpeechToTextProviderFactory.java`)

Registry and selector for all available providers.

**Key responsibilities:**
- `register(SpeechToTextProvider provider)` - plugins self-register at startup
- `getProvider(VoiceModelProvider enum)` - returns Optional of implementation
- `isAvailable(VoiceModelProvider enum)` - checks if provider is registered AND available
- `getUnavailabilityReason(enum)` - human-readable explanation why provider is unavailable
- `getAvailableProviders()` - returns all currently usable providers (for UI dropdowns)

**Initialization** via `SpeechToTextProviderConfiguration`. Since ADR-0011 phase 2 it discovers every
`SpeechToTextProvider` bean by type, so a vendor adapter in its own module (`core/sarvam`) registers itself and
`providers` has no vendor dependency:
```java
@Configuration
public class SpeechToTextProviderConfiguration {
    public SpeechToTextProviderConfiguration(
        SpeechToTextProviderFactory factory,
        List<SpeechToTextProvider> providers
    ) {
        ModalityProviderRegistration.registerAll(
            "SpeechToTextProvider", providers, SpeechToTextProvider::getProvider, factory::register);
    }
}
```

### 6. VoiceProviderValidator (`core/providers/modality/VoiceProviderValidator.java`)

Validates that a voice model provider/model selection is legal for this deployment.

**Invariants enforced:**
- Provider and model must be set together (both null or both non-null)
- If provider is set, model must be non-blank
- Selected provider must be available on this deployment
- Validation happens at agent creation/update time, before persistence

**Used by:**
- `AgentService.updateVoiceModel()` - rejects invalid selections
- Web UI validation (future) - client-side feedback before submission

### 7. Agent Entity Extensions

**New columns** (migration `V148__agent_voice_model_provider.sql`):

```sql
ALTER TABLE agent
    ADD COLUMN voice_model_provider VARCHAR(32),
    ADD COLUMN voice_model VARCHAR(128),
    ADD COLUMN video_model_provider VARCHAR(32),
    ADD COLUMN video_model VARCHAR(128),
    ADD COLUMN text_model_provider VARCHAR(32);
```

**New fields** on `Agent.java`:

```java
@Enumerated(EnumType.STRING)
@Column(name = "voice_model_provider", length = 32)
private VoiceModelProvider voiceModelProvider;

@Column(name = "voice_model", length = 128)
private String voiceModel;

// video_model_provider, video_model, text_model_provider: placeholders for Stage 2+
```

**New methods**:
- `getVoiceModelProvider()` / `getVoiceModel()` - getters (nullable)
- `setVoiceModelProvider(provider, model)` - atomic pair setter
- `clearVoiceModelProvider()` - reverts to default fallback chain

**Migration strategy**: Both columns are nullable on initial creation. Pre-existing agents have `NULL` and use the legacy fallback chain. New agents created via web UI have both set explicitly. Agents updated via PATCH endpoint have explicit selection applied.

### 8. Service Layer Integration

#### AgentService.updateVoiceModel()

```java
@Transactional
public AgentResponse updateVoiceModel(UUID agentId, AgentVoiceModelUpdateRequest request) {
    // 1. Load agent (404 if not found or not visible to caller)
    // 2. Check edit-lock policy (Premium-only feature)
    // 3. Validate provider selection via VoiceProviderValidator
    // 4. Persist agent.setVoiceModelProvider(provider, model)
    // 5. Return updated AgentResponse
}
```

**Permissions & gates:**
- FREE tier: Always throws `TenantLimitExceededException`. FREE tenants use hardcoded fallback chain.
- PREMIUM/UNLIMITED: Can set explicit providers if available on deployment
- Edit-lock: Same gate as `updateModel()` (24h grace period for new agents, then locked until upgrade)

### 9. Controller Endpoint

**Endpoint**: `PATCH /api/agents/{agentId}/voice-model`

**Request body** (`AgentVoiceModelUpdateRequest`):
```json
{
  "voiceModelProvider": "SIDECAR_WHISPER",  // or "SARVAM", null to clear
  "voiceModel": "openai/whisper-1"           // or "saaras:v3", null to clear
}
```

**Response**: Full `AgentResponse` with updated voice model fields visible.

**Error cases**:
- `404` - agent not found
- `402` - FREE tier (provider selection is premium feature)
- `400` - provider/model validation failed (unavailable or mismatched)
- `403` - edit-lock active (24h grace period expired, needs upgrade)

### 10. Voice Endpoint Integration

**Current behavior** (VoiceCallController.turn()):
1. Try sidecar Whisper (if available)
2. Fall back to Sarvam
3. Fail with user-friendly error if both unavailable

**Future behavior** (after provider integration):
1. If agent has explicit voiceModelProvider set, use that
2. If null, use legacy fallback chain (Sidecar → Sarvam)
3. Log which provider was used for each transcription
4. Catch provider-specific exceptions and retry if retryable

**Integration point**:
```java
VoiceModelProvider selectedProvider = agent.getVoiceModelProvider();
if (selectedProvider != null) {
    // Route through agent's selected provider
    SpeechToTextProvider provider = providerFactory.getProvider(selectedProvider)
        .orElseThrow(() -> new VoiceCallException("Provider not available"));
    userText = provider.transcribe(audioBytes, filename, agent.getVoiceModel(), null);
} else {
    // Use legacy fallback chain (backwards compatibility)
    userText = transcribeViaLegacyFallbackChain(audioBytes, filename);
}
```

## Adding a New Provider

### Step 1: Create enum value in VoiceModelProvider
```java
public enum VoiceModelProvider {
    NEW_PROVIDER("new-provider", "default-model-id")
}
```

### Step 2: Implement SpeechToTextProvider
```java
@Component
public class NewSpeechToTextProvider implements SpeechToTextProvider {
    // Implement transcribe(), isAvailable(), getUnavailabilityReason(), getProvider()
}
```

### Step 3: Register with factory
Add constructor injection to `SpeechToTextProviderConfiguration`:
```java
factory.register(newProvider);
```

### Step 4: Test availability checks
- Call `voiceProviderValidator.validate(NEW_PROVIDER, "model-id")`
- Verify `isAvailable()` returns false when provider is misconfigured
- Verify error messages are clear and actionable

## Data Flow: Agent Voice Turn

```
VoiceCallController.turn(agentId, audioBytes)
    ↓
Agent agent = agentService.get(agentId)
    ↓
if agent.voiceModelProvider != null:
    provider = providerFactory.getProvider(agent.voiceModelProvider)
    text = provider.transcribe(audioBytes, filename, agent.voiceModel, null)
else:
    text = legacyFallbackChain(audioBytes)  // Sidecar → Sarvam
    ↓
AgentChatService.chat(agentId, ..., text)
    ↓
String reply = agentChatService.streamReply(...)
    ↓
ElevenLabsClient.textToSpeech(reply)   ← still hardcoded for `turn()` (live calls) -
    ↓                                    see "Stage 2" below: only `speak()` (the
VoiceTurnResponse(userText, replyText,   single-shot "play this reply" endpoint)
                   replyAudio)           consults agent.ttsProvider today.
```

## Stage 2: Text-to-Speech (TTS) Provider Selection (2026-08-09 - Partially Implemented)

Output-side twin of Stage 1: an agent's voice REPLIES were always synthesized through a
hardcoded ElevenLabs → Sarvam → text-only-fallback chain, invisible and unselectable. This
stage makes that chain explicitly selectable per agent, following Stage 1's exact pattern -
new enum, new interface, new factory, new validator, new provider implementations, new
Agent columns, new PATCH endpoint, new web UI selector - re-read the "Architecture
Components" section above with `TtsModelProvider`/`TextToSpeechProvider`/
`TextToSpeechProviderFactory`/`TextToSpeechProviderValidator` substituted for their STT
counterparts; the shape is identical.

**What's DONE (built, compiled, tested green):**
- `core/providers/modality/TtsModelProvider.java` - enum `ELEVENLABS` | `SARVAM` (both
  implemented from day one, unlike Stage 1's ELEVENLABS-reserved-for-later - the chain
  already spoke to both providers, this just makes the existing choice explicit).
- `core/providers/modality/TextToSpeechProvider.java` + `TextToSpeechProviderFactory.java` +
  `TextToSpeechProviderValidator.java` + the exception hierarchy (`TextToSpeechException` /
  `TextToSpeechProviderException` / `TextToSpeechSynthesisException`) - direct mirrors of
  their STT equivalents.
- `ElevenLabsTextToSpeechProvider.java` / `SarvamTextToSpeechProvider.java` - real,
  synthesis-capable implementations, not stubs. **Read `TextToSpeechProvider`'s javadoc
  before adding a new caller**: ElevenLabs's quota check is internal to `ElevenLabsClient`
  (inherited automatically), Sarvam's is NOT (it lives on `VoiceCallController` as a private
  method) - `SarvamTextToSpeechProvider.synthesize()` does NOT quota-check, by design, because
  the one place that matters today (`VoiceCallController`) deliberately bypasses this
  interface for its own routing (next point).
- `agent.ttsProvider` / `agent.ttsModel` (migration V196) + `AgentService#updateTtsModel` +
  `PATCH /api/agents/{id}/tts-model` + `AgentCreateRequest`/`AgentResponse` fields - same
  Premium-feature gating, same null-means-default-chain semantics as Stage 1.
- Web UI: `TextToSpeechProviderSelector.tsx` (mirrors `VoiceProviderSelector.tsx`, reusing the
  same generic `TranscriptionProviderOptions` component - zero new generic UI code needed)
  wired into `AgentWorkbench.tsx`'s agent-settings panel, right next to the STT selector, both
  in the creation form and the existing-agent editor. ElevenLabs/Sarvam availability badges
  reuse `api.getElevenLabsCredential()`/`api.getSarvamCredential()`, the same calls
  `ProvidersPanel.tsx` already uses.
- `VoiceCallController#speak` (`POST /api/agents/{id}/speak`, the single-shot "play this
  reply" endpoint used by the text-chat UI) now passes the agent's `ttsProvider` through to a
  new `synthesizeSpeech(agentId, text, voiceId, ttsProvider)` overload. `ttsProvider ==
  SARVAM` skips ElevenLabs entirely and calls straight into the existing, already-quota-checked
  `trySarvamTts` private helper - no new quota logic was written. `ttsProvider == ELEVENLABS`
  (or unset) runs the original chain byte-for-byte unchanged. Either way a failure still falls
  through to `"text-only-fallback"`, same universal safety net as before.

**What's NOT done - the honest boundary, read this before assuming Stage 2 is complete:**
- **`VoiceCallController#turn` (the live LiveKit voice-call streaming pipeline) does NOT
  consult `agent.ttsProvider` at all.** Its per-sentence `synthesizeChunk` method still runs
  the original hardcoded ElevenLabs-then-Sarvam chain unconditionally, regardless of what an
  agent has selected. This was a deliberate scope cut, not an oversight: `synthesizeChunk` and
  its quota propagation carry extensive 2026-08-08 audit-fix comments (see that method's
  javadoc) about exactly how a `VoiceQuotaExceededException` must surface mid-stream: rewiring
  it to branch on an explicit provider risked reintroducing a bug that audit had JUST fixed,
  for a live real-time-call code path that's materially harder to test than the single-shot
  `speak()` endpoint. **Next step for whoever picks this up**: thread `agent.ttsProvider`
  into `turn()`'s call to `synthesizeChunk` the same way `speak()` now does for
  `synthesizeSpeech`, being careful to preserve the quota-propagation contract described in
  that method's javadoc (a `VoiceQuotaExceededException` must still propagate as a `Mono`
  error, not get swallowed).
- No per-agent TTS target-language selection - `SarvamTextToSpeechProvider` and the existing
  chain both hardcode `en-IN` (same limitation Stage 1 has on the STT side for Sarvam's
  `mode` parameter, never fully exposed either).
- No third TTS provider yet. Adding one: implement `TextToSpeechProvider`, add the enum value
  to `TtsModelProvider`, register in `TextToSpeechProviderConfiguration` - exactly the "Adding
  a New Provider" steps below, TTS-side.

## Web UI Integration

**Correction (2026-08-09): despite the "Placeholder" heading and "future work" framing below,
all four items were already built for STT by the time this correction was written** -
`VoiceProviderSelector.tsx`, wired into `AgentWorkbench.tsx`'s creation form and existing-agent
editor, populated from `providerFactory.getAvailableProviders()` via the `providerStatus` prop.
This doc was simply never updated after that work landed. The Stage 2 section above built the
direct TTS equivalent, `TextToSpeechProviderSelector.tsx`, reusing the exact same generic
`TranscriptionProviderOptions` component both selectors are thin wrappers over - so the four
items below are accurate as a description of what exists for BOTH modalities today, not as
outstanding future work:

1. **Agent creation form**: Voice/TTS provider dropdowns (populated by `providerFactory.getAvailableProviders()` / `TextToSpeechProviderFactory` equivalent)
2. **Agent edit page**: Toggle + dropdown to change voice/TTS provider
3. **Validation**: Real-time feedback if selected provider becomes unavailable (via the `providerStatus` prop, computed client-side from `api.getSarvamCredential()`/`api.getElevenLabsCredential()`/sidecar health)
4. **Fallback indicator**: Show "Using platform default" when voiceModelProvider/ttsProvider is null

## Backward Compatibility

- **Pre-existing agents**: Have `voice_model_provider = NULL`. Voice endpoint uses legacy fallback chain (behavior unchanged).
- **New agents via web UI**: Have explicit provider set (opt-in at creation time).
- **Updated agents via PATCH**: Receive new provider selection (can revert with `PATCH /voice-model` with null provider).
- **Feature flag not needed**: Null-check on agent.voiceModelProvider is the feature gate.

## Testing Strategy

The test classes planned here at Stage 1 (`VoiceProviderValidatorTest`, `SidecarWhisperProviderTest`,
`SarvamSpeechToTextProviderTest`, `SpeechToTextProviderFactoryTest`, `AgentServiceVoiceModelTest`,
`AgentControllerVoiceModelTest`, `VoiceCallControllerIntegrationTest`) do not exist under those names. The
coverage in the tree today (v0.1.9):

- `core/providers`: `ModalityProviderConfigurationTest` (registration by type)
- `core/sarvam`: `SarvamProviderPluginsTest`, `SarvamSpeechToTextClientTest`, `SarvamTextToSpeechClientTest`
- `core/elevenlabs`: `ElevenLabsTextToSpeechProviderTest`
- `core/voice`: `VoiceCallControllerProviderTest`, `VoiceCallControllerTest`
- `core/app`: `ProviderPluginDiscoveryTest` (vendor providers are discovered, not named)

### Manual Testing
1. Create agent, explicitly set SIDECAR_WHISPER provider
2. Call voice endpoint with audio
3. Verify transcription routed through sidecar (check logs for "Sidecar Whisper transcription successful")
4. Update agent to SARVAM
5. Call voice endpoint again, verify routed through Sarvam

## Key Files Reference

| File | Purpose |
|------|---------|
| `core/providers/modality/VoiceModelProvider.java` | Enum: SIDECAR_WHISPER, SARVAM, ELEVENLABS, etc. |
| `core/providers/modality/SpeechToTextProvider.java` | Interface all providers implement |
| `core/providers/modality/SpeechToTextException.java` | Base exception class |
| `core/providers/modality/SpeechToTextTranscriptionException.java` | Audio quality errors (HTTP 400) |
| `core/providers/modality/SpeechToTextProviderException.java` | Provider errors (HTTP 503, retryable flag) |
| `core/providers/modality/SidecarWhisperProvider.java` | Sidecar Whisper implementation |
| `core/sarvam/.../SarvamSpeechToTextProvider.java` | Sarvam implementation (vendor module since ADR-0011 phase 2) |
| `core/providers/modality/SpeechToTextProviderFactory.java` | Registry and selector |
| `core/providers/modality/VoiceProviderValidator.java` | Validates provider/model selections |
| `core/providers/modality/SpeechToTextProviderConfiguration.java` | Spring config: registers providers |
| `core/agents/.../agents/core/Agent.java` | Added: voiceModelProvider, voiceModel fields + getters/setters |
| `core/agents/dto/AgentVoiceModelUpdateRequest.java` | PATCH request DTO |
| `core/agents/.../agents/core/AgentService.java` | Added: updateVoiceModel() method |
| `core/agents/.../agents/core/AgentController.java` | Added: PATCH /{id}/voice-model endpoint |
| `core/app/src/main/resources/db/migration/tenant/V148__agent_voice_model_provider.sql` | Schema: add voice_model_provider, voice_model columns |
| `core/sarvam/SarvamCredentialAvailabilityChecker.java` | Helper: checks if Sarvam is configured |
| `web/app/components/agents/VoiceProviderSelector.tsx` | STT web UI selector (thin wrapper over TranscriptionProviderOptions) |

**Stage 2 (TTS, 2026-08-09) additions:**

| File | Purpose |
|------|---------|
| `core/providers/modality/TtsModelProvider.java` | Enum: ELEVENLABS, SARVAM |
| `core/providers/modality/TextToSpeechProvider.java` | Interface all TTS providers implement |
| `core/providers/modality/TextToSpeechException.java` | Base exception class |
| `core/providers/modality/TextToSpeechSynthesisException.java` | Input-text errors |
| `core/providers/modality/TextToSpeechProviderException.java` | Provider errors (retryable flag) |
| `core/elevenlabs/.../ElevenLabsTextToSpeechProvider.java` | ElevenLabs implementation (quota-checked via ElevenLabsClient) |
| `core/sarvam/.../SarvamTextToSpeechProvider.java` | Sarvam implementation (NOT quota-checked - see its javadoc) |
| `core/providers/modality/TextToSpeechProviderFactory.java` | Registry and selector |
| `core/providers/modality/TextToSpeechProviderValidator.java` | Validates provider/model selections |
| `core/providers/modality/TextToSpeechProviderConfiguration.java` | Spring config: registers providers |
| `core/providers/pom.xml` | Had an `elevenlabs` dependency at Stage 2; removed in ADR-0011 phase 2 (vendor modules now depend on `providers`, not the reverse) |
| `core/agents/.../agents/core/Agent.java` | Added: ttsProvider, ttsModel fields + getters/setters/clear |
| `core/agents/dto/AgentTtsModelUpdateRequest.java` | PATCH request DTO |
| `core/agents/.../agents/core/AgentService.java` | Added: updateTtsModel() method + create()-time TTS handling |
| `core/agents/.../agents/core/AgentController.java` | Added: PATCH /{id}/tts-model endpoint |
| `core/app/src/main/resources/db/migration/tenant/V196__agent_tts_provider.sql` | Schema: add tts_provider, tts_model columns |
| `core/voice/VoiceCallController.java` | `speak()` + new 4-arg `synthesizeSpeech()` overload consult agent.ttsProvider; `turn()`/`synthesizeChunk` do NOT yet - see Stage 2 section |
| `web/app/components/agents/TextToSpeechProviderSelector.tsx` | TTS web UI selector, wired into AgentWorkbench.tsx |

## Future Expansion: Video & Text Providers

### VideoModelProvider (Stage 2+)
- Enum values: VERTEX_VIDEO, GPT4V, etc.
- Interface: `VideoToTextProvider`
- Use case: Transcribe video content (multi-frame analysis, scene detection)

### TextModelProvider (Stage 2+)
- Enum: INHERIT_FROM_PRIMARY, EXPLICIT
- Use case: Route text completions to a different LLM than the primary agent provider
- Example: Draft via fast cheap model, final synthesis via powerful model

### Refactors needed:
- Generalize `SpeechToTextProvider` → `MediaProvider<InputType, OutputType>`
- Create `VoiceVideoTextProviderService` to handle all modalities
- Update AgentResponse to show all modality selections

## Deployment Considerations

1. **Database migration** runs automatically on first boot (Flyway)
2. **Sidecar availability** checked on every startup via `SidecarWhisperProvider.isAvailable()`
3. **Sarvam configuration** checked on every voice turn (credential may be added/removed)
4. **Provider factory** initialized at Spring startup; providers are singletons
5. **No feature flags needed** - availability checks are runtime, not compile-time

## Success Metrics

- [ ] Agents can explicitly select voice provider at creation time
- [ ] Voice endpoint logs which provider was used for each transcription
- [ ] PREMIUM tenants can change voice provider; FREE tenants get clear error
- [ ] New provider implementations require only 3 files (enum value, provider class, factory registration)
- [ ] Web UI populates provider dropdown from available providers
- [ ] Fallback chain still works for backward-compatible agents (NULL provider)
- [ ] Clear error messages when selected provider is unavailable

## References

- `core/voice/VoiceCallController.java` - Current voice turn implementation
- `core/sarvam/` - Existing Sarvam integration
- `core/knowledge/SidecarClient.java` - Existing Sidecar integration
