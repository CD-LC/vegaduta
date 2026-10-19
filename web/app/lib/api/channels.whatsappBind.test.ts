import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetch = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...a: unknown[]) => apiFetch(...a),
  toJson: async (res: Response) => {
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.status === 204 ? null : res.json();
  },
  toJsonQuietOn: () => async (res: Response) => res.json(),
  jsonBody: (p: unknown) => ({ headers: {}, body: JSON.stringify(p) }),
}));

import { channelsApi } from "./channels";

const json = (status: number, body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status }));

describe("setWhatsAppDefaultAgent", () => {
  beforeEach(() => apiFetch.mockReset());

  it("sends the task and the acknowledgement", async () => {
    apiFetch.mockReturnValue(json(200, { code: "BOUND" }));
    const r = await channelsApi.setWhatsAppDefaultAgent("a1", "BOOKING", true);
    expect(r.ok).toBe(true);
    expect(JSON.parse(apiFetch.mock.calls[0][1].body)).toEqual({
      agentId: "a1",
      task: "BOOKING",
      acknowledgeOpenEnded: true,
    });
  });

  it("returns the open-ended warning as a result, not an error", async () => {
    apiFetch.mockReturnValue(
      json(409, { code: "WHATSAPP_OPEN_ENDED_AGENT", message: "m", reasons: ["no task scope"] }),
    );
    const r = await channelsApi.setWhatsAppDefaultAgent("a1", "SUPPORT");
    expect(r).toMatchObject({ ok: false, code: "WHATSAPP_OPEN_ENDED_AGENT", reasons: ["no task scope"] });
  });

  it("returns a missing task as a result", async () => {
    apiFetch.mockReturnValue(json(400, { code: "WHATSAPP_TASK_REQUIRED", message: "m" }));
    const r = await channelsApi.setWhatsAppDefaultAgent("a1", "");
    expect(r).toMatchObject({ ok: false, code: "WHATSAPP_TASK_REQUIRED", reasons: [] });
  });

  it("still throws for any other failure", async () => {
    apiFetch.mockReturnValue(json(500, { error: "boom" }));
    await expect(channelsApi.setWhatsAppDefaultAgent("a1", "SUPPORT")).rejects.toThrow();
    apiFetch.mockReturnValue(json(400, { error: "other validation" }));
    await expect(channelsApi.setWhatsAppDefaultAgent("a1", "SUPPORT")).rejects.toThrow();
  });
});
