import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { PinResult, WorkflowPin } from "../../lib/api/workflowPins";

const listPins = vi.fn();
const putPin = vi.fn();
const deletePin = vi.fn();
vi.mock("../../lib/api/workflowPins", async () => {
  const actual = await vi.importActual<typeof import("../../lib/api/workflowPins")>("../../lib/api/workflowPins");
  return {
    ...actual,
    listPins: (...a: unknown[]) => listPins(...a),
    putPin: (...a: unknown[]) => putPin(...a),
    deletePin: (...a: unknown[]) => deletePin(...a),
  };
});

import { __resetPinCache, usePins } from "./usePins";

const mk = (nodeId: string, data: string | null = "{}", sizeChars = 2): WorkflowPin => ({
  nodeId,
  source: "test",
  sourceRunId: null,
  pinnedBy: "me",
  pinnedAt: "2026-09-30T10:00:00Z",
  sizeChars,
  configHash: null,
  data,
});
const ok = <T,>(data: T): PinResult<T> => ({ ok: true, data });
const fail = (kind: string, message = "", status = 400): PinResult<never> =>
  ({ ok: false, kind, message, status }) as PinResult<never>;

beforeEach(() => {
  listPins.mockReset();
  putPin.mockReset();
  deletePin.mockReset();
  __resetPinCache();
});

async function loaded(workflowId = "wf1") {
  const hook = renderHook(({ id }) => usePins(id), { initialProps: { id: workflowId } });
  await waitFor(() => expect(hook.result.current.loaded).toBe(true));
  return hook;
}

