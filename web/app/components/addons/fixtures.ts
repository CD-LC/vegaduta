// In-memory stand-in for /api/addons, used by the tests so the page can be exercised without core.
// Shapes mirror lib/addonsApi.ts; behaviour mirrors the contract in docs/console/add-ons.md (compose
// add-ons wait for the host, models start pulling at once, a disable that others need is refused).
import {
  AddonApiError,
  type AddonApply,
  type AddonChange,
  type AddonList,
  type AddonView,
  type AddonsApi,
} from "../../lib/addonsApi";

export function addon(overrides: Partial<AddonView> & Pick<AddonView, "id">): AddonView {
  return {
    name: overrides.id,
    description: `${overrides.id} description`,
    category: "assistant",
    kind: "compose-profile",
    availability: "available",
    sizeMb: 500,
    minRamMb: 1024,
    needsGpu: false,
    license: "Apache-2.0",
    free: true,
    requires: [],
    defaultOn: false,
    hostCommand: "./scripts/vegaduta personal",
    learnMore: "docs/personal-mode.md",
    unlocks: [],
    model: null,
    desired: false,
    status: "off",
    message: null,
    progress: null,
    installed: null,
    desiredAt: null,
    reportedAt: null,
    fit: "ok",
    canEnable: true,
    blockedReason: null,
    requiredBy: [],
    ...overrides,
  };
}

/** A small catalog shaped like the real one. `documents` requires `workspace` here to exercise dependencies. */
export function catalogFixture(): AddonView[] {
  return [
    addon({
      id: "workspace",
      name: "Build terminal and projects folder",
      description: "Lets the Assistant create, run and test files in a projects folder on your computer.",
      sizeMb: 1350,
      minRamMb: 3072,
      defaultOn: true,
      desired: true,
      status: "on",
      unlocks: ["assistant_terminal"],
    }),
    addon({
      id: "search",
      name: "Web search",
      description: "A private search engine that runs next to VegaDūta.",
      category: "search",
      sizeMb: 377,
      minRamMb: 512,
      license: "AGPL-3.0",
      unlocks: ["web_search"],
      learnMore: "docs/local-models.md#free-web-search",
    }),
    addon({
      id: "documents",
      name: "Documents and transcription",
      description: "Reads PDFs and scanned pages, and turns audio and video into text.",
      category: "documents",
      sizeMb: 4140,
      minRamMb: 2048,
      requires: ["workspace"],
      unlocks: ["documents", "transcription"],
    }),
    addon({
      id: "browser",
      name: "Assistant browser",
      description: "Lets the Assistant drive its own headless Chromium browser.",
      sizeMb: 6000,
      minRamMb: 1024,
      license: "Apache-2.0; Chromium: BSD-3-Clause",
      fit: "low_memory",
      unlocks: ["browser_automation"],
    }),
    addon({
      id: "model-qwen35-2b",
      name: "Qwen 3.5 2B",
      description: "A small free model that runs on this computer.",
      category: "models",
      kind: "model-pull",
      sizeMb: 1536,
      minRamMb: 6144,
      model: "qwen3.5:2b",
      installed: false,
      unlocks: ["local_models"],
    }),
    addon({
      id: "tools",
      name: "Tools runner (MCP servers)",
      description: "Planned. Run MCP tool servers in a sandbox.",
      category: "tools",
      kind: "planned",
      availability: "planned",
      sizeMb: 0,
      minRamMb: 0,
      license: "",
      status: "unavailable",
      canEnable: false,
      blockedReason: "planned",
      unlocks: ["mcp_tools"],
      learnMore: "docs/architecture/OUT-OF-THE-BOX-PERSONAL-2026-10-19.md",
    }),
  ];
}

