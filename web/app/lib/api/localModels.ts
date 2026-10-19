import { apiFetch, jsonBody, toJson } from "./core";

/** Tool-calling grade the backend assigns a model: native function calling, constrained-JSON emulation, or none. */
export type ToolCallingGrade = "native" | "constrained" | "none";

export interface LocalModelChoice {
  tag: string;
  /** Model family as the catalog names it (Qwen, Gemma, Llama, ...). */
  family: string;
  downloadGb: number;
  ramGb: number;
  toolCalling: ToolCallingGrade;
  note: string;
  /** Total parameters in billions (mixture-of-experts rows count every expert). */
  paramsB: number;
  /** Context window in thousands of tokens. */
  contextK: number;
  /** Video or unified memory that holds the whole model at its default quantisation, in GB. */
  vramGb: number;
  licence: string;
  /** Whether the model reasons before answering (slower first token). */
  thinking: boolean;
  bestFor: string;
  /** True when `ramGb` is within this machine's memory budget (`LocalModelRecommendation.budgetGb`). */
  fits: boolean;
}

export interface LocalHardware {
  ramGb: number;
  cpuCores: number;
  gpu: { vendor: string; name: string; vramGb: number } | null;
  appleSilicon: boolean;
}

/** GET /api/local-models/recommendation. */
export interface LocalModelRecommendation {
  hardware: LocalHardware;
  recommended: LocalModelChoice;
  /** The runners-up that fit the budget, best first (at most four). */
  alternatives: LocalModelChoice[];
  /** Every catalog row in catalog order, each flagged `fits`; nothing is hidden for being too big. */
  catalog: LocalModelChoice[];
  /** The memory, in GB, this machine can give a model: the cut-off for `fits`. */
  budgetGb: number;
  ollama: { reachable: boolean; baseUrl: string; installedTags: string[] };
  /** The tag the local pool row currently serves (null when no row points at this server). */
  servingModel: string | null;
}

/** POST /api/local-models/use. */
export interface LocalModelUseResult {
  tag: string;
  rowId: number;
  /** True when no pool row pointed at this server and one was created. */
  created: boolean;
  enabled: boolean;
  toolCallGrade: string | null;
}

/** POST /api/local-models/probe. */
export interface LocalModelProbe {
  tag: string;
  toolCalling: ToolCallingGrade;
  structuredOutput: boolean;
  latencyMs: number;
  toolRouterMode: string;
}

export type PullStatus = "pending" | "running" | "done" | "failed";

/** GET /api/local-models/pull/{jobId}. */
export interface LocalModelPullState {
  status: PullStatus;
  percent: number;
  message: string;
}

/** POST /api/local-models/warm. */
export interface LocalModelWarmResult {
  tag: string;
  /** False when the model server was unreachable or refused the load; the chat still works, just colder. */
  loaded: boolean;
  ms: number;
}

/** Reasoning switch values; "default" leaves the model's own behaviour alone. */
export type ReasoningEffort = "none" | "low" | "medium" | "high" | "default";

/** GET/PUT /api/local-models/settings: the local model's console-editable behaviour. */
export interface LocalModelSettings {
  servingModel: string | null;
  reasoningEffort: ReasoningEffort;
  /** Ollama keep_alive: how long the model stays in memory (30m, 2h, -1 for always). */
  keepAlive: string;
  /** The context window chosen in the console (a model variant made for it); null = the server default. */
  contextLength: number | null;
  /** The context Ollama actually loaded the model with; null when it is not loaded. */
  loadedContext: number | null;
  /** The context core asks for on every chat. */
  wantedContext: number;
}

export const localModelsApi = {
  getLocalModelSettings: () => apiFetch(`/api/local-models/settings`, { skipGetCache: true }).then(toJson<LocalModelSettings>),
  saveLocalModelSettings: (update: { reasoningEffort?: ReasoningEffort; keepAlive?: string; contextLength?: number }) =>
    apiFetch(`/api/local-models/settings`, { method: "PUT", ...jsonBody(update) }).then(toJson<LocalModelSettings>),
  /** Loads the local model into memory ahead of the first chat (no tag = the pool row's model). */
  warmLocalModel: (tag?: string) =>
    apiFetch(`/api/local-models/warm`, { method: "POST", ...jsonBody({ tag: tag || undefined }) }).then(
      toJson<LocalModelWarmResult>
    ),
  getLocalModelRecommendation: () =>
    apiFetch(`/api/local-models/recommendation`).then(toJson<LocalModelRecommendation>),
  probeLocalModel: (tag: string) =>
    apiFetch(`/api/local-models/probe`, { method: "POST", ...jsonBody({ tag }) }).then(toJson<LocalModelProbe>),
  startLocalModelPull: (tag: string) =>
    apiFetch(`/api/local-models/pull`, { method: "POST", ...jsonBody({ tag }) }).then(toJson<{ jobId: string }>),
  getLocalModelPull: (jobId: string) =>
    apiFetch(`/api/local-models/pull/${encodeURIComponent(jobId)}`).then(toJson<LocalModelPullState>),
  useLocalModel: (tag: string) =>
    apiFetch(`/api/local-models/use`, { method: "POST", ...jsonBody({ tag }) }).then(toJson<LocalModelUseResult>),
};