describe("usePins", () => {
  it("loads pins keyed by node id", async () => {
    listPins.mockResolvedValue(ok([mk("a"), mk("b")]));
    const { result } = await loaded();
    expect(Object.keys(result.current.pins).sort()).toEqual(["a", "b"]);
    expect(result.current.canEdit).toBe(true);
    expect(result.current.unsupported).toBe(false);
  });

  it("degrades to an empty, unsupported state when the route is missing", async () => {
    listPins.mockResolvedValue(fail("unsupported", "", 501));
    const { result } = await loaded();
    expect(result.current.pins).toEqual({});
    expect(result.current.unsupported).toBe(true);
    expect(result.current.canEdit).toBe(false);
  });

  it("keeps last-known pins on a network error and still settles", async () => {
    listPins.mockResolvedValueOnce(ok([mk("a")]));
    const { result } = await loaded();
    listPins.mockResolvedValueOnce(fail("network", "", 0));
    await act(async () => {
      await result.current.refresh();
    });
    expect(result.current.pins.a).toBeTruthy();
    expect(result.current.unsupported).toBe(false);
  });

  it("is view-only when the server withholds pin data", async () => {
    listPins.mockResolvedValue(ok([mk("a", null)]));
    const { result } = await loaded();
    expect(result.current.canEdit).toBe(false);
  });

  it("pins optimistically, then adopts the server's pin", async () => {
    listPins.mockResolvedValue(ok([]));
    let release: (r: PinResult<WorkflowPin>) => void = () => {};
    putPin.mockReturnValue(new Promise((res) => (release = res)));
    const { result } = await loaded();
    let pending: Promise<PinResult<WorkflowPin>> = Promise.resolve(ok(mk("x")));
    act(() => {
      pending = result.current.pin("x", { data: '{"a":1}', source: "manual" });
    });
    // Visible before the server answers.
    expect(result.current.pins.x?.data).toBe('{"a":1}');
    await act(async () => {
      release(ok({ ...mk("x", '{"a":1}', 7), pinnedBy: "server-user" }));
      await pending;
    });
    expect(result.current.pins.x.pinnedBy).toBe("server-user");
  });

  it("rolls a failed new pin back and reports why", async () => {
    listPins.mockResolvedValue(ok([]));
    putPin.mockResolvedValue(fail("too-large", "too big"));
    const { result } = await loaded();
    let r: PinResult<WorkflowPin>;
    await act(async () => {
      r = await result.current.pin("x", { data: "{}", source: "manual" });
    });
    expect(r.ok).toBe(false);
    expect(r.kind).toBe("too-large");
    expect(result.current.pins.x).toBeUndefined();
  });

  it("restores the previous pin when replacing one fails", async () => {
    const old = mk("x", '{"old":true}', 12);
    listPins.mockResolvedValue(ok([old]));
    putPin.mockResolvedValue(fail("network", "", 0));
    const { result } = await loaded();
    await act(async () => {
      await result.current.pin("x", { data: '{"new":true}', source: "manual" });
    });
    expect(result.current.pins.x).toEqual(old);
  });

  it("refuses a 31st pin without calling the server", async () => {
    listPins.mockResolvedValue(ok(Array.from({ length: 30 }, (_, i) => mk(`n${i}`))));
    const { result } = await loaded();
    let r: PinResult<WorkflowPin>;
    await act(async () => {
      r = await result.current.pin("extra", { data: "{}", source: "manual" });
    });
    expect(r.ok).toBe(false);
    expect(r.kind).toBe("too-many");
    expect(putPin).not.toHaveBeenCalled();
  });

  it("flips to view-only after a 403 and rolls back", async () => {
    listPins.mockResolvedValue(ok([]));
    putPin.mockResolvedValue(fail("forbidden", "", 403));
    const { result } = await loaded();
    await act(async () => {
      await result.current.pin("x", { data: "{}", source: "manual" });
    });
    expect(result.current.pins.x).toBeUndefined();
    expect(result.current.canEdit).toBe(false);
  });

  it("unpins optimistically and puts the pin back if the delete fails", async () => {
    listPins.mockResolvedValue(ok([mk("x")]));
    deletePin.mockResolvedValue(fail("network", "", 0));
    const { result } = await loaded();
    await act(async () => {
      const r = await result.current.unpin("x");
      expect(r.ok).toBe(false);
    });
    expect(result.current.pins.x).toBeTruthy();

    deletePin.mockResolvedValue(ok(null));
    await act(async () => {
      await result.current.unpin("x");
    });
    expect(result.current.pins.x).toBeUndefined();
  });

  it("does not write a late PUT answer for workflow A into workflow B", async () => {
    listPins.mockImplementation((id: string) => Promise.resolve(ok(id === "wfA" ? [] : [mk("b1")])));
    let release: (r: PinResult<WorkflowPin>) => void = () => {};
    putPin.mockReturnValue(new Promise((res) => (release = res)));
    const hook = await loaded("wfA");
    let pending: Promise<PinResult<WorkflowPin>> = Promise.resolve(ok(mk("x")));
    act(() => {
      pending = hook.result.current.pin("x", { data: "{}", source: "manual" });
    });
    hook.rerender({ id: "wfB" });
    await waitFor(() => expect(hook.result.current.loaded).toBe(true));
    await act(async () => {
      release(ok(mk("x")));
      await pending;
    });
    expect(Object.keys(hook.result.current.pins)).toEqual(["b1"]);
  });

  it("reports total-size, not too-many, when the 2,000,000 character cap is what a pin would breach", async () => {
    listPins.mockResolvedValue(ok([mk("a", "{}", 1_900_000)]));
    const { result } = await loaded();
    let r: PinResult<WorkflowPin>;
    await act(async () => {
      r = await result.current.pin("b", { data: "x".repeat(150_000), source: "manual" });
    });
    expect(r.ok).toBe(false);
    expect(r.kind).toBe("total-size");
    expect(putPin).not.toHaveBeenCalled();
  });

  it("paints cached pins at once on remount and ignores a late answer for another workflow", async () => {
    listPins.mockResolvedValueOnce(ok([mk("a")]));
    const first = await loaded("wfA");
    first.unmount();
    let releaseB: (r: PinResult<WorkflowPin[]>) => void = () => {};
    listPins.mockReturnValueOnce(new Promise((res) => (releaseB = res)));
    const second = renderHook(({ id }) => usePins(id), { initialProps: { id: "wfB" } });
    listPins.mockResolvedValueOnce(ok([mk("a")]));
    second.rerender({ id: "wfA" });
    expect(second.result.current.pins.a).toBeTruthy(); // from cache, before any fetch settles
    await act(async () => {
      releaseB(ok([mk("zzz")]));
    });
    expect(second.result.current.pins.zzz).toBeUndefined();
  });
});
