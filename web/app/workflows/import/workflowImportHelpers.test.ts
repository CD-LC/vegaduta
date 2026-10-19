import { describe, expect, it } from "vitest";
import type { PortabilityFidelity, PortabilityPlaceholder } from "../../lib/api/workflowPortability";
import {
  MAX_WORKFLOW_IMPORT_BYTES,
  MAX_WORKFLOW_NAME_LENGTH,
  buildPortabilityEnvelope,
  cleanMappings,
  detectWorkflowFormat,
  exceedsWorkflowImportLimit,
  fidelityBadgeVariant,
  fidelityPercent,
  groupPlaceholders,
  looksLikeWorkflowFile,
  mappablePlaceholders,
  nameFromFileName,
  pickerSourceFor,
  sameMappings,
  unmappedCount,
  utf8Bytes,
} from "./workflowImportHelpers";

function placeholder(over: Partial<PortabilityPlaceholder>): PortabilityPlaceholder {
  return { key: "agent_1", kind: "agent", label: "Researcher", mappable: true, resolved: false, mappedTo: "", usedBy: [], ...over };
}

describe("detectWorkflowFormat", () => {
  it("recognises a VegaDuta workflow file by its format marker or schemaVersion", () => {
    expect(detectWorkflowFormat('{"format":"vegaduta.workflow","schemaVersion":1,"nodes":[]}')).toBe("vegaduta");
    expect(detectWorkflowFormat('{"schemaVersion":1,"nodes":[],"edges":[]}')).toBe("vegaduta");
  });

  it("recognises an n8n export (nodes keyed to connections), also as the first of a list", () => {
    expect(detectWorkflowFormat('{"name":"x","nodes":[],"connections":{}}')).toBe("n8n");
    expect(detectWorkflowFormat('[{"nodes":[],"connections":{}},{"nodes":[],"connections":{}}]')).toBe("n8n");
  });

  it("recognises Dify DSL as YAML without parsing it, and as JSON", () => {
    expect(detectWorkflowFormat("app:\n  mode: workflow\nkind: app\nworkflow:\n  graph:\n    nodes: []\n")).toBe("dify");
    expect(detectWorkflowFormat('{"kind":"app","app":{"mode":"workflow"},"workflow":{"graph":{"nodes":[]}}}')).toBe("dify");
  });

  it("refuses to guess on anything else", () => {
    expect(detectWorkflowFormat("")).toBeNull();
    expect(detectWorkflowFormat("   ")).toBeNull();
    expect(detectWorkflowFormat("just words")).toBeNull();
    expect(detectWorkflowFormat('{"hello":1}')).toBeNull();
    expect(detectWorkflowFormat("{not json")).toBeNull();
    expect(detectWorkflowFormat("[1,2]")).toBeNull();
    expect(detectWorkflowFormat("app:\n  name: only an app\n")).toBeNull();
  });

  it("tolerates a BOM", () => {
    expect(detectWorkflowFormat('\uFEFF{"nodes":[],"connections":{}}')).toBe("n8n");
  });
});

describe("size limits", () => {
  it("counts UTF-8 bytes, including multi-byte and astral characters", () => {
    expect(utf8Bytes("abc")).toBe(3);
    expect(utf8Bytes("é")).toBe(2);
    expect(utf8Bytes("€")).toBe(3);
    expect(utf8Bytes("😀")).toBe(4);
  });

  it("flags text over the limit and passes text under it", () => {
    expect(exceedsWorkflowImportLimit("x".repeat(100), 100)).toBe(false);
    expect(exceedsWorkflowImportLimit("x".repeat(101), 100)).toBe(true);
    // 60 three-byte characters are 180 bytes: over a 100 byte cap although only 60 UTF-16 units long.
    expect(exceedsWorkflowImportLimit("€".repeat(60), 100)).toBe(true);
    expect(MAX_WORKFLOW_IMPORT_BYTES).toBe(2 * 1024 * 1024);
  });
});

describe("buildPortabilityEnvelope", () => {
  it("sends the text as the payload and nothing optional by default", () => {
    expect(buildPortabilityEnvelope("n8n", '{"nodes":[]}')).toEqual({ source: "n8n", payload: '{"nodes":[]}' });
  });

  it("falls back to auto for an unknown source and strips a BOM", () => {
    const envelope = buildPortabilityEnvelope("zapier" as any, "\uFEFFkind: app");
    expect(envelope.source).toBe("auto");
    expect(envelope.payload).toBe("kind: app");
  });

  it("adds a trimmed, capped name override only when non-blank", () => {
    expect(buildPortabilityEnvelope("auto", "x", "   ").overrides).toBeUndefined();
    expect(buildPortabilityEnvelope("auto", "x", "  My flow ").overrides).toEqual({ name: "My flow" });
    expect(buildPortabilityEnvelope("auto", "x", "n".repeat(500)).overrides?.name).toHaveLength(MAX_WORKFLOW_NAME_LENGTH);
  });

  it("sends only mappings that have a value", () => {
    const envelope = buildPortabilityEnvelope("auto", "x", "", { agent_1: " id-1 ", kb_1: "", workflow_1: "  " });
    expect(envelope.mappings).toEqual({ agent_1: "id-1" });
    expect(buildPortabilityEnvelope("auto", "x", "", { kb_1: "" }).mappings).toBeUndefined();
  });

  it("never puts the file in a URL-shaped field", () => {
    const envelope = buildPortabilityEnvelope("auto", "secret-looking text");
    expect(Object.keys(envelope).sort()).toEqual(["payload", "source"]);
  });
});

