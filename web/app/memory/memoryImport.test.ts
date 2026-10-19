import { describe, expect, it } from "vitest";
import en from "../../messages/en.json";
import {
  IMPORT_REASON_CODES,
  IMPORT_SHARED_TARGET,
  IMPORT_SOURCES,
  buildImportPrompt,
  buildImportRequest,
  defaultSelection,
  groupSkipped,
  importReasonKey,
  importSourceLabel,
  isImportSource,
  isSelectable,
  overflow,
  roomFor,
  selectAll,
  selectNone,
  selectedFacts,
  toggleSelection,
  type ImportPreviewFact,
} from "./memoryImport";
import type { MemoryFact } from "./memoryTypes";

const FACTS: ImportPreviewFact[] = [
  { id: 0, text: "Lives in Pune", scope: "shared" },
  { id: 1, text: "Prefers Kotlin", scope: "shared", duplicateOf: "Prefers Kotlin." },
  { id: 2, text: "[hidden]", rejectedReason: "secret" },
  { id: 3, text: "Likes green tea", scope: "shared" },
];

describe("buildImportPrompt", () => {
  it("asks for one fact per line, no commentary and nothing sensitive", () => {
    for (const s of IMPORT_SOURCES) {
      const p = buildImportPrompt(s.id);
      expect(p).toContain("one fact per line");
      expect(p).toContain('each starting with "- "');
      expect(p).toContain("No headings and no commentary");
      expect(p).toMatch(/passwords, API keys, bank or card numbers, government ID numbers/);
      expect(p).toContain("reply with the single word: None");
      expect(p).not.toContain("\r");
      expect(p).toBe(p.trim());
    }
  });

  it("differs per assistant only in the sentence that says where to look", () => {
    const chatgpt = buildImportPrompt("chatgpt");
    const claude = buildImportPrompt("claude");
    const gemini = buildImportPrompt("gemini");
    expect(new Set([chatgpt, claude, gemini]).size).toBe(3);
    expect(chatgpt).toContain("saved memories");
    expect(claude).toContain("your memory of me");
    expect(gemini).toContain("information you have saved");
    const lines = (p: string) => p.split("\n");
    expect(lines(chatgpt).length).toBe(lines(claude).length);
  });

  it("knows the three sources", () => {
    expect(IMPORT_SOURCES.map((s) => s.id)).toEqual(["chatgpt", "claude", "gemini"]);
    expect(isImportSource("claude")).toBe(true);
    expect(isImportSource("other")).toBe(false);
    expect(importSourceLabel("chatgpt")).toBe("ChatGPT");
  });
});

describe("selection", () => {
  it("pre-selects only new, accepted lines", () => {
    expect([...defaultSelection(FACTS)]).toEqual([0, 3]);
    expect(isSelectable(FACTS[1])).toBe(false);
    expect(isSelectable(FACTS[2])).toBe(false);
    expect([...defaultSelection(null)]).toEqual([]);
  });

  it("toggles without mutating the previous selection", () => {
    const before = defaultSelection(FACTS);
    const after = toggleSelection(before, 0);
    expect([...before]).toEqual([0, 3]);
    expect([...after]).toEqual([3]);
    expect([...toggleSelection(after, 0)].sort()).toEqual([0, 3]);
  });

  it("select all never ticks a locked line, select none clears", () => {
    expect([...selectAll(FACTS)]).toEqual([0, 3]);
    expect(selectNone().size).toBe(0);
  });

  it("selectedFacts ignores ticked ids that are locked or unknown, and keeps the order shown", () => {
    const ticked = new Set<number>([3, 1, 2, 99, 0]);
    expect(selectedFacts(FACTS, ticked).map((f) => f.id)).toEqual([0, 3]);
    expect(selectedFacts(undefined, ticked)).toEqual([]);
  });
});

describe("buildImportRequest", () => {
  it("sends shared facts without an agent id", () => {
    const req = buildImportRequest(FACTS, defaultSelection(FACTS), IMPORT_SHARED_TARGET, "chatgpt");
    expect(req).toEqual({
      source: "chatgpt",
      facts: [
        { text: "Lives in Pune", scope: "shared" },
        { text: "Likes green tea", scope: "shared" },
      ],
    });
  });

  it("sends private facts with the chosen agent", () => {
    const req = buildImportRequest(FACTS, new Set([3]), "agent-1", "gemini");
    expect(req.source).toBe("gemini");
    expect(req.facts).toEqual([{ text: "Likes green tea", scope: "agent", agentId: "agent-1" }]);
  });

  it("sends nothing when nothing is ticked", () => {
    expect(buildImportRequest(FACTS, selectNone(), IMPORT_SHARED_TARGET, "claude").facts).toEqual([]);
  });
});

