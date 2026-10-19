import { describe, expect, it } from "vitest";
import { pagedQuery, toPaged } from "./paged";

function res(body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json", ...headers },
  });
}

describe("toPaged", () => {
  it("reads total and has-more from the headers", async () => {
    const page = await toPaged(res([{ id: "a" }], { "X-Total-Count": "7", "X-Has-More": "true" }), 0, 1);
    expect(page).toEqual({ rows: [{ id: "a" }], total: 7, hasMore: true });
  });

  it("falls back to a full page meaning more when the headers are missing", async () => {
    expect((await toPaged(res([{ id: "a" }, { id: "b" }]), 0, 2)).hasMore).toBe(true);
    expect(await toPaged(res([{ id: "a" }]), 0, 2)).toMatchObject({ hasMore: false, total: null });
  });
});

describe("pagedQuery", () => {
  it("omits offset 0 and keeps other params", () => {
    expect(pagedQuery(new URLSearchParams({ channel: "WHATSAPP" }), 0, 500)).toBe("channel=WHATSAPP&limit=500");
    expect(pagedQuery(new URLSearchParams(), 500, 500)).toBe("limit=500&offset=500");
  });
});
