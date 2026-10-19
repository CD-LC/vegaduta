import { describe, expect, it } from "vitest";
import { repeatsOption } from "./evalRunOptions";

describe("repeatsOption", () => {
  it("sends 1 explicitly so the server default of 3 cannot override it", () => {
    expect(repeatsOption("1")).toBe(1);
  });
  it("sends other valid values", () => {
    expect(repeatsOption("3")).toBe(3);
    expect(repeatsOption("5")).toBe(5);
  });
  it("omits empty and out-of-range values", () => {
    expect(repeatsOption("")).toBeUndefined();
    expect(repeatsOption("abc")).toBeUndefined();
    expect(repeatsOption("0")).toBeUndefined();
    expect(repeatsOption("6")).toBeUndefined();
  });
});
