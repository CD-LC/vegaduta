import { describe, expect, it } from "vitest";
import type { AddonApply, AddonView } from "./addonsApi";
import { addon, catalogFixture } from "../components/addons/fixtures";
import {
  NO_FILTERS,
  categoryCounts,
  dependentsOf,
  envLines,
  errorKeyFor,
  filterAddons,
  formatMb,
  groupByCategory,
  isWindowsClient,
  learnMoreHref,
  pollIntervalMs,
  requirementsToEnable,
  statusBucket,
  statusTone,
  windowsApply,
} from "./addonsModel";

const byId = (list: AddonView[]) => new Map(list.map((a) => [a.id, a] as const));
const text = (a: AddonView) => `${a.name} ${a.description}`;

describe("grouping and filtering", () => {
  it("groups in display order, keeps catalog order inside a group and appends unknown categories", () => {
    const list = [
      addon({ id: "a", category: "tools" }),
      addon({ id: "b", category: "assistant" }),
      addon({ id: "c", category: "assistant" }),
      addon({ id: "d", category: "future" as never }),
    ];
    expect(groupByCategory(list).map((g) => [g.category, g.items.map((i) => i.id)])).toEqual([
      ["assistant", ["b", "c"]],
      ["tools", ["a"]],
      ["future", ["d"]],
    ]);
    expect(categoryCounts(list).get("assistant")).toBe(2);
  });

  it("filters by visible text, category and state bucket", () => {
    const list = catalogFixture();
    const ids = (f: Partial<typeof NO_FILTERS>) => filterAddons(list, { ...NO_FILTERS, ...f }, text).map((a) => a.id);
    expect(ids({})).toHaveLength(list.length);
    expect(ids({ query: "  CHROMIUM " })).toEqual(["browser"]);
    expect(ids({ query: "model-qwen" })).toEqual(["model-qwen35-2b"]);
    expect(ids({ category: "models" })).toEqual(["model-qwen35-2b"]);
    expect(ids({ status: "on" })).toEqual(["workspace"]);
    expect(ids({ status: "planned" })).toEqual(["tools"]);
    expect(ids({ status: "off" })).toEqual(["search", "documents", "browser", "model-qwen35-2b"]);
    expect(ids({ query: "zzz" })).toEqual([]);
  });

  it("puts everything in flight or failed under attention", () => {
    for (const status of ["enable_requested", "applying", "disable_requested", "pulling", "failed"] as const) {
      expect(statusBucket(addon({ id: "x", status })), status).toBe("attention");
    }
    expect(statusBucket(addon({ id: "x", status: "unavailable", availability: "planned" }))).toBe("planned");
  });
});

describe("polling cadence", () => {
  it("is 2 s for a download, 5 s for a host step, and off otherwise", () => {
    expect(pollIntervalMs([addon({ id: "a" }), addon({ id: "b", status: "on" })])).toBeNull();
    expect(pollIntervalMs([addon({ id: "a", status: "enable_requested" })])).toBe(5000);
    expect(pollIntervalMs([addon({ id: "a", status: "disable_requested" })])).toBe(5000);
    expect(pollIntervalMs([addon({ id: "a", status: "applying" })])).toBe(5000);
    expect(pollIntervalMs([addon({ id: "a", status: "enable_requested" }), addon({ id: "b", status: "pulling" })])).toBe(2000);
    // A failure or a planned entry never keeps the page polling.
    expect(pollIntervalMs([addon({ id: "a", status: "failed" }), addon({ id: "b", status: "unavailable" })])).toBeNull();
  });
});

describe("dependencies", () => {
  const chain = [
    addon({ id: "base", desired: true, status: "on", requiredBy: ["mid"] }),
    addon({ id: "mid", requires: ["base"], desired: true, status: "on", requiredBy: ["top"] }),
    addon({ id: "top", requires: ["mid"] }),
    addon({ id: "lone", requires: ["off-dep"] }),
    addon({ id: "off-dep" }),
  ];

  it("lists the requirements a turn-on would also switch on, requirements first, skipping those already wanted", () => {
    expect(requirementsToEnable("top", byId(chain)).map((a) => a.id)).toEqual([]);
    expect(requirementsToEnable("lone", byId(chain)).map((a) => a.id)).toEqual(["off-dep"]);
    const cold = chain.map((a) => ({ ...a, desired: false }));
    expect(requirementsToEnable("top", byId(cold)).map((a) => a.id)).toEqual(["base", "mid"]);
  });

  it("follows requiredBy transitively for a disable preview and survives a cycle", () => {
    expect(dependentsOf("base", byId(chain)).map((a) => a.id)).toEqual(["mid", "top"]);
    expect(dependentsOf("top", byId(chain))).toEqual([]);
    const loop = [addon({ id: "x", requiredBy: ["y"] }), addon({ id: "y", requiredBy: ["x"] })];
    expect(dependentsOf("x", byId(loop)).map((a) => a.id)).toEqual(["y"]);
  });
});

