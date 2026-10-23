import { describe, expect, it } from "vitest";
import { NAV_SECTIONS } from "./adminNav";
import { parseCollapsed, serializeCollapsed } from "./navCollapsed";

const LABELS = NAV_SECTIONS.flatMap((s) => (s.label ? [s.label] : []));

describe("rail group state in localStorage", () => {
  it("stores only the collapsed groups, as a list of rail labels in rail order", () => {
    const state = { [LABELS[2]]: true, [LABELS[0]]: true, [LABELS[1]]: false };
    expect(JSON.parse(serializeCollapsed(state))).toEqual([LABELS[0], LABELS[2]]);
  });

  it("never stores a label that is not a rail group", () => {
    expect(JSON.parse(serializeCollapsed({ "Not a group": true, [LABELS[0]]: true }))).toEqual([LABELS[0]]);
  });

  it("stores an empty list when nothing is collapsed", () => {
    expect(serializeCollapsed({})).toBe("[]");
    expect(serializeCollapsed({ [LABELS[0]]: false })).toBe("[]");
  });

  it("round-trips through parse", () => {
    const state = { [LABELS[0]]: true, [LABELS[3]]: true };
    expect(parseCollapsed(serializeCollapsed(state))).toEqual(state);
  });

  it("still reads the first format, an object of collapsed labels", () => {
    expect(parseCollapsed(JSON.stringify({ [LABELS[1]]: true, [LABELS[2]]: false }))).toEqual({ [LABELS[1]]: true });
  });

  it("ignores values it does not understand and throws on text that is not JSON", () => {
    expect(parseCollapsed("42")).toEqual({});
    expect(parseCollapsed(JSON.stringify([LABELS[0], 7, null]))).toEqual({ [LABELS[0]]: true });
    expect(() => parseCollapsed("{not json")).toThrow();
  });
});
