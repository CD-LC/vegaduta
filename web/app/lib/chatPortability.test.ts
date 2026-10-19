import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";
import en from "../../messages/en.json";
import {
  EXPORT_FORMATS,
  defaultSelection,
  exportFallbackName,
  filenameFromContentDisposition,
  formatBytes,
  groupSkipped,
  isSelectable,
  roomLeft,
  selectNone,
  selectedIds,
  selectionCap,
  shortDate,
  skipReasonKey,
  sourceLabelKey,
  toggleSelection,
  validateUpload,
  type ChatImportCapacity,
  type ChatPreviewConversation,
} from "./chatPortability";

function conv(id: string, over: Partial<ChatPreviewConversation> = {}): ChatPreviewConversation {
  return { id, source: "chatgpt", title: `Chat ${id}`, messageCount: 2, snippet: "hi", alreadyImported: false, truncated: false, ...over };
}

const CONVS: ChatPreviewConversation[] = [conv("a"), conv("b", { alreadyImported: true }), conv("c"), conv("d")];
const CAPACITY: ChatImportCapacity = { storedChats: 10, maxStoredChats: 2000, maxPerImport: 500 };

describe("validateUpload", () => {
  it("accepts a zip or json within the size limit", () => {
    expect(validateUpload({ name: "export.zip", size: 1000 }, 25_000_000)).toBeNull();
    expect(validateUpload({ name: "conversations.JSON", size: 1000 }, 25_000_000)).toBeNull();
  });

  it("refuses empty, wrong-type and oversized files", () => {
    expect(validateUpload(null, 100)).toBe("empty");
    expect(validateUpload({ name: "a.json", size: 0 }, 100)).toBe("empty");
    expect(validateUpload({ name: "notes.txt", size: 10 }, 100)).toBe("wrongType");
    expect(validateUpload({ name: "a.zip", size: 101 }, 100)).toBe("tooLarge");
  });
});

describe("formatBytes", () => {
  it("scales units", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(2048)).toBe("2.0 KB");
    expect(formatBytes(25 * 1024 * 1024)).toBe("25 MB");
    expect(formatBytes(-1)).toBe("0 B");
    expect(formatBytes(NaN)).toBe("0 B");
  });
});

describe("selection", () => {
  it("only new conversations are selectable and selected by default", () => {
    expect(isSelectable(CONVS[0])).toBe(true);
    expect(isSelectable(CONVS[1])).toBe(false);
    expect([...defaultSelection(CONVS, 500)]).toEqual(["a", "c", "d"]);
  });

  it("the default selection stops at the cap", () => {
    expect([...defaultSelection(CONVS, 2)]).toEqual(["a", "c"]);
    expect([...defaultSelection(CONVS, 0)]).toEqual([]);
    expect([...defaultSelection(null, 5)]).toEqual([]);
  });

  it("toggling adds up to the cap and always removes", () => {
    let sel = selectNone();
    sel = toggleSelection(sel, "a", 2);
    sel = toggleSelection(sel, "c", 2);
    sel = toggleSelection(sel, "d", 2); // over the cap: ignored
    expect([...sel]).toEqual(["a", "c"]);
    sel = toggleSelection(sel, "a", 2);
    expect([...sel]).toEqual(["c"]);
  });

  it("does not mutate the previous selection", () => {
    const before = new Set<string>(["a"]);
    toggleSelection(before, "c", 5);
    expect([...before]).toEqual(["a"]);
  });

  it("the ids sent are the ticked, importable ones in display order", () => {
    expect(selectedIds(CONVS, new Set(["d", "b", "a"]))).toEqual(["a", "d"]);
    expect(selectedIds(null, new Set(["a"]))).toEqual([]);
  });
});

describe("capacity", () => {
  it("room is what is left of the stored-chat limit and the cap is the smaller of that and the per-import limit", () => {
    expect(roomLeft(CAPACITY)).toBe(1990);
    expect(selectionCap(CAPACITY)).toBe(500);
    expect(selectionCap({ storedChats: 1900, maxStoredChats: 2000, maxPerImport: 500 })).toBe(100);
    expect(selectionCap({ storedChats: 2000, maxStoredChats: 2000, maxPerImport: 500 })).toBe(0);
    expect(selectionCap({ storedChats: 5000, maxStoredChats: 2000, maxPerImport: 500 })).toBe(0);
    expect(roomLeft(null)).toBe(0);
    expect(selectionCap(undefined)).toBe(0);
  });
});