describe("mappings", () => {
  it("cleans and compares mapping sets regardless of blanks", () => {
    expect(cleanMappings(undefined)).toEqual({});
    expect(cleanMappings({ a: "1", b: "" })).toEqual({ a: "1" });
    expect(sameMappings({ a: "1" }, { a: "1", b: "" })).toBe(true);
    expect(sameMappings({ a: "1" }, { a: "2" })).toBe(false);
    expect(sameMappings({ a: "1" }, {})).toBe(false);
  });

  it("counts mappable placeholders that still have no pick", () => {
    const list = [
      placeholder({ key: "agent_1" }),
      placeholder({ key: "kb_1", kind: "kb" }),
      placeholder({ key: "credential_1", kind: "credential", mappable: false }),
    ];
    expect(mappablePlaceholders(list)).toHaveLength(2);
    expect(unmappedCount(list, {})).toBe(2);
    expect(unmappedCount(list, { agent_1: "id" })).toBe(1);
    expect(unmappedCount(list, { agent_1: "id", kb_1: "id2" })).toBe(0);
  });

  it("maps a kind to the list its choices come from", () => {
    expect(pickerSourceFor("agent")).toBe("agents");
    expect(pickerSourceFor("kb")).toBe("knowledge");
    expect(pickerSourceFor("workflow")).toBe("workflows");
    expect(pickerSourceFor("group")).toBeNull();
    expect(pickerSourceFor("credential")).toBeNull();
  });
});

describe("groupPlaceholders", () => {
  it("groups by kind in display order and leaves empty groups out", () => {
    const groups = groupPlaceholders([
      placeholder({ key: "secret:K", kind: "secret", mappable: false }),
      placeholder({ key: "agent_2" }),
      placeholder({ key: "credential_1", kind: "credential", mappable: false }),
      placeholder({ key: "agent_1" }),
      placeholder({ key: "weird_1", kind: "mystery", mappable: false }),
    ]);
    expect(groups.map((g) => g.kind)).toEqual(["agent", "credential", "secret", "mystery"]);
    expect(groups[0].items.map((p) => p.key)).toEqual(["agent_2", "agent_1"]);
    expect(groupPlaceholders([])).toEqual([]);
  });
});

describe("fidelity", () => {
  const base: PortabilityFidelity = {
    level: "partial",
    sourceNodes: 10,
    exact: 4,
    needsReview: 3,
    placeholders: 2,
    skipped: 1,
    sourceConnections: 9,
    connectionsKept: 8,
    connectionsDropped: 1,
  };

  it("reports the share of steps that mapped, not counting annotations", () => {
    expect(fidelityPercent(base)).toBe(78); // 7 of 9
    expect(fidelityPercent({ ...base, sourceNodes: 0, exact: 0, needsReview: 0, skipped: 0 })).toBe(0);
    expect(fidelityPercent({ ...base, exact: 99 })).toBe(100);
  });

  it("maps each level to a design-system badge variant", () => {
    expect(fidelityBadgeVariant("high")).toBe("default");
    expect(fidelityBadgeVariant("partial")).toBe("secondary");
    expect(fidelityBadgeVariant("low")).toBe("outline");
  });
});

describe("file names", () => {
  it("suggests a name from the file name", () => {
    expect(nameFromFileName("order-flow.vegaduta-workflow.json")).toBe("order flow");
    expect(nameFromFileName("My_Support_App.yml")).toBe("My Support App");
    expect(nameFromFileName("")).toBe("");
  });

  it("accepts JSON and YAML files by name or mime type", () => {
    expect(looksLikeWorkflowFile("a.json", "")).toBe(true);
    expect(looksLikeWorkflowFile("a.yaml", "")).toBe(true);
    expect(looksLikeWorkflowFile("a.YML", "")).toBe(true);
    expect(looksLikeWorkflowFile("export", "application/json")).toBe(true);
    expect(looksLikeWorkflowFile("a.exe", "application/octet-stream")).toBe(false);
  });
});
