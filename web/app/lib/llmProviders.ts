// The chat (LLM) providers a workspace can add its own key for, and how the /admin "LLM PROVIDERS" tile describes them.
//
// Prod re-verification 2026-10-05, R7: the tile read "0 of 9 supported active" on a workspace whose agents were
// answering. Two things were off, both on the web side:
//   - the "9" was a hand-copied constant that had not kept up with the providers page (13 chat providers now);
//   - the count is the workspace's OWN keys (GET /api/providers is BYOK credentials only, by design), so a workspace
//     answering on the shared platform model has 0 of them, and "0 active" read as "nothing works".
// The count itself is correct for what it counts (active own keys); the tile now says what powers the workspace when
// that is the shared platform model.

/** Provider cards on the providers page that are not chat models (speech, code sandbox, voice). */
const NON_LLM_PROVIDERS: ReadonlySet<string> = new Set(["SARVAM", "E2B", "ELEVENLABS"]);

/** Every chat provider the providers page offers. Kept honest by llmProviders.test.ts against the page's own list. */
export const LLM_PROVIDER_NAMES: readonly string[] = [
  "ANTHROPIC",
  "OPENAI",
  "OPENROUTER",
  "GROK",
  "GEMINI",
  "AZURE_OPENAI",
  "GOOGLE_VERTEX",
  "AWS_BEDROCK",
  "OLLAMA",
  "MISTRAL",
  "DEEPSEEK",
  "GROQ",
  "META",
  "LOCAL_OPENAI",
];

export function isLlmProviderName(name: string): boolean {
  return !NON_LLM_PROVIDERS.has(name);
}

/** How many of the workspace's own provider keys are active (one per provider; a row without a status is not). */
export function countActiveLlmKeys(rows: ReadonlyArray<{ providerName?: unknown; status?: unknown }> | null | undefined): number {
  if (!Array.isArray(rows)) return 0;
  const names = new Set<string>();
  for (const r of rows) {
    if (r && r.status === "ACTIVE" && typeof r.providerName === "string" && isLlmProviderName(r.providerName)) names.add(r.providerName);
  }
  return names.size;
}

import { isPersonalInstall } from "./personalInstall";

/** The i18n key + values for the tile's sub line. On a personal install the pool is the local model, not a shared one. */
export function llmProvidersTileSub(onPlatformLlm: boolean | null | undefined): { key: string; values: { count: number } } {
  return {
    key: onPlatformLlm === true ? (isPersonalInstall() ? "llmProvidersSubLocal" : "llmProvidersSubPlatform") : "llmProvidersSub",
    values: { count: LLM_PROVIDER_NAMES.length },
  };
}
