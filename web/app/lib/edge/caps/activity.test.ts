import { describe, expect, it, vi } from "vitest";
import { beginEdgeActivity, endEdgeActivity, getEdgeActivity, subscribeEdgeActivity } from "./activity";
import * as capabilities from "../capabilities";

describe("edge activity signal", () => {
  it("is refcounted: overlapping turns clear only when the last one ends", () => {
    const seen: boolean[] = [];
    const off = subscribeEdgeActivity((a) => seen.push(a.active));
    beginEdgeActivity(1, "m1");
    beginEdgeActivity(0, "m0");
    expect(getEdgeActivity()).toMatchObject({ active: true, tier: 0, modelId: "m0" });
    endEdgeActivity();
    expect(getEdgeActivity().active).toBe(true);
    endEdgeActivity();
    expect(getEdgeActivity()).toMatchObject({ active: false, tier: null, modelId: null });
    off();
    expect(seen).toEqual([true, true, false]);
  });

  it("an unbalanced end is harmless and a throwing listener never breaks the path", () => {
    const off = subscribeEdgeActivity(() => {
      throw new Error("bad listener");
    });
    expect(() => endEdgeActivity()).not.toThrow();
    expect(() => beginEdgeActivity(1, "x")).not.toThrow();
    endEdgeActivity();
    off();
  });

  it("stays reachable through the capabilities facade (same module state)", () => {
    const spy = vi.fn();
    const off = capabilities.subscribeEdgeActivity(spy);
    beginEdgeActivity(1, "facade");
    expect(capabilities.getEdgeActivity().modelId).toBe("facade");
    endEdgeActivity();
    off();
    expect(spy).toHaveBeenCalledTimes(2);
  });
});