describe("formatting", () => {
  it("rounds sizes the way an approximation should read", () => {
    expect(formatMb(0)).toBe("0 MB");
    expect(formatMb(377)).toBe("377 MB");
    expect(formatMb(1350)).toBe("1.3 GB");
    expect(formatMb(1536)).toBe("1.5 GB");
    expect(formatMb(2048)).toBe("2 GB");
    expect(formatMb(14336)).toBe("14 GB");
    expect(formatMb(Number.NaN)).toBe("0 MB");
  });

  it("maps statuses to a tone", () => {
    expect(statusTone("on")).toBe("success");
    expect(statusTone("failed")).toBe("danger");
    expect(statusTone("enable_requested")).toBe("warning");
    expect(statusTone("pulling")).toBe("progress");
    expect(statusTone("off")).toBe("neutral");
  });
});

describe("learnMoreHref", () => {
  it("links repository docs paths, keeps the anchor and rejects anything else", () => {
    expect(learnMoreHref("docs/local-models.md#free-web-search")).toBe(
      "https://github.com/CD-LC/vegaduta/blob/main/docs/local-models.md#free-web-search"
    );
    expect(learnMoreHref("docs/architecture/OUT-OF-THE-BOX-PERSONAL-2026-10-19.md")).toContain("/blob/main/docs/architecture/");
    expect(learnMoreHref("")).toBeNull();
    expect(learnMoreHref("https://evil.example/x.md")).toBeNull();
    expect(learnMoreHref("javascript:alert(1)")).toBeNull();
    expect(learnMoreHref("README.md")).toBeNull();
  });
});

describe("host commands", () => {
  const apply: AddonApply = {
    command: "./scripts/vegaduta personal --profile rag --profile workspace --search",
    profiles: ["rag", "workspace"],
    flags: ["--search"],
    envSet: { CORE_MEM_LIMIT: "2560m", CORE_IMAGE_VARIANT: "-full" },
    envUnset: [],
    pending: [],
  };

  it("sorts env lines so the text is stable between polls", () => {
    expect(envLines(apply.envSet)).toEqual(["CORE_IMAGE_VARIANT=-full", "CORE_MEM_LIMIT=2560m"]);
    expect(envLines({})).toEqual([]);
  });

  it("builds the PowerShell form and reports the flag it cannot express", () => {
    expect(windowsApply(apply)).toEqual({
      command: ".\\scripts\\vegaduta.ps1 personal -Profile rag,workspace",
      unsupportedFlags: ["--search"],
    });
    expect(windowsApply({ ...apply, profiles: [], flags: [] })).toEqual({
      command: ".\\scripts\\vegaduta.ps1 personal",
      unsupportedFlags: [],
    });
  });

  it("detects a Windows client", () => {
    expect(isWindowsClient("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).toBe(true);
    expect(isWindowsClient("Mozilla/5.0 (Macintosh; Intel Mac OS X)")).toBe(false);
    expect(isWindowsClient(undefined)).toBe(false);
  });
});

describe("errorKeyFor", () => {
  it("prefers the controller's code, then the HTTP status", () => {
    expect(errorKeyFor(409, "required_by")).toBe("error.required_by");
    expect(errorKeyFor(503, "ollama_unreachable")).toBe("error.ollama_unreachable");
    expect(errorKeyFor(429, null)).toBe("error.rate_limited");
    expect(errorKeyFor(403, null)).toBe("error.forbidden");
    expect(errorKeyFor(401, "whatever")).toBe("error.signedOut");
    expect(errorKeyFor(503, null)).toBe("error.unavailable");
    expect(errorKeyFor(undefined, null)).toBe("error.generic");
  });
});
