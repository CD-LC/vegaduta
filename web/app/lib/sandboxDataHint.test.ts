// D5: the composer hint that explains why data-file analysis is unavailable.
// Strings below are the exact sentences core can send (SandboxFileRegistry).

import { describe, it, expect } from "vitest";
import {
  isSandboxHintDismissed,
  readSandboxDataUnavailableReason,
  rememberSandboxHintDismissed,
  sandboxDataHint,
  sandboxDataHintTarget,
} from "./sandboxDataHint";

const CAPABILITY_OFF = "Turn on Code execution for this agent (Capabilities) to analyse attached files.";
const NO_E2B =
  "File analysis needs this workspace to connect E2B (API key and code-execution template) under Integrations; until then attached data files cannot be analysed.";
const PLATFORM_NOT_READY =
  "File analysis is not available yet for this workspace. You can connect your own E2B key (API key and code-execution template) under Integrations to use it now.";
const CHECK_FAILED = "File analysis is temporarily unavailable for this workspace.";

function agent(modalities: any, id = "a-1") {
  return { id, modalities };
}

describe("readSandboxDataUnavailableReason", () => {
  it("is null when the key is absent (flag off, or an older core)", () => {
    expect(readSandboxDataUnavailableReason(agent({ image: true, sandboxData: false }))).toBeNull();
    expect(readSandboxDataUnavailableReason({ id: "a-1" })).toBeNull();
    expect(readSandboxDataUnavailableReason(null)).toBeNull();
  });

  it("is null for null, blank and non-string values", () => {
    expect(readSandboxDataUnavailableReason(agent({ sandboxDataUnavailableReason: null }))).toBeNull();
    expect(readSandboxDataUnavailableReason(agent({ sandboxDataUnavailableReason: "   " }))).toBeNull();
    expect(readSandboxDataUnavailableReason(agent({ sandboxDataUnavailableReason: 42 }))).toBeNull();
  });

  it("ignores a reason when the sandbox is actually available", () => {
    expect(
      readSandboxDataUnavailableReason(agent({ sandboxData: true, sandboxDataUnavailableReason: CAPABILITY_OFF }))
    ).toBeNull();
  });

  it("returns the trimmed sentence otherwise", () => {
    expect(
      readSandboxDataUnavailableReason(agent({ sandboxData: false, sandboxDataUnavailableReason: `  ${NO_E2B} ` }))
    ).toBe(NO_E2B);
  });
});

describe("sandboxDataHintTarget", () => {
  it("sends 'Code execution is off' to the agent's Capabilities", () => {
    expect(sandboxDataHintTarget(CAPABILITY_OFF)).toBe("capabilities");
  });

  it("sends the E2B setup reasons to Providers", () => {
    expect(sandboxDataHintTarget(NO_E2B)).toBe("providers");
    expect(sandboxDataHintTarget(PLATFORM_NOT_READY)).toBe("providers");
  });

  it("offers no link for a temporary failure or a sentence it does not recognise", () => {
    // E2B may already be connected - "Connect E2B" would be wrong advice.
    expect(sandboxDataHintTarget(CHECK_FAILED)).toBe("none");
    expect(sandboxDataHintTarget("E2B is temporarily unreachable.")).toBe("none");
    expect(sandboxDataHintTarget("Something new the server says.")).toBe("none");
  });
});

describe("dismissal memory", () => {
  function fakeStore() {
    const data = new Map<string, string>();
    return {
      getItem: (k: string) => data.get(k) ?? null,
      setItem: (k: string, v: string): void => { data.set(k, v); },
    };
  }

  it("remembers a dismissal per agent and reason", () => {
    const store = fakeStore();
    expect(isSandboxHintDismissed("a-1", CAPABILITY_OFF, store)).toBe(false);
    rememberSandboxHintDismissed("a-1", CAPABILITY_OFF, store);
    expect(isSandboxHintDismissed("a-1", CAPABILITY_OFF, store)).toBe(true);
    // Another agent, or a new reason for the same agent, still shows.
    expect(isSandboxHintDismissed("a-2", CAPABILITY_OFF, store)).toBe(false);
    expect(isSandboxHintDismissed("a-1", NO_E2B, store)).toBe(false);
  });

  it("never throws when storage is missing or blocked", () => {
    const blocked = {
      getItem: () => {
        throw new Error("denied");
      },
      setItem: () => {
        throw new Error("denied");
      },
    };
    expect(isSandboxHintDismissed("a-1", NO_E2B, null)).toBe(false);
    expect(isSandboxHintDismissed("a-1", NO_E2B, blocked)).toBe(false);
    expect(() => rememberSandboxHintDismissed("a-1", NO_E2B, blocked)).not.toThrow();
    expect(() => rememberSandboxHintDismissed("a-1", NO_E2B, null)).not.toThrow();
  });
});

describe("sandboxDataHint", () => {
  it("is null when there is nothing to explain", () => {
    expect(sandboxDataHint(agent({ sandboxData: false }))).toBeNull();
    expect(sandboxDataHint(undefined)).toBeNull();
  });

  it("links the capability reason to this agent on /agents", () => {
    expect(sandboxDataHint(agent({ sandboxDataUnavailableReason: CAPABILITY_OFF }, "id with/space"))).toEqual({
      reason: CAPABILITY_OFF,
      target: "capabilities",
      href: "/agents?agentId=id%20with%2Fspace",
    });
  });

  it("falls back to /agents when the agent has no id", () => {
    expect(sandboxDataHint({ modalities: { sandboxDataUnavailableReason: CAPABILITY_OFF } })?.href).toBe("/agents");
  });

  it("links workspace reasons to /providers and shows the sentence verbatim", () => {
    expect(sandboxDataHint(agent({ sandboxDataUnavailableReason: PLATFORM_NOT_READY }))).toEqual({
      reason: PLATFORM_NOT_READY,
      target: "providers",
      href: "/providers",
    });
  });

  it("shows a temporary failure with no link at all", () => {
    expect(sandboxDataHint(agent({ sandboxDataUnavailableReason: CHECK_FAILED }))).toEqual({
      reason: CHECK_FAILED,
      target: "none",
      href: null,
    });
  });
});
