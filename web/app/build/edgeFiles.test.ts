import { describe, expect, it } from "vitest";
import { filesForPrompt, safeWrites } from "./edgeFiles";

const existing = {
  project_files: ["src/App.jsx", "src/big.js", "package.json"],
  files: [
    { path: "src/App.jsx", content: "export default 1;\n", full: true },
    { path: "src/big.js", content: "excerpt", full: false },
  ],
};

describe("edge file context", () => {
  it("shows full files and marks the rest read-only", () => {
    const p = filesForPrompt(existing, 10_000);
    expect(p.text).toContain("# src/App.jsx\nexport default 1;");
    expect(p.text).toContain("do not write these: src/big.js");
    expect(p.text).toContain("PROJECT FILES:");
    expect([...p.writable]).toEqual(["src/App.jsx"]);
  });

  it("treats a full file past the budget as read-only", () => {
    const p = filesForPrompt(existing, 5);
    expect(p.writable.size).toBe(0);
  });

  it("drops writes to existing files the model did not see in full", () => {
    const p = filesForPrompt(existing, 10_000);
    const kept = safeWrites(
      [
        { path: "src/App.jsx", content: "new" },
        { path: "src/big.js", content: "clobber" },
        { path: "package.json", content: "{}" },
        { path: "src/New.jsx", content: "new file" },
      ],
      existing,
      p.writable
    );
    expect(kept.map((f) => f.path)).toEqual(["src/App.jsx", "src/New.jsx"]);
  });
});
