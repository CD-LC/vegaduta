import { describe, expect, it, vi, beforeEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";

const apiFetchMock = vi.fn();
vi.mock("../../lib/api/core", () => ({
  apiFetch: (...a: unknown[]) => apiFetchMock(...a),
  toJsonQuietOn: () => async (res: Response) => res.json(),
}));

import { usePagedList } from "./pagedLoad";

function res(rows: unknown[], headers: Record<string, string> = {}) {
  return { ok: true, status: 200, headers: new Headers(headers), json: async () => rows } as unknown as Response;
}
const rowsOf = (n: number) => Array.from({ length: n }, (_, i) => ({ id: i }));

describe("usePagedList", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
  });



  it("fetches one server page with offset and exposes the real total", async () => {
    apiFetchMock.mockImplementation(async () => res(rowsOf(10), { "X-Total-Count": "42", "X-Has-More": "true" }));
    const { result } = renderHook(() => usePagedList<{ id: number }>("/api/x?a=1", 200));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(apiFetchMock.mock.calls[0][0]).toBe("/api/x?a=1&limit=10");
    expect(result.current.total).toBe(42);
    expect(result.current.hasMore).toBe(true);
    expect(result.current.legacy).toBe(false);
    act(() => result.current.setPage(2));
    await waitFor(() => expect(apiFetchMock).toHaveBeenCalledTimes(2));
    expect(apiFetchMock.mock.calls[1][0]).toBe("/api/x?a=1&limit=10&offset=10");
  });

  it("falls back to client paging when the server sends no X-Total-Count", async () => {
    apiFetchMock.mockImplementation(async (path: string) => res(rowsOf(path.includes("limit=200") ? 37 : 10)));
    const { result } = renderHook(() => usePagedList<{ id: number }>("/api/x", 200));
    await waitFor(() => expect(result.current.legacy).toBe(true));
    expect(apiFetchMock.mock.calls[1][0]).toBe("/api/x?limit=200");
    expect(result.current.total).toBe(37);
    expect(result.current.rows).toHaveLength(10);
    act(() => result.current.setPage(4));
    expect(result.current.rows).toHaveLength(7);
    expect(apiFetchMock).toHaveBeenCalledTimes(2);
  });

  it("reports a failed load", async () => {
    apiFetchMock.mockImplementation(async () => {
      throw new Error("boom");
    });
    const { result } = renderHook(() => usePagedList("/api/x", 200));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBe("boom");
    expect(result.current.rows).toEqual([]);
  });
});
