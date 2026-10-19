// Prod re-verification 2026-10-05, R7: "LLM PROVIDERS 0 of 9 supported active" on a workspace that was answering.

import { describe, expect, it, vi } from "vitest";

vi.mock("./api", () => ({ api: {} }));
vi.mock("../components/AuthProvider", () => ({ useAuth: () => ({ hasPermission: () => true }) }));
vi.mock("./i18n", () => ({ useTranslations: () => (key: string) => key }));

import { PROVIDERS } from "../components/ProvidersPanel";
import { LLM_PROVIDER_NAMES, countActiveLlmKeys, isLlmProviderName, llmProvidersTileSub } from "./llmProviders";

describe("the number of supported chat providers", () => {
  it("is the providers page's own list minus the cards that are not chat models, not a copied constant", () => {
    const fromPage = PROVIDERS.filter((p) => isLlmProviderName(p)).sort();
    expect([...LLM_PROVIDER_NAMES].sort()).toEqual(fromPage);
    expect(LLM_PROVIDER_NAMES.length).toBeGreaterThan(9);
  });
});

describe("countActiveLlmKeys - the workspace's own active provider keys", () => {
  it("counts each active provider once, ignores inactive rows and the non-chat cards", () => {
    expect(
      countActiveLlmKeys([
        { providerName: "OPENROUTER", status: "ACTIVE" },
        { providerName: "OPENROUTER", status: "ACTIVE" },
        { providerName: "GOOGLE_VERTEX", status: "INACTIVE" },
        { providerName: "ELEVENLABS", status: "ACTIVE" },
        { providerName: "ANTHROPIC", status: "ACTIVE" },
        { status: "ACTIVE" },
      ]),
    ).toBe(2);
  });

  it("is 0 for a missing or odd payload", () => {
    expect(countActiveLlmKeys(null)).toBe(0);
    expect(countActiveLlmKeys(undefined)).toBe(0);
    expect(countActiveLlmKeys([])).toBe(0);
  });
});

describe("llmProvidersTileSub - what the tile says under the count", () => {
  it("says chat runs on the shared platform model when it does (0 own keys is then expected, not a fault)", () => {
    expect(llmProvidersTileSub(true).key).toBe("llmProvidersSubPlatform");
  });

  it("keeps the 'of N supported active' wording otherwise, with the real N", () => {
    for (const on of [false, null, undefined]) {
      const sub = llmProvidersTileSub(on);
      expect(sub.key).toBe("llmProvidersSub");
      expect(sub.values.count).toBe(LLM_PROVIDER_NAMES.length);
    }
  });
});
