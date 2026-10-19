// D3: the "data-analyst" console template - registration, honest prerequisites,
// open-on-every-plan listing, and the post-create capability enable.

import { describe, it, expect, vi } from "vitest";
import { AGENT_TEMPLATES, CATEGORY_AUDIENCE, templateNameKey, templateTaglineKey } from "../agentTemplates";
import { prereqsForConsoleTemplate } from "./templatePrereqs";
import { OPEN_CONSOLE_TEMPLATE_KEYS, findStarterTemplate, isOpenConsoleTemplate, isStarterTemplateKey } from "./starterTemplates";
import {
  describeCapabilityOutcome,
  enableTemplateCapabilities,
  fileAnalysisUnavailableNote,
  templateCapabilityKeys,
  templateWantsFileAnalysis,
  type TemplateCapabilityApi,
} from "./templateCapabilities";
import type { AgentCapabilityEntry } from "../../lib/api";
import en from "../../../messages/en.json";

const strings = (en as Record<string, any>).agentTemplates as Record<string, string>;

function capability(overrides: Partial<AgentCapabilityEntry> = {}): AgentCapabilityEntry {
  return {
    key: "e2b-code-execution",
    label: "Code execution",
    description: "Run short snippets in a sandbox.",
    category: "tools",
    enabled: false,
    availability: "AVAILABLE",
    availabilityHint: null,
    ...overrides,
  };
}

function stubApi(list: AgentCapabilityEntry[] | Error, setResult: unknown | Error = {}): TemplateCapabilityApi & {
  listAgentCapabilities: ReturnType<typeof vi.fn>;
  setAgentCapability: ReturnType<typeof vi.fn>;
} {
  return {
    listAgentCapabilities:
      list instanceof Error ? vi.fn().mockRejectedValue(list) : vi.fn().mockResolvedValue(list),
    setAgentCapability:
      setResult instanceof Error ? vi.fn().mockRejectedValue(setResult) : vi.fn().mockResolvedValue(setResult),
  };
}

// The scoped translator's contract: {name} placeholders filled from params.
const t = (key: string, params?: Record<string, string | number>) =>
  (strings[key] ?? key).replace(/\{(\w+)\}/g, (_: string, name: string) => String(params?.[name] ?? `{${name}}`));

describe("data-analyst console template", () => {
  const template = AGENT_TEMPLATES.find((x) => x.key === "data-analyst");

  it("is registered once, in a personal-audience category", () => {
    expect(AGENT_TEMPLATES.filter((x) => x.key === "data-analyst")).toHaveLength(1);
    expect(template).toBeTruthy();
    // Widened: `category` is a plain string on the template union (TS7053).
    expect((CATEGORY_AUDIENCE as Record<string, string | undefined>)[template!.category]).toBe("personal");
  });

  it("has its display strings in en.json", () => {
    expect(strings[templateNameKey("data-analyst")]).toBeTruthy();
    expect(strings[templateTaglineKey("data-analyst")]).toBeTruthy();
  });

  it("is file-first: mounted files in, saved chart/workbook/script and verbatim urls out", () => {
    const prompt = template!.systemPrompt;
    for (const needle of ["run_code", "mountedFiles", "chart.png", "results.xlsx", "analysis.py", "own line"]) {
      expect(prompt, `prompt should mention ${needle}`).toContain(needle);
    }
    // Honest when the tool is missing rather than inventing numbers.
    expect(prompt).toContain("not switched on");
    // A file the model can already read (inline CSV on a document-capable
    // model, mountedFiles empty) must be used, not asked for again.
    expect(prompt).toContain("already in the conversation");
    // No download urls back (file outputs off): results go in the reply.
    expect(prompt).toContain("If no url comes back");
  });

  it("does not promise file uploads, charts or workbooks in its gallery tagline", () => {
    const tagline = strings[templateTaglineKey("data-analyst")];
    for (const claim of ["Attach", "Excel", "workbook", "chart"]) {
      expect(tagline, `tagline must not promise "${claim}" on a default deployment`).not.toContain(claim);
    }
  });

  it("declares the Code execution capability and nothing else does", () => {
    expect(templateCapabilityKeys("data-analyst")).toEqual(["e2b-code-execution"]);
    expect(templateCapabilityKeys("homework-helper")).toEqual([]);
    expect(templateCapabilityKeys("starter:kirana")).toEqual([]);
    expect(templateCapabilityKeys("no-such-template")).toEqual([]);
    expect(templateCapabilityKeys(null)).toEqual([]);
  });

  it("reports the E2B prerequisite in the setup guide data, and no MCP server", () => {
    const prereqs = prereqsForConsoleTemplate("data-analyst", template);
    expect(prereqs.e2b).toBe(true);
    expect(prereqs.mcp).toEqual([]);
    expect(prereqs.knowledge).toBe("none");
    expect(prereqs.channel).toBe(false);
    // Every other console template is unchanged.
    expect(prereqsForConsoleTemplate("homework-helper").e2b).toBe(false);
  });

  it("stays open on every plan without becoming a persona starter", () => {
    expect(OPEN_CONSOLE_TEMPLATE_KEYS).toContain("data-analyst");
    expect(isOpenConsoleTemplate("data-analyst")).toBe(true);
    expect(isOpenConsoleTemplate("homework-helper")).toBe(false);
    expect(isOpenConsoleTemplate(null)).toBe(false);
    expect(isStarterTemplateKey("data-analyst")).toBe(false);
    expect(findStarterTemplate("data-analyst")).toBeNull();
    // Every open key must be a real console template.
    for (const key of OPEN_CONSOLE_TEMPLATE_KEYS) {
      expect(AGENT_TEMPLATES.some((x) => x.key === key), `unknown open template "${key}"`).toBe(true);
    }
  });
});

