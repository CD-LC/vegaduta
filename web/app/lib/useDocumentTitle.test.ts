import { describe, expect, it } from "vitest";
import { documentTitleFor } from "./useDocumentTitle";

describe("documentTitleFor", () => {
  it("uses the first string candidate", () => {
    expect(documentTitleFor("Usage & limits", "Plan")).toBe("Usage & limits - VegaDuta");
    expect(documentTitleFor(null, "  Tools ")).toBe("Tools - VegaDuta");
  });

  it("returns null when no candidate is a non-empty string", () => {
    expect(documentTitleFor(undefined, null, "", 3)).toBeNull();
  });
});
