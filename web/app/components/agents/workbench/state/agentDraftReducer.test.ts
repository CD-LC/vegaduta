import { describe, expect, it } from "vitest";
import {
  agentDraftReducer,
  draftStatus,
  initialDraftState,
  DEFAULT_SYSTEM_PROMPT,
  type AgentDraftState,
} from "./agentDraftReducer";

const start = (): AgentDraftState => initialDraftState("OPENAI");

describe("agentDraftReducer", () => {
  it("starts clean with the documented defaults", () => {
    const s = start();
    expect(draftStatus(s)).toBe("clean");
    expect(s.fields.providerName).toBe("OPENAI");
    expect(s.fields.systemPrompt).toBe(DEFAULT_SYSTEM_PROMPT);
    expect(s.fields.newAgentMode).toBe("SIMPLE");
    expect(s.fields.newAgentTuningMode).toBe("MANUAL");
    expect(initialDraftState().fields.providerName).toBe("ANTHROPIC");
  });

  it("becomes dirty on an edit and clean again when the edit is undone", () => {
    let s = agentDraftReducer(start(), { type: "field", key: "name", value: "Bot" });
    expect(draftStatus(s)).toBe("dirty");
    s = agentDraftReducer(s, { type: "field", key: "name", value: "" });
    expect(draftStatus(s)).toBe("clean");
  });

  it("supports functional updaters and returns the same state for a no-op write", () => {
    let s = agentDraftReducer(start(), { type: "field", key: "name", value: "ab" });
    s = agentDraftReducer(s, { type: "field", key: "name", value: (c) => `${c as string}c` });
    expect(s.fields.name).toBe("abc");
    expect(agentDraftReducer(s, { type: "field", key: "name", value: "abc" })).toBe(s);
  });

  it("walks clean -> dirty -> saving -> error -> dirty", () => {
    let s = agentDraftReducer(start(), { type: "field", key: "name", value: "Bot" });
    expect(draftStatus(s)).toBe("dirty");
    s = agentDraftReducer(s, { type: "saving", value: true });
    expect(draftStatus(s)).toBe("saving");
    s = agentDraftReducer(s, { type: "error", value: "boom" });
    // saving wins over error until the call settles
    expect(draftStatus(s)).toBe("saving");
    s = agentDraftReducer(s, { type: "saving", value: false });
    expect(draftStatus(s)).toBe("error");
    s = agentDraftReducer(s, { type: "error", value: null });
    expect(draftStatus(s)).toBe("dirty");
  });

  it("tracks the model picker separately from the fields", () => {
    const s = agentDraftReducer(start(), { type: "picker", key: "useCustom", value: true });
    expect(s.picker.useCustom).toBe(true);
    expect(draftStatus(s)).toBe("clean");
  });

  it("reset returns to a blank draft but keeps an in-flight save and the fetch flags", () => {
    let s = start();
    s = agentDraftReducer(s, { type: "field", key: "name", value: "Bot" });
    s = agentDraftReducer(s, { type: "field", key: "voiceModelProvider", value: "SARVAM" });
    s = agentDraftReducer(s, { type: "picker", key: "catalog", value: [{ id: "m" } as never] });
    s = agentDraftReducer(s, { type: "picker", key: "useCustom", value: true });
    s = agentDraftReducer(s, { type: "picker", key: "loading", value: true });
    s = agentDraftReducer(s, { type: "error", value: "old" });
    s = agentDraftReducer(s, { type: "success", value: "ok" });
    s = agentDraftReducer(s, { type: "saving", value: true });
    const r = agentDraftReducer(s, { type: "reset", providerName: "GEMINI" });
    expect(r.fields.name).toBe("");
    expect(r.fields.voiceModelProvider).toBeNull();
    expect(r.fields.providerName).toBe("GEMINI");
    expect(r.picker.catalog).toEqual([]);
    expect(r.picker.useCustom).toBe(false);
    expect(r.picker.loading).toBe(true);
    expect(r.error).toBeNull();
    expect(r.success).toBeNull();
    expect(r.saving).toBe(true);
    expect(draftStatus(agentDraftReducer(r, { type: "saving", value: false }))).toBe("clean");
  });
});