describe("enableTemplateCapabilities", () => {
  it("makes no request when the template declares nothing", async () => {
    const api = stubApi([capability()]);
    expect(await enableTemplateCapabilities(api, "agent-1", [])).toEqual({ enabled: [], needsSetup: [], unavailable: [], failed: [] });
    expect(api.listAgentCapabilities).not.toHaveBeenCalled();
    expect(api.setAgentCapability).not.toHaveBeenCalled();
  });

  it("switches an available capability on", async () => {
    const api = stubApi([capability()]);
    const outcome = await enableTemplateCapabilities(api, "agent-1", ["e2b-code-execution"]);
    expect(api.setAgentCapability).toHaveBeenCalledWith("agent-1", "e2b-code-execution", true);
    expect(outcome).toEqual({ enabled: ["Code execution"], needsSetup: [], unavailable: [], failed: [] });
  });

  it("still switches a NEEDS_SETUP capability on and hands back the server's hint", async () => {
    const hint = "Activate an E2B key with a code-execution template on the Providers page first.";
    const api = stubApi([capability({ availability: "NEEDS_SETUP", availabilityHint: hint })]);
    const outcome = await enableTemplateCapabilities(api, "agent-1", ["e2b-code-execution"]);
    expect(api.setAgentCapability).toHaveBeenCalledTimes(1);
    expect(outcome.enabled).toEqual(["Code execution"]);
    expect(outcome.needsSetup).toEqual([{ label: "Code execution", hint }]);
    expect(outcome.failed).toEqual([]);
  });

  it("does not re-send a capability that is already on", async () => {
    const api = stubApi([capability({ enabled: true })]);
    const outcome = await enableTemplateCapabilities(api, "agent-1", ["e2b-code-execution"]);
    expect(api.setAgentCapability).not.toHaveBeenCalled();
    expect(outcome.enabled).toEqual(["Code execution"]);
  });

  it("leaves an UNAVAILABLE or not-offered capability off and reports it", async () => {
    const unavailable = stubApi([capability({ availability: "UNAVAILABLE" })]);
    expect(await enableTemplateCapabilities(unavailable, "agent-1", ["e2b-code-execution"])).toEqual({
      enabled: [],
      needsSetup: [],
      unavailable: ["Code execution"],
      failed: [],
    });
    expect(unavailable.setAgentCapability).not.toHaveBeenCalled();

    // Not in the server's list: a friendly label, never the raw key.
    const notOffered = stubApi([]);
    const outcome = await enableTemplateCapabilities(notOffered, "agent-1", ["e2b-code-execution"]);
    expect(outcome.unavailable).toEqual(["Code execution"]);
    expect(outcome.failed).toEqual([]);
    expect((await enableTemplateCapabilities(notOffered, "agent-1", ["some-new-key"])).unavailable).toEqual([
      "This capability",
    ]);
  });

  it("never throws: a failed list or a failed toggle is reported, not raised", async () => {
    const listFails = stubApi(new Error("boom"));
    expect(await enableTemplateCapabilities(listFails, "agent-1", ["e2b-code-execution"])).toEqual({
      enabled: [],
      needsSetup: [],
      unavailable: [],
      failed: ["Code execution"],
    });

    const toggleFails = stubApi([capability()], new Error("Editing is a Premium feature"));
    expect(await enableTemplateCapabilities(toggleFails, "agent-1", ["e2b-code-execution"])).toEqual({
      enabled: [],
      needsSetup: [],
      unavailable: [],
      failed: ["Code execution"],
    });
  });
});

