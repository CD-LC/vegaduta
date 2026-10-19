import { apiFetch, shareInflight, toJson, toJsonQuietOn, jsonBody } from "./core";
import type { VaultSecret, VaultSecretStoreRequest, ProviderModelInfo, ModelPriceEntry, ModalityCatalog, FeatureFlagEvaluation, ProviderResponse } from "./types";

/** GET /api/providers/default-model. */
type DefaultAgentModel = {
  providerName: string | null;
  model: string | null;
  source: "PLATFORM_POOL" | "WORKING_PROVIDER" | "ACTIVE_PROVIDER" | "NONE";
  needsAttention: boolean;
  message: string | null;
};

export const providersApi = {
  // Ask-AI drafting helper - powers AskAiHelper.js, used from any
  // description/prompt field in the console. Needs at least one active LLM
  // provider (below); returns a 400 with a clear message if none exists yet.
  askAiDraft: (payload: object) =>
    apiFetch(`/api/assist/draft`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson<{ suggestion: string; [key: string]: unknown }>),

  // Tenant-facing feature-flag read (TenantFeatureFlagController) - "what's
  // my own evaluated variant for flag X, right now." Generic by key; the
  // first caller is AskAiHelper's edge-availability check (edge.ask-ai).
  evaluateFeatureFlag: (key: string) =>
    apiFetch(`/api/tenant/feature-flags/${encodeURIComponent(key)}/evaluate`).then(toJson<FeatureFlagEvaluation>),

  // Providers (Stage 2)
  listProviders: () => apiFetch(`/api/providers`).then(toJson<ProviderResponse[]>),
  // "Can this tenant run an agent at all, and on what?" - the server's own
  // routing rule (TenantLimits#usesPlatformPool: FREE, the usePlatformLlm
  // toggle, OR a live complimentary grant), not a client re-derivation of it.
  // listProviders() above is BYOK credentials only and is the WRONG signal
  // for this; see ProviderAvailabilityResponse's javadoc in core/providers.
  getProviderAvailability: (): Promise<{
    hasByokProvider: boolean;
    platformHostedAvailable: boolean;
    canCreateAgents: boolean;
  }> => apiFetch(`/api/providers/availability`).then(toJson<{ hasByokProvider: boolean; platformHostedAvailable: boolean; canCreateAgents: boolean; }>),
  // The provider + model a NEW agent should start on: one this workspace can use right now (the key whose
  // calls have been succeeding, or the pool). Replaces taking providers[0], which could be a key the
  // provider refuses. providerName is null when nothing is usable.
  getDefaultAgentModel: (): Promise<DefaultAgentModel> =>
    apiFetch(`/api/providers/default-model`).then(toJsonQuietOn<DefaultAgentModel>([403, 404])),
  activateProvider: (providerName: string, apiKey: string) =>
    apiFetch(`/api/providers`, {
      method: "POST",
      ...jsonBody({ providerName, apiKey }),
    }).then(toJson<ProviderResponse>),
  // "Give option to edit, delete the added key" - direct feedback. Marks the
  // credential INACTIVE server-side (ProviderService#deactivate) rather than
  // deleting the row - re-activating (the existing activateProvider call
  // above) is how "edit" works for every provider on this panel, since
  // ProviderService#activate already upserts on the (provider_name) unique
  // constraint.
  deactivateProvider: (providerName: string) =>
    apiFetch(`/api/providers/${providerName}`, { method: "DELETE" }),

  // Tool-calling probe of the connected Local / OpenAI-compatible server (LocalOpenAiProbeController): one canned
  // request to `model` on the tenant's own server, graded STRUCTURED / TEXT / NONE. Advisory; changes nothing.
  probeLocalOpenAi: (model: string) =>
    apiFetch(`/api/providers/local-openai/probe`, {
      method: "POST",
      ...jsonBody({ model }),
    }).then(
      toJson<{
        model: string;
        toolCallGrade: "STRUCTURED" | "TEXT" | "NONE" | null;
        serverKind: string | null;
        toolRouterMode: string | null;
        parameterBillions: number | null;
        advice: string;
      }>
    ),

  // Secrets vault (core/secrets, /api/secrets). Deliberately write-only: there
  // is no getSecret/revealSecret here because the backend has no such endpoint
  // (SecretVaultController's javadoc explains why). The UI renders `preview`
  // (last 4 chars) and `reference` - the {{secret.NAME}} string to paste into
  // an agent, tool or MCP config. Never ask this API for a value.
  listSecrets: (): Promise<VaultSecret[]> => apiFetch(`/api/secrets`).then(toJson<VaultSecret[]>),
  // Create-or-rotate: POSTing an existing (name, scope) replaces the value and
  // keeps the row's id, so every {{secret.NAME}} reference survives a rotation
  // with no edits. Always 201, never 200-vs-201, so a caller cannot use the
  // status code to probe which names already exist.
  storeSecret: (body: VaultSecretStoreRequest): Promise<VaultSecret> =>
    apiFetch(`/api/secrets`, { method: "POST", ...jsonBody(body) }).then(toJson<VaultSecret>),
  // Replaces a secret's destination allow-list (SEC-006). [] = never sent to a URL.
  updateSecretAllowedHosts: (id: string, allowedHosts: string[]): Promise<VaultSecret> =>
    apiFetch(`/api/secrets/${encodeURIComponent(id)}/allowed-hosts`, { method: "PUT", ...jsonBody({ allowedHosts }) }).then(toJson<VaultSecret>),
  // Default delete = revoke: the row, its last_used_at and its audit trail
  // survive so "what used this key, and when did we cut it off" stays
  // answerable. purge=true is the irreversible variant and is a separate,
  // more emphatic confirmation in the UI.
  revokeSecret: (id: string) =>
    apiFetch(`/api/secrets/${encodeURIComponent(id)}`, { method: "DELETE" }),
  purgeSecret: (id: string) =>
    apiFetch(`/api/secrets/${encodeURIComponent(id)}?purge=true`, { method: "DELETE" }),
  // "In model selection also, there should be a dropdown which lists all
  // available models in that provider" - backed by ProviderModelCatalogService's
  // scheduled refresh job, not a live call per keystroke.
  //
  // Enriched with per-model $/MTok pricing (POST /api/model-pricing) so the
  // picker can show a relative cost badge (Chatbase-style "roughly 20x
  // pricier" transparency, gap-radar P0 2026-09-23) - a second, small
  // request rather than changing the /models response shape, which 8
  // existing call sites depend on staying byte-for-byte additive-only (see
  // ProviderModelResponse's javadoc). Pricing is enrichment, never a reason
  // to fail the model list itself: a failed/slow pricing call silently
  // leaves inputCostPerMillionUsd/outputCostPerMillionUsd undefined and the
  // dropdown still renders normally.
  listProviderModels: (providerName: string): Promise<ProviderModelInfo[]> =>
    apiFetch(`/api/providers/${providerName}/models`)
      .then(toJson<ProviderModelInfo[]>)
      .then(async (models: ProviderModelInfo[]) => {
        if (!Array.isArray(models) || models.length === 0) return models;
        try {
          // POST, not GET: a ~500-model catalog made the query-string form a
          // ~20 KB URL that the edge rejected without CORS headers, which the
          // browser surfaced as "backend unreachable" on every Agents page
          // load (prod, 2026-09-24).
          const ids = models.map((m) => m.modelId);
          // Several pickers on one page ask for the same catalog at once: one POST between them.
          const prices = await shareInflight(`model-pricing:${ids.join(",")}`, () =>
            apiFetch(`/api/model-pricing`, {
              method: "POST",
              ...jsonBody({ modelIds: ids }),
            }).then(toJson<ModelPriceEntry[]>)
          );
          const byId = new Map(prices.map((p) => [p.modelId, p]));
          return models.map((m) => {
            const price = byId.get(m.modelId);
            return price
              ? { ...m, inputCostPerMillionUsd: price.inputPerMillionUsd, outputCostPerMillionUsd: price.outputPerMillionUsd }
              : m;
          });
        } catch {
          return models;
        }
      }),
  // "application should show what platform llm is configured" - direct
  // feedback. Tenant-facing read of the label/model the platform-hosted
  // pool is currently routing FREE-tier (or usePlatformLlm) chat to -
  // { available, label, model }; available:false when the pool's master
  // switch is off or nothing is enabled.
  platformLlm: () => apiFetch(`/api/providers/platform-llm`).then(toJson<{ available: boolean; label: string | null; model: string | null; [key: string]: unknown }>),

  // Server-generated STT/TTS/video provider catalog (ProviderController's
  // /modalities endpoint, backed by ModalityCatalogService) - replaced the
  // workbench's hardcoded VOICE_PROVIDERS/TTS_PROVIDERS/VIDEO_PROVIDERS
  // arrays and its hand-wired availability probes (per-brand credential
  // reads, sidecar health, "is OPENAI in listProviders"). Each option's
  // `available`/`unavailabilityReason`/`implemented` is resolved server-side
  // for this tenant, so the UI renders the list verbatim.
  getModalityCatalog: (): Promise<ModalityCatalog> =>
    apiFetch(`/api/providers/modalities`).then(toJson<ModalityCatalog>),

  // Idle-timeout policy (IdleLogout.tsx) - admin-configurable via the
  // Platform Settings screen (session.idle-timeout-seconds/
  // session.idle-warning-seconds), readable by any authenticated user.
  getSessionConfig: () => apiFetch(`/api/session/config`).then(toJson),

  // Sarvam AI (Sarvam Integration Build Plan) - deliberately NOT routed
  // through activateProvider/listProviders above: Sarvam's BYOK credential
  // lives in its own sarvam_credential table (SarvamController), shared with
  // Stage 3's non-chat clients (translate/STT/TTS/etc.), not the generic
  // LlmProviderCredential table every other provider card writes to - see
  // ProviderCredentialResolver#resolveActiveKey's javadoc. Same
  // "204 = nothing activated yet" shape as the channel credentials below.
  getSarvamCredential: () =>
    apiFetch(`/api/sarvam/credential/status`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateSarvamCredential: (apiKey: string) =>
    apiFetch(`/api/sarvam/credential`, {
      method: "POST",
      ...jsonBody({ apiKey }),
    }).then(toJson),
  deactivateSarvamCredential: () =>
    apiFetch(`/api/sarvam/credential`, { method: "DELETE" }),

  // ElevenLabs voice cloning (Master Roadmap 2026 item #4) - same "own BYOK
  // table, not the generic LlmProviderCredential one" shape as Sarvam above
  // (ElevenLabsController, backed by its own elevenlabs_credential table).
  // The backend has always supported this (POST/GET-status/DELETE
  // /api/elevenlabs/credential) but had no frontend on-ramp until 2026-08-03 -
  // a tenant could only activate it via curl/Postman.
  getElevenLabsCredential: () =>
    apiFetch(`/api/elevenlabs/credential/status`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateElevenLabsCredential: (apiKey: string) =>
    apiFetch(`/api/elevenlabs/credential`, {
      method: "POST",
      ...jsonBody({ apiKey }),
    }).then(toJson),
  deactivateElevenLabsCredential: () =>
    apiFetch(`/api/elevenlabs/credential`, { method: "DELETE" }),
  // Voice cloning (ElevenLabsVoiceController) - separate from the credential
  // above: an ACTIVE key alone isn't enough for the speak tool to resolve
  // (AgentToolProvider#resolveAudioTools also requires
  // elevenLabsVoiceService.hasAnyVoice()), so a tenant must clone at least
  // one voice here before any agent can actually speak. Deliberately NOT
  // routed through jsonBody() - this is a multipart/form-data POST (name +
  // an audio file), and jsonBody() would stamp `Content-Type:
  // application/json` over the FormData body, which both breaks the
  // multipart boundary the browser needs to set itself and mismatches what
  // Spring's @RequestParam("file") MultipartFile binding expects. apiFetch
  // only sets Authorization on top of whatever headers/body the caller
  // passes, so leaving `headers` unset here is what lets fetch() compute the
  // correct `multipart/form-data; boundary=...` header on its own.
  listElevenLabsVoices: () => apiFetch(`/api/elevenlabs/voices`).then(toJson<unknown[]>),
  cloneElevenLabsVoice: (name: string, file: Blob) => {
    const formData = new FormData();
    formData.append("name", name);
    formData.append("file", file);
    return apiFetch(`/api/elevenlabs/voices`, {
      method: "POST",
      body: formData,
    }).then(toJson);
  },
  deleteElevenLabsVoice: (id: string) =>
    apiFetch(`/api/elevenlabs/voices/${id}`, { method: "DELETE" }),

  // E2B code execution (Master Roadmap 2026 item #14 fast-follow, #1071) -
  // same "own BYOK table, not the generic LlmProviderCredential one" shape
  // as Sarvam above (E2bCredentialController, backed by its own
  // e2b_credential table). 204 = nothing activated yet. codeExecutionTemplateId
  // is null until setE2bCodeExecutionTemplate is called - see
  // E2bCredentialService#hasCodeExecutionTemplate's javadoc for why both an
  // active credential AND a configured template are required before an agent
  // can actually use run_code.
  getE2bCredential: () =>
    apiFetch(`/api/sdlc/credential/status`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateE2bCredential: (apiKey: string) =>
    apiFetch(`/api/sdlc/credential`, {
      method: "POST",
      ...jsonBody({ apiKey }),
    }).then(toJson),
  deactivateE2bCredential: () =>
    apiFetch(`/api/sdlc/credential`, { method: "DELETE" }),
  setE2bCodeExecutionTemplate: (templateId: string) =>
    apiFetch(`/api/sdlc/credential/code-execution-template`, {
      method: "PUT",
      ...jsonBody({ templateId }),
    }).then(toJson),

  // E2B sandbox lifecycle (E2bSandboxController, /api/sdlc/sandbox) - lets a
  // tenant admin exercise the sandbox directly (create/run/kill) rather than
  // only being able to save credentials and hope they work. Used by the
  // Providers page's "Test connection" affordance: create a throwaway
  // sandbox from the configured code-execution template, run a one-line
  // smoke-test snippet, then always kill it regardless of outcome so
  // nothing is left running against the tenant's own E2B quota.
  createSandbox: (templateId: string, timeoutSeconds: number, envVars: unknown, metadata: unknown) =>
    apiFetch(`/api/sdlc/sandbox`, {
      method: "POST",
      ...jsonBody({ templateId, timeoutSeconds, envVars, metadata }),
    }).then(toJson),
  listSandboxes: () => apiFetch(`/api/sdlc/sandbox`).then(toJson),
  killSandbox: (sandboxId: string) =>
    apiFetch(`/api/sdlc/sandbox/${sandboxId}`, { method: "DELETE" }),
  runSandboxCode: (code: string, language: string, timeoutSeconds: number) =>
    apiFetch(`/api/sdlc/sandbox/run-code`, {
      method: "POST",
      ...jsonBody({ code, language, timeoutSeconds }),
    }).then(toJson<{ timedOut: boolean; exitCode: number | null; stdout: string | null; stderr: string | null; [key: string]: unknown }>),

  // OCR provider abstraction (tenant-configurable cloud OCR for image
  // ingestion) - BYOK, same 204-means-unconfigured shape as Sarvam above.
  // Unconfigured is the expected working default (falls back to the
  // sidecar's built-in Tesseract), not an error state.
  getOcrProviderStatus: () =>
    apiFetch(`/api/knowledge/ocr-provider/status`).then(toJson<{ provider: string | null; [key: string]: unknown }>),
  activateOcrProvider: (provider: string, apiKey: string) =>
    apiFetch(`/api/knowledge/ocr-provider`, {
      method: "POST",
      ...jsonBody({ provider, apiKey }),
    }),
  deactivateOcrProvider: () =>
    apiFetch(`/api/knowledge/ocr-provider`, { method: "DELETE" }),
};
