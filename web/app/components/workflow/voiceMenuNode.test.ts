import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";
import {
  NODE_DEFS,
  VOICE_MENU_NO_MATCH_HANDLE,
  VOICE_MENU_TIMEOUT_HANDLE,
  nodeDef,
  nodeDescriptionKey,
  nodeLabelKey,
  voiceMenuPorts,
} from "./nodeDefs";

const t = (key: string) => key;

describe("voice_menu palette entry", () => {
  it("is registered, supported, and grouped under Logic", () => {
    const def = NODE_DEFS.find((d) => d.type === "voice_menu");
    expect(def).toBeDefined();
    expect(def!.supported).toBe(true);
    expect(def!.category).toBe("Logic");
    expect(nodeDef("voice_menu")).toBe(def);
  });

  it("has palette label/description strings in en.json (no raw-key rendering)", () => {
    const nodeDefsNs = (en as any).nodeDefs;
    expect(nodeDefsNs[nodeLabelKey("voice_menu")]).toBeTruthy();
    expect(nodeDefsNs[nodeDescriptionKey("voice_menu")]).toBeTruthy();
  });

  it("default config matches the server contract and its own bounds", () => {
    const cfg = nodeDef("voice_menu").defaultConfig;
    // Server clamps timeoutSeconds to 1-60 and maxRetries to 0-5.
    expect(cfg.timeoutSeconds).toBeGreaterThanOrEqual(1);
    expect(cfg.timeoutSeconds).toBeLessThanOrEqual(60);
    expect(cfg.maxRetries).toBeGreaterThanOrEqual(0);
    expect(cfg.maxRetries).toBeLessThanOrEqual(5);
    expect(cfg.finishOnKey).toBe("#");
    // Every default option is a single DTMF character with a non-blank label.
    for (const branch of cfg.branches || []) {
      expect(branch.digit).toMatch(/^[0-9*#]$/);
      expect(branch.label.trim()).not.toBe("");
    }
  });

  it("every en.json key the node's UI reads exists", () => {
    const drawer = (en as any).nodeConfigDrawer;
    for (const key of [
      "voiceMenuHint",
      "voiceMenuPromptLabel",
      "voiceMenuPromptPlaceholder",
      "voiceMenuInvalidPromptLabel",
      "voiceMenuInvalidPromptPlaceholder",
      "voiceMenuNumDigitsLabel",
      "voiceMenuNumDigitsPlaceholder",
      "voiceMenuNumDigitsHint",
      "voiceMenuFinishOnKeyLabel",
      "voiceMenuTimeoutLabel",
      "voiceMenuMaxRetriesLabel",
      "voiceMenuBranchesLabel",
      "voiceMenuBranchDigitPlaceholder",
      "voiceMenuBranchLabelPlaceholder",
      "voiceMenuAddBranch",
      "voiceMenuRemoveBranch",
      "voiceMenuBranchesHint",
    ]) {
      expect(drawer[key], `nodeConfigDrawer.${key}`).toBeTruthy();
    }
    const node = (en as any).workflowNode;
    for (const key of ["voiceMenuOptionCount", "voiceMenuNoPrompt", "voiceMenuNoMatchPortTitle", "voiceMenuTimeoutPortTitle"]) {
      expect(node[key], `workflowNode.${key}`).toBeTruthy();
    }
  });
});

describe("voiceMenuPorts", () => {
  it("emits one port per option in author order, then the two implicit ports", () => {
    const ports = voiceMenuPorts(
      { branches: [{ digit: "1", label: "sales" }, { digit: "2", label: "support" }] },
      t,
    );
    expect(ports.map((p) => p.id)).toEqual(["sales", "support", VOICE_MENU_NO_MATCH_HANDLE, VOICE_MENU_TIMEOUT_HANDLE]);
    expect(ports[0].title).toBe("1 - sales");
  });

  it("always has the implicit ports even with no options configured", () => {
    expect(voiceMenuPorts(undefined, t).map((p) => p.id)).toEqual([
      VOICE_MENU_NO_MATCH_HANDLE,
      VOICE_MENU_TIMEOUT_HANDLE,
    ]);
  });

  it("skips blank, duplicate (case-insensitive) and reserved labels so handle ids stay unique", () => {
    const ports = voiceMenuPorts(
      {
        branches: [
          { digit: "1", label: "sales" },
          { digit: "2", label: "Sales" }, // duplicate of the first, different case
          { digit: "3", label: "  " }, // blank
          { digit: "4", label: "error" }, // reserved: every node's failure port
          { digit: "5", label: "no_match" }, // reserved: implicit port
          { digit: "6", label: "billing" },
        ],
      },
      t,
    );
    const ids = ports.map((p) => p.id);
    expect(ids).toEqual(["sales", "billing", VOICE_MENU_NO_MATCH_HANDLE, VOICE_MENU_TIMEOUT_HANDLE]);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
