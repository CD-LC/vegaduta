# Generic Provider & Capability Registry — Design & Roadmap

**Date:** 2026-08-09
**Trigger:** ElevenLabs/Sarvam "Needs setup" badge bug (fixed same day, see §0) surfaced a deeper
question from the maintainer: *"if we onboard a new provider, how does it get wired in as plug-and-play?
Are we designing this in the right way?"* This doc answers that directly — audits the current
pattern against real code, and proposes a target design plus a phased migration. It does not
implement the target design; it is the roadmap artifact the migration should be scoped against.

---

## 0. The bug that started this (already fixed)

`web/app/components/agents/TranscriptionProviderOptions.tsx`'s `getNeedsSetupBadge` gated the
"Needs setup" pill on a **static** `requiresConfig: true` flag on the provider definition, not on
the live credential status — so it never turned off once a tenant actually added an ElevenLabs or
Sarvam key, sitting permanently next to `getStatusBadge`'s correct green checkmark for the same
credential. Fixed 2026-08-09 to gate on `providerStatus[provider.id] === "unavailable"` instead,
mirroring the status badge's own live check. One file, isolated, no schema change.

That bug was a symptom. The cause is what the rest of this document is about.

---

## 1. Current state — three parallel credential patterns, verified in code

Every "provider credential" in this codebase is conceptually the same thing: a tenant's encrypted
secret for an external service, with a status and an activation flow. Today it is implemented
**three different ways**:

### 1.1 The generic pattern (works well, 13 providers)

`core/providers/.../LlmProviderCredential.java` — one table, one row shape, distinguished by a
`provider_name` enum column (`ProviderName`: `ANTHROPIC, OPENAI, AZURE_OPENAI, GOOGLE_VERTEX,
AWS_BEDROCK, OLLAMA, OPENROUTER, GROK, GEMINI, MISTRAL, DEEPSEEK, GROQ` — 12 of the 13, `SARVAM`
is the enum value but not the table, see below):

```java
@Table(name = "llm_provider_credential", uniqueConstraints = @UniqueConstraint(columnNames = "provider_name"))
class LlmProviderCredential {
    UUID id;
    ProviderName providerName;   // the discriminator
    ProviderStatus status;
    String encryptedApiKey;       // ciphertext, AES-256-GCM
    Instant createdAt, updatedAt, lastValidatedAt;
}
```

