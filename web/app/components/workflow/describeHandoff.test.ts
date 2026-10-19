import { describe, expect, it } from "vitest";
import {
  DESCRIBE_HANDOFF_PREFIX,
  DESCRIBE_NAME_MAX_CHARS,
  DESCRIBE_PROMPT_MAX_CHARS,
  clearDescribeHandoff,
  deriveWorkflowName,
  normalizeDescribePrompt,
  readDescribeHandoff,
  takeDescribeHandoff,
  writeDescribeHandoff,
} from "./describeHandoff";

function memoryStorage() {
  const map = new Map<string, string>();
  return {
    map,
    getItem: (k: string) => (map.has(k) ? map.get(k)! : null),
    setItem: (k: string, v: string) => {
      map.set(k, v);
    },
    removeItem: (k: string) => {
      map.delete(k);
    },
  };
}

const throwingStorage = {
  getItem: () => {
    throw new Error("blocked");
  },
  setItem: () => {
    throw new Error("quota");
  },
  removeItem: () => {
    throw new Error("blocked");
  },
};

describe("normalizeDescribePrompt", () => {
  it("trims and collapses whitespace, including newlines", () => {
    expect(normalizeDescribePrompt("  every   Monday\n\tsend a digest  ")).toBe("every Monday send a digest");
  });

  it("returns an empty string for non-strings", () => {
    expect(normalizeDescribePrompt(null)).toBe("");
    expect(normalizeDescribePrompt(undefined)).toBe("");
    expect(normalizeDescribePrompt(42)).toBe("");
  });

  it("caps the length", () => {
    expect(normalizeDescribePrompt("a".repeat(DESCRIBE_PROMPT_MAX_CHARS + 50))).toHaveLength(DESCRIBE_PROMPT_MAX_CHARS);
  });
});

describe("deriveWorkflowName", () => {
  it("uses the first six words and capitalises the first letter", () => {
    expect(deriveWorkflowName("every monday pull unpaid invoices and whatsapp a reminder")).toBe(
      "Every monday pull unpaid invoices and",
    );
  });

  it("keeps a short prompt whole", () => {
    expect(deriveWorkflowName("Weekly sales digest")).toBe("Weekly sales digest");
  });

  it("strips trailing punctuation left by the word cut", () => {
    expect(deriveWorkflowName("When a lead arrives, score it, then reply")).toBe("When a lead arrives, score it");
    expect(deriveWorkflowName("Triage support tickets.")).toBe("Triage support tickets");
  });

  it("honours a custom word count", () => {
    expect(deriveWorkflowName("one two three four", 2)).toBe("One two");
  });

  it("caps very long words inside the name column limit", () => {
    const name = deriveWorkflowName("x".repeat(500));
    expect(name.length).toBe(DESCRIBE_NAME_MAX_CHARS);
  });

  it("returns an empty string when there is nothing usable", () => {
    expect(deriveWorkflowName("")).toBe("");
    expect(deriveWorkflowName("   \n ")).toBe("");
    expect(deriveWorkflowName("...")).toBe("");
    expect(deriveWorkflowName(undefined)).toBe("");
  });
});

describe("describe handoff storage", () => {
  it("writes under the documented key and reads it back", () => {
    const s = memoryStorage();
    expect(writeDescribeHandoff("wf-1", "  send a   weekly digest ", s)).toBe(true);
    expect(s.map.get(`${DESCRIBE_HANDOFF_PREFIX}wf-1`)).toBe("send a weekly digest");
    expect(readDescribeHandoff("wf-1", s)).toBe("send a weekly digest");
    // read does not consume
    expect(readDescribeHandoff("wf-1", s)).toBe("send a weekly digest");
  });

  it("is scoped per workflow id", () => {
    const s = memoryStorage();
    writeDescribeHandoff("wf-1", "one", s);
    expect(readDescribeHandoff("wf-2", s)).toBeNull();
  });

  it("refuses an empty prompt or id", () => {
    const s = memoryStorage();
    expect(writeDescribeHandoff("wf-1", "   ", s)).toBe(false);
    expect(writeDescribeHandoff("", "something", s)).toBe(false);
    expect(s.map.size).toBe(0);
  });

  it("clear removes the entry", () => {
    const s = memoryStorage();
    writeDescribeHandoff("wf-1", "one", s);
    clearDescribeHandoff("wf-1", s);
    expect(readDescribeHandoff("wf-1", s)).toBeNull();
  });

  it("take returns the prompt once, then null", () => {
    const s = memoryStorage();
    writeDescribeHandoff("wf-1", "one", s);
    expect(takeDescribeHandoff("wf-1", s)).toBe("one");
    expect(takeDescribeHandoff("wf-1", s)).toBeNull();
  });

  it("degrades quietly when storage is missing or throws", () => {
    expect(writeDescribeHandoff("wf-1", "one", null)).toBe(false);
    expect(readDescribeHandoff("wf-1", null)).toBeNull();
    expect(() => clearDescribeHandoff("wf-1", null)).not.toThrow();

    expect(writeDescribeHandoff("wf-1", "one", throwingStorage)).toBe(false);
    expect(readDescribeHandoff("wf-1", throwingStorage)).toBeNull();
    expect(() => clearDescribeHandoff("wf-1", throwingStorage)).not.toThrow();
    expect(takeDescribeHandoff("wf-1", throwingStorage)).toBeNull();
  });

  it("uses window.sessionStorage by default", () => {
    window.sessionStorage.clear();
    expect(writeDescribeHandoff("wf-default", "hello there")).toBe(true);
    expect(window.sessionStorage.getItem(`${DESCRIBE_HANDOFF_PREFIX}wf-default`)).toBe("hello there");
    expect(takeDescribeHandoff("wf-default")).toBe("hello there");
    expect(window.sessionStorage.getItem(`${DESCRIBE_HANDOFF_PREFIX}wf-default`)).toBeNull();
  });
});
