import { describe, expect, it } from "vitest";
import { sanitizeTypedToken, validTokenValue } from "./tokenValue";

describe("tokenValue", () => {
  it("strips injection characters from rounding", () => {
    expect(sanitizeTypedToken("--radius", "8px; } body{display:none")).toBe("8pxbodydis");
    expect(sanitizeTypedToken("--radius", "$1")).toBe("1");
  });
  it("only accepts complete lengths and hex colours", () => {
    expect(validTokenValue("--radius", "12px")).toBe("12px");
    expect(validTokenValue("--radius", "0.5rem")).toBe("0.5rem");
    expect(validTokenValue("--radius", "")).toBeNull();
    expect(validTokenValue("--radius", "8pxbody")).toBeNull();
    expect(validTokenValue("--brand", "#aabbcc")).toBe("#aabbcc");
    expect(validTokenValue("--brand", "red; }")).toBeNull();
  });
});
