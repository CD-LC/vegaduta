import { describe, it, expect } from "vitest";
import {
  artifactIdForTitle,
  contentFingerprint,
  downloadInfo,
  extensionFor,
  hasPreview,
  mimeForExtension,
  normalizeKind,
  safeFilenameStem,
  type ArtifactKind,
} from "./artifactTypes";

describe("normalizeKind", () => {
  it("maps types and aliases, rejects the rest", () => {
    expect(normalizeKind("html")).toBe("html");
    expect(normalizeKind(" HTML ")).toBe("html");
    expect(normalizeKind("md")).toBe("markdown");
    expect(normalizeKind("code")).toBe("code");
    expect(normalizeKind("pdf")).toBeNull();
    expect(normalizeKind("")).toBeNull();
    expect(normalizeKind(undefined)).toBeNull();
    expect(normalizeKind("constructor")).toBeNull();
    expect(normalizeKind("__proto__")).toBeNull();
  });
});

describe("type -> extension / mime", () => {
  it("covers every kind", () => {
    expect(downloadInfo("html", null, "My page")).toEqual({ filename: "My-page.html", mime: "text/html", extension: "html" });
    expect(downloadInfo("svg", null, "Logo")).toEqual({ filename: "Logo.svg", mime: "image/svg+xml", extension: "svg" });
    expect(downloadInfo("markdown", null, "Plan")).toEqual({ filename: "Plan.md", mime: "text/markdown", extension: "md" });
    expect(downloadInfo("csv", null, "Sales")).toEqual({ filename: "Sales.csv", mime: "text/csv", extension: "csv" });
    expect(downloadInfo("mermaid", null, "Flow")).toEqual({ filename: "Flow.mmd", mime: "text/plain", extension: "mmd" });
  });

  it("derives code extensions from the language, defaulting to txt", () => {
    expect(extensionFor("code", "python")).toBe("py");
    expect(extensionFor("code", "TypeScript")).toBe("ts");
    expect(extensionFor("code", "c++")).toBe("cpp");
    expect(extensionFor("code", "bash")).toBe("sh");
    expect(extensionFor("code", "brainfuck")).toBe("txt");
    expect(extensionFor("code", null)).toBe("txt");
    expect(extensionFor("code", "constructor")).toBe("txt");
  });

  it("uses a sensible mime for code files", () => {
    expect(downloadInfo("code", "json", "cfg").mime).toBe("application/json");
    expect(downloadInfo("code", "css", "s").mime).toBe("text/css");
    expect(downloadInfo("code", "js", "s").mime).toBe("text/javascript");
    expect(downloadInfo("code", "python", "s").mime).toBe("text/plain");
    expect(mimeForExtension("unknown")).toBe("text/plain");
    expect(mimeForExtension("constructor")).toBe("text/plain");
  });

  it("only html, svg, markdown and csv have a Preview", () => {
    const withPreview: ArtifactKind[] = ["html", "svg", "markdown", "csv"];
    expect(withPreview.every((k) => hasPreview(k))).toBe(true);
    expect(hasPreview("code")).toBe(false);
    expect(hasPreview("mermaid")).toBe(false);
  });
});

describe("safeFilenameStem", () => {
  it("removes separators, traversal and leading dots", () => {
    expect(safeFilenameStem("../../etc/passwd")).toBe("etc-passwd");
    expect(safeFilenameStem("a\\b:c*d?e")).toBe("a-b-c-d-e");
    expect(safeFilenameStem(".hidden")).toBe("hidden");
  });

  it("falls back and avoids Windows device names", () => {
    expect(safeFilenameStem("")).toBe("artifact");
    expect(safeFilenameStem("***")).toBe("artifact");
    expect(safeFilenameStem("CON")).toBe("artifact-CON");
    expect(safeFilenameStem("nul")).toBe("artifact-nul");
  });

  it("keeps letters and caps the length", () => {
    expect(safeFilenameStem("Price list for Munich")).toBe("Price-list-for-Munich");
    expect(safeFilenameStem("a".repeat(200)).length).toBeLessThanOrEqual(60);
  });
});

describe("artifactIdForTitle", () => {
  it("treats case and spacing differences as the same artifact", () => {
    expect(artifactIdForTitle("Pricing Page")).toBe(artifactIdForTitle("  pricing   page "));
  });

  it("separates different titles", () => {
    expect(artifactIdForTitle("Pricing page")).not.toBe(artifactIdForTitle("Pricing page 2"));
  });

  it("is Unicode-normalised", () => {
    const composed = "Caf" + String.fromCharCode(0xe9);
    const decomposed = "Cafe" + String.fromCharCode(0x301);
    expect(artifactIdForTitle(composed)).toBe(artifactIdForTitle(decomposed));
  });
});

describe("contentFingerprint", () => {
  it("changes with content and is stable", () => {
    expect(contentFingerprint("abc")).toBe(contentFingerprint("abc"));
    expect(contentFingerprint("abc")).not.toBe(contentFingerprint("abd"));
  });
});
