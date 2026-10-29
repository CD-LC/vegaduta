import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...args: unknown[]) => apiFetchMock(...args),
  toJson: async (res: Response) => {
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  },
  toJsonQuietOn: () => async (res: Response) => {
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  },
  jsonBody: (payload: unknown) => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) }),
  friendlyStatusFallback: (status: number) => `friendly-${status}`,
}));

import { desktopRecipesApi, normalizeRunResult } from "./desktopRecipes";

const ok = (body: unknown = {}) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

beforeEach(() => {
  apiFetchMock.mockReset();
  apiFetchMock.mockImplementation(async () => ok());
});

describe("desktopRecipesApi", () => {
  it("lists recipes on GET /api/desktop/recipes, passing the filters through as query params", async () => {
    await desktopRecipesApi.listDesktopRecipes();
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/desktop/recipes");
    await desktopRecipesApi.listDesktopRecipes("a 1", 10, 40);
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/desktop/recipes?agentId=a+1&limit=10&offset=40");
  });

  it("reads one recipe on GET /{id}", async () => {
    await desktopRecipesApi.getDesktopRecipe("r 1");
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/desktop/recipes/r%201");
  });

  it("creates a recipe and saves a run as a recipe with POSTs carrying the JSON body", async () => {
    const spec = {
      version: 1,
      allowedApps: ["Safari"],
      params: [{ name: "orderId", required: true }],
      steps: [{ action: "click", x: 1, y: 2 }],
    };
    await desktopRecipesApi.createDesktopRecipe({ agentId: "a1", name: "n", spec });
    let [url, init] = apiFetchMock.mock.calls[0];
    expect(url).toBe("/api/desktop/recipes");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ agentId: "a1", name: "n", spec });

    const input = {
      agentId: "a1",
      sessionId: "s1",
      name: "n",
      parameters: { orderId: "A-1" },
      allowLiteralText: false,
      defaultMode: "MONITOR" as const,
    };
    await desktopRecipesApi.saveDesktopRunAsRecipe(input);
    [url, init] = apiFetchMock.mock.calls[1];
    expect(url).toBe("/api/desktop/recipes/from-run");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual(input);
  });

  it("runs a recipe with POST /{id}/run and patches/deletes on the same sub-route", async () => {
    const run = { params: { orderId: "A-1" }, confirmIrreversible: true, fromStep: 2, mode: "MONITOR" as const };
    await desktopRecipesApi.runDesktopRecipe("r1", run);
    const [runUrl, runInit] = apiFetchMock.mock.calls[0];
    expect(runUrl).toBe("/api/desktop/recipes/r1/run");
    expect(runInit.method).toBe("POST");
    expect(JSON.parse(runInit.body)).toEqual(run);

    await desktopRecipesApi.updateDesktopRecipe("r1", { defaultMode: "MONITOR" });
    const [patchUrl, patchInit] = apiFetchMock.mock.calls[1];
    expect(patchUrl).toBe("/api/desktop/recipes/r1");
    expect(patchInit.method).toBe("PATCH");
    expect(JSON.parse(patchInit.body)).toEqual({ defaultMode: "MONITOR" });

    apiFetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await desktopRecipesApi.deleteDesktopRecipe("r1");
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/desktop/recipes/r1", { method: "DELETE" });
  });

  describe("normalizeRunResult", () => {
    it("accepts the {ok, recipe, mode, stepResults} shape", () => {
      const out = normalizeRunResult({
        ok: true,
        recipe: { id: "r1", name: "n", steps: 3 },
        mode: "MONITOR",
        stepsRun: 3,
        stepResults: [{ index: 1, action: "click", ok: true, note: "done" }],
      });
      expect(out).toEqual({
        ok: true,
        recipe: { id: "r1", name: "n", steps: 3 },
        mode: "MONITOR",
        stepsRun: 3,
        failure: undefined,
        stepResults: [{ index: 1, action: "click", ok: true, note: "done" }],
      });
    });

    it("accepts the flat {recipeId, name, success, stepsTotal, steps} shape", () => {
      const out = normalizeRunResult({
        recipeId: "r1",
        name: "n",
        success: false,
        stepsRun: 1,
        stepsTotal: 4,
        failure: "step 2: element not found",
        steps: [
          { index: 1, action: "open", ok: true, note: null },
          { index: 2, action: "click", ok: false, note: "element not found" },
        ],
      });
      expect(out.ok).toBe(false);
      expect(out.recipe).toEqual({ id: "r1", name: "n", steps: 4 });
      expect(out.failure).toBe("step 2: element not found");
      expect(out.stepResults).toHaveLength(2);
      expect(out.stepResults[1]).toEqual({ index: 2, action: "click", ok: false, note: "element not found" });
    });
  });

  describe("fetchDesktopMonitorFrame", () => {
    it("reads the frame as a Blob and always skips the GET cache", async () => {
      const bytes = new Uint8Array([1, 2, 3]);
      apiFetchMock.mockResolvedValue(new Response(bytes, { status: 200, headers: { "Content-Type": "image/jpeg" } }));
      const blob = await desktopRecipesApi.fetchDesktopMonitorFrame();
      expect(apiFetchMock).toHaveBeenLastCalledWith("/api/desktop/monitor/frame", { skipGetCache: true });
      expect(blob.size).toBe(3);
      expect(blob.type).toBe("image/jpeg");
    });

    it("throws an error carrying .status on 404/503 (monitor or sidecar off) without a toast", async () => {
      apiFetchMock.mockResolvedValue(new Response("off", { status: 503 }));
      await expect(desktopRecipesApi.fetchDesktopMonitorFrame()).rejects.toMatchObject({ status: 503 });
      apiFetchMock.mockResolvedValue(new Response("gone", { status: 404 }));
      await expect(desktopRecipesApi.fetchDesktopMonitorFrame()).rejects.toMatchObject({ status: 404 });
    });

    it("rejects a non-image body even on a 200", async () => {
      apiFetchMock.mockResolvedValue(
        new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } })
      );
      await expect(desktopRecipesApi.fetchDesktopMonitorFrame()).rejects.toMatchObject({ status: 200 });
    });
  });
});
