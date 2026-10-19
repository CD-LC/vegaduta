import { describe, expect, it } from "vitest";
import { extensionOf, fileBadge, formatBytes, initialsOf, highlight } from "./knowledgeFormat";

describe("knowledgeFormat", () => {
  it("recognises supported extensions case-insensitively", () => {
    expect(extensionOf("Report.PDF")).toBe(".pdf");
    expect(extensionOf("clip.mp4")).toBe(".mp4");
    expect(extensionOf("notes.exe")).toBeNull();
  });

  it("maps extensions to badge labels", () => {
    expect(fileBadge(".pdf").label).toBe("PDF");
    expect(fileBadge(".docx").label).toBe("DOC");
    expect(fileBadge(".jpg").label).toBe("IMG");
    expect(fileBadge(".wav").label).toBe("AUD");
    expect(fileBadge(".zip").label).toBe("FILE");
  });

  it("formats bytes with a KB floor", () => {
    expect(formatBytes(0)).toBe("0 KB");
    expect(formatBytes(10)).toBe("1 KB");
  });

  it("derives initials", () => {
    expect(initialsOf("Ada Lovelace")).toBe("AL");
    expect(initialsOf(null)).toBe("?");
  });

  it("highlights case-insensitive matches without regex injection", () => {
    expect(highlight("no match", "")).toBe("no match");
    expect(Array.isArray(highlight("Hello hello", "hello"))).toBe(true);
    expect(() => highlight("a (b", "(")).not.toThrow();
  });
});