describe("describeCapabilityOutcome", () => {
  it("says nothing on plain success", () => {
    expect(describeCapabilityOutcome({ enabled: ["Code execution"], needsSetup: [], unavailable: [], failed: [] }, t)).toBeNull();
    expect(describeCapabilityOutcome({ enabled: [], needsSetup: [], unavailable: [], failed: [] }, t)).toBeNull();
  });

  it("carries the setup hint, using keys that exist in en.json", () => {
    for (const key of ["capabilityNeedsSetupNote", "capabilityNeedsSetupNoHint", "capabilityEnableFailedNote"]) {
      expect(strings[key], `missing agentTemplates.${key}`).toBeTruthy();
    }
    const withHint = describeCapabilityOutcome(
      { enabled: ["Code execution"], needsSetup: [{ label: "Code execution", hint: "Connect E2B first." }], unavailable: [], failed: [] },
      t
    );
    expect(withHint).toContain("Code execution");
    expect(withHint).toContain("Connect E2B first.");
    expect(withHint).not.toContain("{");

    const noHint = describeCapabilityOutcome(
      { enabled: ["Code execution"], needsSetup: [{ label: "Code execution", hint: null }], unavailable: [], failed: [] },
      t
    );
    expect(noHint).toContain("Code execution");
    expect(noHint).not.toContain("{");
  });

  it("tells the person where to switch on a capability that could not be enabled", () => {
    const note = describeCapabilityOutcome(
      { enabled: [], needsSetup: [], unavailable: [], failed: ["Code execution"] },
      t
    );
    expect(note).toContain("Code execution");
    expect(note).toContain("Capabilities");
  });

  it("does not send the person to a toggle that is not there when the capability is unavailable", () => {
    expect(strings.capabilityUnavailableNote, "missing agentTemplates.capabilityUnavailableNote").toBeTruthy();
    const note = describeCapabilityOutcome(
      { enabled: [], needsSetup: [], unavailable: ["Code execution"], failed: [] },
      t
    );
    expect(note).toContain("Code execution");
    expect(note).not.toContain("turn it on");
    expect(note).not.toContain("{");
  });
});

describe("fileAnalysisUnavailableNote", () => {
  it("applies only to templates that declare Code execution", () => {
    expect(templateWantsFileAnalysis("data-analyst")).toBe(true);
    expect(templateWantsFileAnalysis("homework-helper")).toBe(false);
    expect(templateWantsFileAnalysis(null)).toBe(false);
  });

  it("speaks up when file analysis is off and the server gave no reason (the default deployment)", () => {
    expect(strings.fileAnalysisUnavailableNote, "missing agentTemplates.fileAnalysisUnavailableNote").toBeTruthy();
    expect(fileAnalysisUnavailableNote({ modalities: { sandboxData: false } }, t)).toBe(
      strings.fileAnalysisUnavailableNote
    );
    expect(fileAnalysisUnavailableNote({ modalities: null }, t)).toBe(strings.fileAnalysisUnavailableNote);
  });

  it("stays quiet when file analysis works, when the server explains itself, or when the agent is unknown", () => {
    expect(fileAnalysisUnavailableNote({ modalities: { sandboxData: true } }, t)).toBeNull();
    expect(
      fileAnalysisUnavailableNote(
        { modalities: { sandboxData: false, sandboxDataUnavailableReason: "Turn on Code execution for this agent." } },
        t
      )
    ).toBeNull();
    expect(fileAnalysisUnavailableNote(null, t)).toBeNull();
  });
});
