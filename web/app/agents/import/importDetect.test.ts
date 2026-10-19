import { describe, expect, it } from "vitest";
import {
  MAX_IMPORT_BYTES,
  MAX_NAME_LENGTH,
  buildEnvelope,
  detectSource,
  detectSourceFromText,
  exceedsImportLimit,
  parseExport,
  utf8ByteLength,
} from "./importDetect";

describe("parseExport", () => {
  it("rejects empty and whitespace-only input", () => {
    expect(parseExport("")).toEqual({ payload: null, reason: "empty" });
    expect(parseExport("   \n ")).toEqual({ payload: null, reason: "empty" });
  });

  it("rejects pasted code as invalid JSON", () => {
    const code = "from agents import Agent\nagent = Agent(name='x', instructions='y')";
    expect(parseExport(code)).toEqual({ payload: null, reason: "invalid-json" });
  });

  it("rejects JSON that is not an object", () => {
    expect(parseExport("[1,2]")).toEqual({ payload: null, reason: "not-object" });
    expect(parseExport('"hello"')).toEqual({ payload: null, reason: "not-object" });
    expect(parseExport("null")).toEqual({ payload: null, reason: "not-object" });
  });

  it("parses an object and tolerates a BOM and surrounding whitespace", () => {
    const out = parseExport('﻿  {"name":"a"}\n');
    expect(out).toEqual({ payload: { name: "a" }, reason: null });
  });
});

describe("detectSource", () => {
  it("detects an OpenAI Assistants API object", () => {
    expect(detectSource({ id: "asst_123", object: "assistant", model: "gpt-4o", instructions: "hi" })).toBe(
      "openai-assistant"
    );
    expect(detectSource({ id: "asst_abc", name: "x" })).toBe("openai-assistant");
    expect(detectSource({ tool_resources: {}, instructions: "x" })).toBe("openai-assistant");
  });

  it("detects Assistants function tools by their nested function definition", () => {
    expect(
      detectSource({ instructions: "x", tools: [{ type: "function", function: { name: "lookup", parameters: {} } }] })
    ).toBe("openai-assistant");
  });

  it("detects an Agents SDK style agent by its SDK-only keys", () => {
    expect(detectSource({ name: "Triage", instructions: "x", handoffs: ["billing"] })).toBe("openai-agents");
    expect(detectSource({ name: "a", instructions: "x", model_settings: { temperature: 0.2 } })).toBe("openai-agents");
    expect(detectSource({ name: "a", instructions: "x", mcpServers: [] })).toBe("openai-agents");
    expect(detectSource({ name: "a", instructions: "x", input_guardrails: [] })).toBe("openai-agents");
  });

  it("detects a Flowise chatflow export", () => {
    expect(
      detectSource({ nodes: [{ id: "chatOpenAI_0", type: "customNode", data: { name: "chatOpenAI" } }], edges: [] })
    ).toBe("flowise");
  });

  it("detects a stored Flowise chatflow record by its flowData string", () => {
    const flowData = JSON.stringify({ nodes: [{ id: "n1", data: { name: "toolAgent" } }], edges: [] });
    expect(detectSource({ id: "f1", name: "My flow", flowData })).toBe("flowise");
  });

  it("prefers Flowise over the OpenAI shapes when nodes are present", () => {
    expect(detectSource({ nodes: [{ id: "n", data: {} }], handoffs: [] })).toBe("flowise");
  });

  it("returns null when ambiguous rather than guessing", () => {
    expect(detectSource({ name: "x", instructions: "y", model: "gpt-4o" })).toBeNull();
    expect(detectSource({})).toBeNull();
  });

  it("does not treat an empty or malformed node list as Flowise", () => {
    expect(detectSource({ nodes: [], edges: [] })).toBeNull();
    expect(detectSource({ nodes: ["a", 3, null] })).toBeNull();
    expect(detectSource({ flowData: "not json", name: "x" })).toBeNull();
  });

  it("never throws on non-object input", () => {
    expect(detectSource(null as any)).toBeNull();
    expect(detectSource([] as any)).toBeNull();
  });
});

describe("detectSourceFromText", () => {
  it("returns null for unparsable text and the source for a parsable export", () => {
    expect(detectSourceFromText("{ nope")).toBeNull();
    expect(detectSourceFromText('{"object":"assistant"}')).toBe("openai-assistant");
  });
});

describe("size checks", () => {
  it("counts UTF-8 bytes, not UTF-16 units", () => {
    expect(utf8ByteLength("abc")).toBe(3);
    expect(utf8ByteLength("é")).toBe(2);
    expect(utf8ByteLength("€")).toBe(3);
    expect(utf8ByteLength("\u{1F600}")).toBe(4);
  });

  it("allows exactly the limit and refuses one byte more", () => {
    expect(exceedsImportLimit("a".repeat(MAX_IMPORT_BYTES))).toBe(false);
    expect(exceedsImportLimit("a".repeat(MAX_IMPORT_BYTES + 1))).toBe(true);
  });

  it("refuses multi-byte text whose character count is under the limit but byte count is over", () => {
    const text = "€".repeat(Math.ceil(MAX_IMPORT_BYTES / 3) + 1);
    expect(text.length).toBeLessThan(MAX_IMPORT_BYTES);
    expect(exceedsImportLimit(text)).toBe(true);
  });

  it("honours a custom limit", () => {
    expect(exceedsImportLimit("hello", 4)).toBe(true);
    expect(exceedsImportLimit("hello", 5)).toBe(false);
  });
});

describe("buildEnvelope", () => {
  const payload = { name: "x" };

  it("sends the source and the payload object, and no overrides without a name", () => {
    expect(buildEnvelope("flowise", payload)).toEqual({ source: "flowise", payload });
    expect(buildEnvelope("auto", payload, "   ")).toEqual({ source: "auto", payload });
  });

  it("sends a trimmed name as overrides.name", () => {
    expect(buildEnvelope("auto", payload, "  My agent ")).toEqual({
      source: "auto",
      payload,
      overrides: { name: "My agent" },
    });
  });

  it("caps the name at the server's sanitised length", () => {
    const env = buildEnvelope("auto", payload, "n".repeat(MAX_NAME_LENGTH + 40));
    expect(env.overrides?.name).toHaveLength(MAX_NAME_LENGTH);
  });

  it("falls back to auto for an unknown source value", () => {
    expect(buildEnvelope("bogus" as any, payload).source).toBe("auto");
  });
});