describe("room", () => {
  const capacity = { sharedRemaining: 3, perAgentLimit: 25 };
  const existing: MemoryFact[] = [
    { id: "a", scope: "agent", agentId: "agent-1", content: "x" },
    { id: "b", scope: "agent", agentId: "agent-1", content: "y" },
    { id: "c", scope: "agent", agentId: "agent-2", content: "z" },
    { id: "d", scope: "shared", agentId: null, content: "w" },
  ];

  it("uses the server's shared room and the per-agent limit minus what the agent holds", () => {
    expect(roomFor(IMPORT_SHARED_TARGET, capacity, existing)).toBe(3);
    expect(roomFor("agent-1", capacity, existing)).toBe(23);
    expect(roomFor("agent-2", capacity, existing)).toBe(24);
    expect(roomFor("agent-3", { sharedRemaining: 0, perAgentLimit: 1 }, [])).toBe(1);
  });

  it("never goes negative and is unknown without capacity", () => {
    expect(roomFor("agent-1", { sharedRemaining: -4, perAgentLimit: 1 }, existing)).toBe(0);
    expect(roomFor(IMPORT_SHARED_TARGET, { sharedRemaining: -4, perAgentLimit: 1 }, existing)).toBe(0);
    expect(roomFor(IMPORT_SHARED_TARGET, undefined, existing)).toBeNull();
    expect(roomFor(IMPORT_SHARED_TARGET, null, existing)).toBeNull();
  });

  it("overflow is how many selected facts will not fit", () => {
    expect(overflow(5, 3)).toBe(2);
    expect(overflow(2, 3)).toBe(0);
    expect(overflow(5, 0)).toBe(5);
    expect(overflow(5, null)).toBe(0);
  });
});

describe("groupSkipped", () => {
  it("groups by reason, biggest first, ties by code", () => {
    const groups = groupSkipped([
      { text: "a", reason: "limit_reached" },
      { text: "b", reason: "duplicate" },
      { text: "c", reason: "limit_reached" },
      { text: "d", reason: "error" },
    ]);
    expect(groups).toEqual([
      { reason: "limit_reached", count: 2 },
      { reason: "duplicate", count: 1 },
      { reason: "error", count: 1 },
    ]);
    expect(groupSkipped(undefined)).toEqual([]);
  });
});

describe("reason labels", () => {
  const page = (en as any).memoryPage as Record<string, string>;

  it("every reason code core can return has an English string", () => {
    for (const code of IMPORT_REASON_CODES) {
      expect(importReasonKey(code)).toBe(`importReason_${code}`);
      expect(typeof page[importReasonKey(code)]).toBe("string");
    }
  });

  it("an unknown or missing code reads as the generic one, which exists", () => {
    expect(importReasonKey("something_new")).toBe("importReason_other");
    expect(importReasonKey(null)).toBe("importReason_other");
    expect(typeof page.importReason_other).toBe("string");
  });

  it("every other importXxx key the card uses exists", () => {
    const keys = [
      "importHeading", "importIntro", "importStart", "importClose", "importBack", "importStep1Title", "importStep1Body",
      "importSourceLabel", "importPromptLabel", "importCopyPrompt", "importCopied", "importCopyFailed", "importStep2Title",
      "importStep2Body", "importSensitiveWarning", "importPastePlaceholder", "importReviewButton", "importReviewing",
      "importStep3Title", "importStats", "importNothingFound", "importSelectAll", "importSelectNone", "importSelectedCount",
      "importDuplicate", "importDuplicateOf", "importRoomWarning", "importRoomFull", "importButton", "importing",
      "importDoneTitle", "importDoneBody", "importSkippedGroup", "importDoneDelete", "importAnother",
    ];
    for (const key of keys) expect(`${key}:${typeof page[key]}`).toBe(`${key}:string`);
  });
});
