import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("./api/core", () => ({ apiFetch: vi.fn() }));
import { apiFetch } from "./api/core";
import { AddonApiError, addonsApi } from "./addonsApi";

const fetchMock = vi.mocked(apiFetch);
const reply = (status: number, body: unknown) =>
  fetchMock.mockResolvedValueOnce(new Response(body === undefined ? null : JSON.stringify(body), { status }));

afterEach(() => fetchMock.mockReset());

describe("addonsApi", () => {
  it("reads the list without the GET cache (it is polled and core calls Ollama on each read)", async () => {
    reply(200, { catalogSchema: 1, addons: [] });
    await expect(addonsApi.list()).resolves.toMatchObject({ catalogSchema: 1 });
    expect(fetchMock).toHaveBeenCalledWith("/api/addons", { skipGetCache: true });
  });

  it("posts enable and disable, encodes the id and sends cascade only when asked", async () => {
    reply(200, { addon: { id: "a b" }, changed: [], needsHost: false, apply: {} });
    await addonsApi.enable("a b");
    expect(fetchMock).toHaveBeenLastCalledWith("/api/addons/a%20b/enable", { method: "POST" });
    reply(200, { addon: {}, changed: [], needsHost: false, apply: {} });
    await addonsApi.disable("search");
    expect(fetchMock).toHaveBeenLastCalledWith("/api/addons/search/disable", { method: "POST" });
    reply(200, { addon: {}, changed: [], needsHost: false, apply: {} });
    await addonsApi.disable("search", true);
    expect(fetchMock).toHaveBeenLastCalledWith("/api/addons/search/disable?cascade=true", { method: "POST" });
  });

  it("keeps the controller's error code and details instead of turning the code into a sentence", async () => {
    reply(409, { error: "required_by", message: "Other add-ons need this one.", details: { dependents: ["documents", 7, "x"] } });
    const err = await addonsApi.disable("workspace").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(AddonApiError);
    const e = err as AddonApiError;
    expect(e.status).toBe(409);
    expect(e.code).toBe("required_by");
    expect(e.message).toBe("Other add-ons need this one.");
    expect(e.dependents).toEqual(["documents", "x"]);
  });

  it("treats a platform error sentence as a message, not a code", async () => {
    reply(403, { error: "You don't have permission to do that." });
    const e = (await addonsApi.enable("search").catch((x: unknown) => x)) as AddonApiError;
    expect(e.code).toBeNull();
    expect(e.status).toBe(403);
    expect(e.message).toBe("You don't have permission to do that.");
    expect(e.dependents).toEqual([]);
  });

  it("survives an empty or non-JSON error body", async () => {
    fetchMock.mockResolvedValueOnce(new Response("<html>bad gateway</html>", { status: 502 }));
    const e = (await addonsApi.list().catch((x: unknown) => x)) as AddonApiError;
    expect(e).toBeInstanceOf(AddonApiError);
    expect(e.status).toBe(502);
    expect(e.code).toBeNull();
    expect(e.message).toBe("HTTP 502");
  });
});