describe("result helpers", () => {
  it("groups skipped chats biggest first, ties by reason", () => {
    const groups = groupSkipped([
      { id: "1", title: "", reason: "not_found" },
      { id: "2", title: "", reason: "duplicate" },
      { id: "3", title: "", reason: "duplicate" },
      { id: "4", title: "", reason: "limit_reached" },
    ]);
    expect(groups).toEqual([
      { reason: "duplicate", count: 2 },
      { reason: "limit_reached", count: 1 },
      { reason: "not_found", count: 1 },
    ]);
    expect(groupSkipped(undefined)).toEqual([]);
  });

  it("maps skip reasons and sources to i18n keys, unknown ones to the generic key", () => {
    expect(skipReasonKey("duplicate")).toBe("skipReason_duplicate");
    expect(skipReasonKey("limit_reached")).toBe("skipReason_limit_reached");
    expect(skipReasonKey("surprise")).toBe("skipReason_other");
    expect(skipReasonKey(null)).toBe("skipReason_other");
    expect(sourceLabelKey("chatgpt")).toBe("sourceChatgpt");
    expect(sourceLabelKey("claude")).toBe("sourceClaude");
    expect(sourceLabelKey("mixed")).toBe("sourceOther");
  });
});

describe("filenameFromContentDisposition", () => {
  it("reads a quoted filename", () => {
    expect(filenameFromContentDisposition('attachment; filename="Trip-planning-20261004.md"', "x.md")).toBe("Trip-planning-20261004.md");
  });

  it("reads an RFC 5987 filename*", () => {
    expect(filenameFromContentDisposition("attachment; filename*=UTF-8''caf%C3%A9.txt", "x.txt")).toBe("café.txt");
  });

  it("drops any path part and falls back when there is nothing usable", () => {
    expect(filenameFromContentDisposition('attachment; filename="../../etc/passwd"', "x.md")).toBe("passwd");
    expect(filenameFromContentDisposition('attachment; filename="..\\\\evil.md"', "x.md")).toBe("evil.md");
    expect(filenameFromContentDisposition(null, "fallback.json")).toBe("fallback.json");
    expect(filenameFromContentDisposition("inline", "fallback.json")).toBe("fallback.json");
  });

  it("builds fallback names", () => {
    expect(exportFallbackName("md")).toBe("chat.md");
    expect(exportFallbackName("json", "imported-chats")).toBe("imported-chats.json");
  });
});

describe("shortDate", () => {
  it("is empty for missing or invalid input", () => {
    expect(shortDate(null)).toBe("");
    expect(shortDate("not a date")).toBe("");
    expect(shortDate("2026-10-04T08:00:00Z", "en-US")).not.toBe("");
  });
});

describe("i18n coverage (chatPortability namespace)", () => {
  const ns = (en as any).chatPortability as Record<string, string>;
  const KEY_RE = /\bt\(\s*"([A-Za-z0-9_]+)"/g;

  function usedKeys(relative: string): string[] {
    const source = fs.readFileSync(path.resolve(process.cwd(), relative), "utf8");
    return [...source.matchAll(KEY_RE)].map((m) => m[1]);
  }

  it("has the namespace", () => {
    expect(typeof ns).toBe("object");
    expect(Object.keys(ns).length).toBeGreaterThan(20);
  });

  it("every key the helpers can return exists", () => {
    const keys = [
      ...EXPORT_FORMATS.map((f) => f.labelKey),
      skipReasonKey("duplicate"),
      skipReasonKey("limit_reached"),
      skipReasonKey("not_found"),
      skipReasonKey("other"),
      sourceLabelKey("chatgpt"),
      sourceLabelKey("claude"),
      sourceLabelKey("x"),
    ];
    for (const key of keys) expect(ns[key], key).toBeTypeOf("string");
  });

  it("every literal t(\"...\") key used by the import card and the export menu exists", () => {
    const files = ["app/memory/ImportChatsCard.tsx", "app/components/ChatExportMenu.tsx"];
    const missing: string[] = [];
    let total = 0;
    for (const file of files) {
      for (const key of usedKeys(file)) {
        total++;
        if (typeof ns[key] !== "string") missing.push(`${file}: ${key}`);
      }
    }
    expect(total).toBeGreaterThan(30);
    expect(missing).toEqual([]);
  });

  it("every placeholder a component passes is present in the English string", () => {
    expect(ns.fileSelected).toContain("{name}");
    expect(ns.fileSelected).toContain("{size}");
    expect(ns.showingFirst).toContain("{shown}");
    expect(ns.showingFirst).toContain("{total}");
    expect(ns.doneBody).toContain("{imported}");
    expect(ns.skippedGroup).toContain("{reason}");
    expect(ns.memSaved).toContain("{saved}");
    expect(ns.attachmentsLine).toContain("{names}");
  });
});
