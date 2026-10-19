import { describe, expect, it } from "vitest";
import { countByGroup, filterRows, groupOf, previewKind, sortRows, type RowKind } from "./filesLogic";

const r = (kind: RowKind, name: string, createdAt: string, extra: object = {}) => ({ kind, name, createdAt, ...extra });
const rows = [
  r("image", "Sunset", "2026-02-01T00:00:00Z"),
  r("sarvam-audio", "Speech clip", "2026-03-01T00:00:00Z"),
  r("knowledge-document", "handbook.pdf", "2026-01-01T00:00:00Z", { collectionName: "HR" }),
  r("document", "Report.docx", "2026-04-01T00:00:00Z", { detail: "DOCX" }),
];
const names = (xs: { name: string }[]) => xs.map((x) => x.name);

describe("files logic", () => {
  it("groups both audio providers together", () => {
    expect(groupOf("elevenlabs-audio")).toBe("audio");
    expect(groupOf("sarvam-audio")).toBe("audio");
    expect(countByGroup(rows)).toEqual({ all: 4, image: 1, audio: 1, video: 0, document: 1, knowledge: 1 });
  });
  it("filters by group and by text across name, detail and collection", () => {
    expect(names(filterRows(rows, "audio", ""))).toEqual(["Speech clip"]);
    expect(names(filterRows(rows, "all", "hr"))).toEqual(["handbook.pdf"]);
    expect(names(filterRows(rows, "all", "docx"))).toEqual(["Report.docx"]);
    expect(filterRows(rows, "video", "")).toEqual([]);
  });
  it("sorts without mutating", () => {
    expect(names(sortRows(rows, "newest"))[0]).toBe("Report.docx");
    expect(names(sortRows(rows, "oldest"))[0]).toBe("handbook.pdf");
    expect(names(sortRows(rows, "name"))[0]).toBe("handbook.pdf");
    expect(names(sortRows(rows, "type"))[0]).toBe("Speech clip");
    expect(names(rows)[0]).toBe("Sunset");
  });
  it("previews only media", () => {
    expect(previewKind("image")).toBe("image");
    expect(previewKind("sarvam-audio")).toBe("audio");
    expect(previewKind("document")).toBeNull();
    expect(previewKind("knowledge-document")).toBeNull();
  });
});