Even genuinely different credential *shapes* were folded into this single table rather than
forking it: Vertex AI's project/location/service-account blob is single-column-encoded into the
same `encrypted_api_key` field (see `VertexCredential`'s javadoc), and Azure/Bedrock/Ollama each
reuse the same row shape with provider-specific parsing on read. **This proves the generic model
already scales to multi-field, multi-shape credentials** — it was a deliberate, working design
choice for 12 of 13 LLM providers.

Onboarding a *new LLM chat provider* into this pattern is genuinely close to plug-and-play: add an
enum value, a validator, a client factory, register it — see `TextToSpeechProviderFactory`'s own
javadoc making the same claim for the TTS modality layer specifically (§1.3 below).

### 1.2 The special-cased pattern (ElevenLabs, Sarvam, E2B — 3 providers, own everything)

`ProvidersPanel.tsx` literally names this: `SARVAM_SPECIAL_CASED`, `E2B_SPECIAL_CASED`,
`ELEVENLABS_SPECIAL_CASED`. Each gets its own, nearly-identical, hand-written entity:

```java
@Table(name = "elevenlabs_credential")   // ElevenLabsCredential.java
class ElevenLabsCredential { UUID id; String encryptedApiKey; ElevenLabsCredentialStatus status; Instant createdAt, updatedAt; }

// SarvamCredential.java — same shape, different table, different status enum
```

Each also gets its own repository, service, validator, and REST controller — 5+ hand-written files
per provider, duplicating what `LlmProviderCredential` already does generically. Sarvam's reason
for existing outside the generic table (`ProviderName`'s own javadoc) is real: its key is needed
by non-chat clients (translate/STT/TTS) that the generic table's chat-oriented resolution path
doesn't reach. **But that is a resolution-path problem, not a storage problem** — the row shape
itself didn't need to fork.

### 1.3 The platform-admin fallback pattern (a fourth, orthogonal system)

`PlatformToolCredential`/`PlatformToolKey` (`ELEVENLABS`, `E2B`) — the *platform's own* paid key,
config'd in `web-admin/app/tool-credentials/page.tsx`, used as a capped fallback when a tenant has
no BYOK key of their own (`PlatformElevenLabsSpeakBackend`, `PlatformSarvamSpeakBackend`). This is
a legitimately different concept (operator credential vs. tenant credential) and should likely
**stay** a separate table even after the design below ships — noted here for completeness, not as
something to fold in.

### 1.4 Tool-resolution is a hand-written priority chain, not a registry

`AgentToolProvider.resolveAudioTools()` (`core/agents/.../tools/AgentToolProvider.java:1514-1589`)
picks which `SpeakBackend` an agent gets via four sequential, hand-written `if (backend == null) {
... }` blocks, in a fixed priority order: BYOK ElevenLabs → BYOK Sarvam → platform ElevenLabs →
platform Sarvam. `SpeakBackend` itself is a clean interface with four implementations — the
*strategy* pattern is right. What's not generic is the *selection*: adding a fifth backend (a new
TTS provider) means inserting a fifth hand-written `if` block, in the right position in the
priority order, by hand, in this one method — not registering a new entry in a data-driven list.

### 1.5 The frontend mirrors the same problem

`TextToSpeechProviderSelector.tsx` / `VoiceProviderSelector.tsx` hold **hardcoded arrays** of
provider option objects (id, name, description, `isPremium`, `requiresConfig`). A new provider
means editing this array by hand, in each feature file that offers a provider picker, and manually
getting `requiresConfig` right (the exact flag that caused the bug in §0). `AgentWorkbench.tsx`
separately hand-writes a `useEffect` + API call per credential type to populate `providerStatus`
(lines ~291-311) — another per-provider, copy-pasted block.

### 1.6 Net picture

Onboarding one new capability today touches **8-9 places across 3 Java modules and 2 Next.js
apps**: a new credential entity+repo+service+controller (following the ElevenLabs/Sarvam template
if it doesn't fit the generic table), a modality-bridge class + enum entry (if it's TTS/STT/image/
video), a new `if` block in `AgentToolProvider`, a hardcoded array entry per UI feature, a
hand-written `providerStatus` fetch in `AgentWorkbench.tsx`, a special-cased JSX block in
`ProvidersPanel.tsx`, and migrations in both schema trees. The credential-storage and tool-wiring
layers are the parts that aren't generic; the LLM-chat-provider and TTS-modality-selection layers
already prove the generic pattern works when it's used.

---

## 2. Design goals for the target state

1. **One credential storage shape**, covering every provider (LLM, TTS, STT, image, video, future
   capabilities not yet imagined) — extending the already-proven `LlmProviderCredential`
   generic-table pattern rather than inventing a new one.
2. **One resolution path** per capability (e.g. "give me something that can speak") that reads a
   priority-ordered registry, not a hand-written `if` chain — so adding backend #5 is a data
   registration, not a method edit.
3. **One frontend contract**: a single `GET /api/providers?capability=tts` (or similar) shape that
   returns everything the UI needs to render a picker (name, status, isPremium, second-step
   requirement + reason) — so `TextToSpeechProviderSelector`/`VoiceProviderSelector`/future
   pickers render from data instead of hardcoded arrays, and a "needs setup" bug like §0 becomes
   structurally impossible (there's only one status field to read, not two that can disagree).
4. **Backward compatible during migration** — existing tenant data (ElevenLabs/Sarvam/E2B
   credentials, active agents using them) must not require a forced re-auth or lose the platform
   fallback behavior.
5. **Don't break what's already generic.** The LLM-chat-provider fan-out (§1.1) and the
   TTS-modality-selection layer (`TextToSpeechProviderFactory`/`TextToSpeechProviderConfiguration`)
   are good patterns already — this is an extension of that shape outward, not a replacement of it.

## 3. Target architecture

### 3.1 Unified credential table

```
provider_credential
  id                  uuid pk
  provider_key        varchar(32)   -- 'ELEVENLABS' | 'SARVAM' | 'ANTHROPIC' | ... (superset enum)
  capabilities         varchar[]     -- e.g. {'CHAT'} or {'TTS','STT','TRANSLATE','TRANSLITERATE','LANGUAGE_ID'}
  encrypted_secret     text          -- ciphertext; single-column-encoded blob for multi-field creds
                                       -- (same technique VertexCredential already uses)
  status               varchar(16)
  extra_config         jsonb null    -- e.g. ElevenLabs' selected voice_id — replaces PlatformToolKey's
                                       -- ad hoc "extraConfig" string with a typed column
  created_at, updated_at, last_validated_at
  unique (provider_key)              -- one active credential per provider per tenant schema
```

This is `LlmProviderCredential` (§1.1) plus a `capabilities` array and a typed `extra_config`
column — not a new design, an extension of the pattern that already covers 12 providers. Sarvam's
multi-capability need (§1.2) is solved by `capabilities` instead of a bespoke table. ElevenLabs'
voice-id second-step requirement (§1.4) is solved by `extra_config` instead of living only in the
Java-side `PlatformToolCredential.extraConfig` equivalent for tenant BYOK credentials, which
doesn't exist today (that's part of why voice-cloning's "second step" isn't visible anywhere in
the generic status model).

### 3.2 A real capability registry (backend)

Introduce one interface every capability provider implements and self-registers as a Spring bean:

```java
interface CapabilityProvider<TInput, TOutput> {
    ProviderKey key();
    Set<Capability> capabilities();          // {TTS}, {TTS, STT, TRANSLATE, ...}
    int priority();                          // lower = tried first, replaces the hand-ordered if-chain
    boolean isReady(TenantContext ctx);       // replaces isActive() && hasAnyVoice() etc, one method
    TOutput invoke(TInput input, TenantContext ctx);
}
```

A `CapabilityRegistry` (autowires `List<CapabilityProvider<?,?>>`, Spring collects every bean that
implements the interface — no manual list to maintain) answers "give me the best-priority ready
provider for capability X" generically. `AgentToolProvider.resolveAudioTools()` (§1.4) becomes:

```java
Optional<CapabilityProvider<...>> backend = capabilityRegistry.resolve(Capability.TTS, tenantCtx);
```

...replacing all four hand-written `if` blocks. Adding provider #5 means writing one new
`CapabilityProvider` bean and giving it a priority — no edit to `AgentToolProvider` at all.

### 3.3 One generic status/list endpoint (backend → frontend contract)

```
GET /api/providers?capability=tts
[
  { "providerKey": "ELEVENLABS", "name": "ElevenLabs", "status": "active" | "not_configured" | "error",
    "isPremium": true, "secondStep": { "required": true, "satisfied": false, "reason": "Clone a voice before agents can speak with it" } },
  { "providerKey": "SARVAM", ... }
]
```

One `status` field, one optional `secondStep` object — no more two badges (`getStatusBadge` +
`getNeedsSetupBadge`) that can silently disagree, because there's only one signal to render.

### 3.4 Generic frontend rendering

`TranscriptionProviderOptions.tsx` already proves this direction works — it's the one place in the
frontend that *is* shared/generic today (§0's bug lived in the shared component, not a per-feature
copy). Extend that: replace the hardcoded `PROVIDERS` arrays in
`TextToSpeechProviderSelector.tsx`/`VoiceProviderSelector.tsx` with a fetch from §3.3's endpoint,
so a new provider needs zero frontend array edits — it appears automatically once its
`CapabilityProvider` bean exists and its credential is `ACTIVE`.

## 4. What does *not* change

- `PlatformToolCredential` (§1.3) stays separate — operator-paid fallback credentials are a
  different concept from tenant BYOK credentials and conflating them would be the wrong kind of
  "generic."
- The `SpeakBackend` strategy interface (§1.4) is already the right shape — §3.2 generalizes its
  *selection*, not its execution.
- `TextToSpeechProviderFactory`/`TextToSpeechProviderConfiguration`'s existing 3-touch-point
  pattern for registering a new *implementation* of an existing modality is good and can stay, or
  be folded into `CapabilityProvider` later — not urgent.

## 5. Migration plan (phased, backward compatible)

**Phase 1 — Registry scaffolding, no data migration (new providers only).**
Build `CapabilityRegistry` + `CapabilityProvider` interface + the unified `provider_credential`
table (additive migration, existing tables untouched). Wire `resolveAudioTools` to consult the
registry first, fall through to the existing four `if` blocks second. Any *new* TTS/STT/etc.
provider added from this point on goes through the registry; ElevenLabs/Sarvam/E2B keep working
exactly as today, untouched.

**Phase 2 — Backfill + dual-read (no tenant-visible change).**
Write `ElevenLabsCredential`/`SarvamCredential` rows into `provider_credential` on every
write (dual-write), backfill existing rows in a migration. Switch reads to prefer
`provider_credential`, falling back to the legacy table if absent (covers the backfill window).
Zero tenant-visible behavior change; this phase is entirely internal.

**Phase 3 — Cut over ElevenLabs, Sarvam, E2B to the registry.**
Reimplement each as a `CapabilityProvider` bean reading from `provider_credential` only. Delete
the legacy `if` blocks in `AgentToolProvider`. Delete the per-provider REST controllers, replaced
by the generic credential CRUD endpoints already implied by §3.3's shape.

**Phase 4 — Frontend cutover.**
Replace hardcoded provider arrays with the `GET /api/providers?capability=X` fetch across
`TextToSpeechProviderSelector`, `VoiceProviderSelector`, `ProvidersPanel`'s special-cased JSX
blocks, and `AgentWorkbench`'s per-provider `providerStatus` `useEffect`s.

**Phase 5 — Drop legacy tables.**
Once Phase 3/4 are stable in production for a full billing cycle (so any edge-case tenant on an
old code path has had time to surface), drop `elevenlabs_credential`, `sarvam_credential`, and
their dedicated controllers/services/repositories entirely.

Each phase is independently shippable and reversible; nothing requires a maintenance window or a
forced tenant re-auth.

## 6. Effort/risk, rough order of magnitude

| Phase | Risk | Rough size |
|---|---|---|
| 1 — Registry scaffolding | Low (additive only) | Medium — new interface, new table, new registry service |
| 2 — Backfill + dual-read | Low-medium (data migration, but reversible) | Small-medium |
| 3 — Cut over 3 providers | Medium (touches live `speak` tool path) | Medium |
| 4 — Frontend cutover | Low (UI-only, generic endpoint already exists from Phase 1) | Small-medium |
| 5 — Drop legacy | Low (cleanup) | Small |

**Recommendation:** Phase 1 is worth doing on its own even before deciding on the rest — it means
the *next* new provider (whatever it is) is the first one onboarded the right way, without
touching or destabilizing ElevenLabs/Sarvam/E2B, which keep working unchanged until Phase 3.