export function applyFor(addons: AddonView[]): AddonApply {
  const wanted = addons.filter((a) => a.desired && a.kind === "compose-profile");
  const profiles = wanted.filter((a) => ["workspace", "documents"].includes(a.id)).map((a) => (a.id === "documents" ? "rag" : a.id));
  const flags = wanted.some((a) => a.id === "search") ? ["--search"] : [];
  const envSet = wanted.some((a) => a.id === "browser") ? { CORE_IMAGE_VARIANT: "-full", CORE_MEM_LIMIT: "2560m" } : {};
  const flat = [...profiles.flatMap((p) => ["--profile", p]), ...flags].join(" ");
  return {
    command: `./scripts/vegaduta personal${flat ? " " + flat : ""}`,
    profiles,
    flags,
    envSet,
    envUnset: wanted.some((a) => a.id === "browser") ? [] : ["CORE_IMAGE_VARIANT", "CORE_MEM_LIMIT"],
    pending: addons.filter((a) => a.status === "enable_requested" || a.status === "disable_requested").map((a) => a.id),
  };
}

export interface MockOptions {
  addons?: AddonView[];
  bridge?: boolean;
  ollama?: boolean;
  ramGb?: number;
}

export interface MockAddonsApi extends AddonsApi {
  state: { addons: AddonView[] };
  /** Test hook: change what the next list() returns. */
  patch: (id: string, change: Partial<AddonView>) => void;
}

export function mockAddonsApi(opts: MockOptions = {}): MockAddonsApi {
  const state = { addons: opts.addons ?? catalogFixture() };
  const byId = (id: string) => {
    const a = state.addons.find((x) => x.id === id);
    if (!a) throw new AddonApiError("No such add-on.", 404, "unknown_addon", {});
    return a;
  };
  const set = (id: string, change: Partial<AddonView>) => {
    state.addons = state.addons.map((a) => (a.id === id ? { ...a, ...change } : a));
  };
  const refreshRequiredBy = () => {
    state.addons = state.addons.map((a) => ({
      ...a,
      requiredBy: state.addons.filter((o) => o.desired && o.status !== "off" && o.requires.includes(a.id)).map((o) => o.id),
    }));
  };
  const snapshot = (): AddonList => ({
    catalogSchema: 1,
    hardware: { ramGb: opts.ramGb ?? 16, cpuCores: 8, gpu: null, appleSilicon: true },
    hostBridge: { configured: opts.bridge ?? true },
    ollama: { reachable: opts.ollama ?? true },
    apply: applyFor(state.addons),
    addons: state.addons,
  });
  const change = (id: string, changed: string[]): AddonChange => ({
    addon: byId(id),
    changed,
    needsHost: state.addons.some((a) => a.status === "enable_requested" || a.status === "disable_requested"),
    apply: applyFor(state.addons),
  });

  return {
    state,
    patch: (id, c) => set(id, c),
    list: async () => snapshot(),
    get: async (id) => byId(id),
    enable: async (id) => {
      const target = byId(id);
      if (target.availability === "planned") throw new AddonApiError("Not available.", 409, "not_available", {});
      if (target.kind === "model-pull" && opts.ollama === false) {
        throw new AddonApiError("Ollama is not reachable.", 503, "ollama_unreachable", {});
      }
      const changed: string[] = [];
      for (const dep of [...target.requires, id]) {
        const cur = byId(dep);
        if (cur.desired) continue;
        changed.push(dep);
        set(dep, cur.kind === "model-pull" ? { desired: true, status: "pulling", progress: 0 } : { desired: true, status: "enable_requested" });
      }
      refreshRequiredBy();
      return change(id, changed);
    },
    disable: async (id, cascade = false) => {
      byId(id);
      const dependents = state.addons.filter((a) => a.desired && a.requires.includes(id)).map((a) => a.id);
      if (dependents.length > 0 && !cascade) {
        throw new AddonApiError("Other add-ons need this one.", 409, "required_by", { dependents });
      }
      const changed: string[] = [];
      for (const dep of [...dependents, id]) {
        changed.push(dep);
        set(dep, { desired: false, status: byId(dep).status === "on" ? "disable_requested" : "off" });
      }
      refreshRequiredBy();
      return change(id, changed);
    },
  };
}
