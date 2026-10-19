// Mock responses for the /api/local-models/* contract, used by the tests and by the dev UI preview
// (web/app/ui-kit/console-preview/local-model) so the wizard can be exercised without the backend.
// Shapes mirror lib/api/localModels.ts.
import type { LocalModelChoice, LocalModelProbe, LocalModelRecommendation } from "../../lib/api/localModels";
import type { LocalModelWizardApi } from "./useLocalModelWizard";

const row = (
  tag: string,
  family: string,
  downloadGb: number,
  ramGb: number,
  toolCalling: LocalModelChoice["toolCalling"],
  note: string,
  extra: Partial<LocalModelChoice> = {},
): LocalModelChoice => ({
  tag,
  family,
  downloadGb,
  ramGb,
  toolCalling,
  note,
  paramsB: 8,
  contextK: 128,
  vramGb: Math.round(downloadGb * 1.3),
  licence: "Apache-2.0",
  thinking: true,
  bestFor: "general assistant work",
  // A 16 GB machine gives a model 12 GB (ModelRecommender#budgetGb).
  fits: ramGb <= 12,
  ...extra,
});

export const recommendedFixture = row("qwen3:8b", "Qwen", 5.2, 8, "native", "Good all-round model for 16 GB machines.", {
  paramsB: 8.2,
  contextK: 40,
  vramGb: 7,
  bestFor: "coding help and tool-heavy agents",
});

export const catalogFixture: LocalModelChoice[] = [
  row("qwen3:4b", "Qwen", 2.6, 5, "constrained", "Smaller and faster.", { paramsB: 4, contextK: 40, vramGb: 4, bestFor: "quick chat" }),
  recommendedFixture,
  row("llama3.2:3b", "Llama", 2, 8, "none", "Chat and summaries only.", {
    paramsB: 3,
    thinking: false,
    licence: "Llama 3.2 community",
    bestFor: "plain chat and summaries",
  }),
  row("gpt-oss:20b", "gpt-oss", 14, 24, "native", "24 GB+ GPU or 32 GB+ Apple Silicon.", {
    paramsB: 21,
    vramGb: 16,
    bestFor: "strong tool use and reasoning",
  }),
  row("llama3.3:70b", "Llama", 43, 48, "native", "Needs a workstation.", {
    paramsB: 70,
    vramGb: 48,
    thinking: false,
    licence: "Llama 3.3 community",
    bestFor: "workstation-class agents",
  }),
];

export const recommendationFixture: LocalModelRecommendation = {
  hardware: { ramGb: 16, cpuCores: 8, gpu: null, appleSilicon: true },
  recommended: recommendedFixture,
  alternatives: [catalogFixture[0]],
  catalog: catalogFixture,
  budgetGb: 12,
  ollama: { reachable: true, baseUrl: "http://host.docker.internal:11434", installedTags: [] },
  servingModel: null,
};

export const probeFixture: LocalModelProbe = {
  tag: "qwen3:8b",
  toolCalling: "native",
  structuredOutput: true,
  latencyMs: 840,
  toolRouterMode: "native",
};

/** A fully working mock API; override any method per test. */
export function mockLocalModelApi(overrides: Partial<LocalModelWizardApi> = {}): LocalModelWizardApi {
  let polls = 0;
  return {
    getLocalModelRecommendation: async () => recommendationFixture,
    startLocalModelPull: async () => ({ jobId: "job-1" }),
    getLocalModelPull: async () => {
      polls += 1;
      return polls < 2
        ? { status: "running", percent: 40, message: "pulling layers" }
        : { status: "done", percent: 100, message: "success" };
    },
    probeLocalModel: async () => probeFixture,
    useLocalModel: async (tag) => ({ tag, rowId: 1, created: false, enabled: true, toolCallGrade: "STRUCTURED" }),
    getDefaultAgentModel: async () => ({ providerName: "PLATFORM_HOSTED", model: "platform-pool", source: "PLATFORM_POOL" }),
    createAgent: async (payload) => ({ id: "agent-1", name: (payload as { name?: string }).name }),
    ...overrides,
  };
}
