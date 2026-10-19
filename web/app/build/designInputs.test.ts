// Unit tests for the pure (DOM-free) designInputs helpers — palette
// extraction and file decoding need a canvas, so only serializeSketch and
// composeContext are covered here.
import { describe, expect, it } from "vitest";
import { composeContext, serializeSketch } from "./designInputs";

describe("serializeSketch", () => {
  it("serializes blocks top-to-bottom", () => {
    const text = serializeSketch([
      { type: "header", label: "Acme" },
      { type: "hero", label: "" },
      { type: "footer", label: "" },
    ]);
    expect(text).toContain("WIREFRAME (top to bottom):");
    expect(text).toContain('1. header — "Acme"');
    expect(text).toContain("2. hero");
    expect(text).toContain("3. footer");
  });

  it("returns empty for no blocks", () => {
    expect(serializeSketch([])).toBe("");
  });
});

describe("composeContext", () => {
  it("lists uploaded assets and input context text", () => {
    const ctx = composeContext(
      [
        { id: "a", kind: "svg", label: "x.svg", contextText: "SVG body" },
        { id: "b", kind: "image", label: "shot.png", contextText: 'Design reference "shot.png" attached.' },
      ],
      ["assets/logo.png"]
    );
    expect(ctx).toContain("assets/logo.png");
    expect(ctx).toContain("SVG body");
    expect(ctx).toContain('Design reference "shot.png"');
  });

  it("is empty when nothing is attached", () => {
    expect(composeContext([], [])).toBe("");
  });
});

import { imageContextText, revokeDesignInput } from "./designInputs";
import { vi } from "vitest";

describe("imageContextText", () => {
  it("only says attached when the image really travels", () => {
    expect(imageContextText("a.png", ["#112233"], true)).toBe('Design reference "a.png" attached. Dominant palette: #112233.');
    const dropped = imageContextText("a.png", ["#112233"], false);
    expect(dropped).not.toMatch(/ attached/);
    expect(dropped).toContain("#112233");
  });
});

describe("revokeDesignInput", () => {
  it("revokes blob URLs only and tolerates empty input", () => {
    const spy = vi.fn();
    const orig = URL.revokeObjectURL;
    URL.revokeObjectURL = spy;
    try {
      revokeDesignInput({ previewUrl: "blob:abc" });
      revokeDesignInput({ previewUrl: "https://x/y.png" });
      revokeDesignInput({});
      revokeDesignInput(null);
    } finally {
      URL.revokeObjectURL = orig;
    }
    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy).toHaveBeenCalledWith("blob:abc");
  });
});
