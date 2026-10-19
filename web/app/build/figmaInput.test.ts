import { describe, expect, it } from "vitest";
import { designInputFromFigma, validateFigmaUrl } from "./figmaInput";

const KEY = "AbCdEfGhIjKlMnOp";

describe("validateFigmaUrl", () => {
  it("accepts design and file links with node-id", () => {
    expect(validateFigmaUrl(`https://www.figma.com/design/${KEY}/N?node-id=1-2`)).toBeNull();
    expect(validateFigmaUrl(`https://figma.com/file/${KEY}/N?node-id=1%3A2`)).toBeNull();
  });
  it("rejects missing node-id, wrong host, http, garbage", () => {
    expect(validateFigmaUrl(`https://www.figma.com/design/${KEY}/N`)).toMatch(/Copy link/);
    expect(validateFigmaUrl(`https://evil.com/design/${KEY}/N?node-id=1-2`)).toMatch(/figma.com/);
    expect(validateFigmaUrl(`http://www.figma.com/design/${KEY}/N?node-id=1-2`)).toMatch(/https/);
    expect(validateFigmaUrl("nope")).toBeTruthy();
  });
});

describe("designInputFromFigma", () => {
  it("carries context and image, labels by frame name", () => {
    const image = { name: "f.png", media_type: "image/png", data_base64: "AAAA" };
    const d = designInputFromFigma({ context: "SPEC", image, name: "Landing" }, 1);
    expect(d.label).toBe("Figma: Landing");
    expect(d.contextText).toBe("SPEC");
    expect(d.image).toBe(image);
  });
  it("works without an image", () => {
    expect(designInputFromFigma({ context: "S" }, 1).image).toBeUndefined();
  });
});

describe("validateFigmaUrl host strictness", () => {
  it("rejects explicit ports and credentials", () => {
    expect(validateFigmaUrl(`https://www.figma.com:8443/design/${KEY}/N?node-id=1-2`)).toMatch(/figma.com/);
    expect(validateFigmaUrl(`https://user:pw@www.figma.com/design/${KEY}/N?node-id=1-2`)).toMatch(/figma.com/);
  });
});
