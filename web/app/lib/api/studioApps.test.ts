// Regression coverage for the publish wire shape: the studio UI's auth-mode
// vocabulary (off|optional|required) must reach core as its StudioApp.AuthMode
// enum (NONE|OPTIONAL|REQUIRED) - "off" used to ship verbatim and every
// default-auth publish 400'd with bad_auth_mode. apiFetch mocked at the seam.

import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.hoisted(() => vi.fn());
vi.mock("./core", () => ({
  apiFetch: apiFetchMock,
  toJson: async (res: any) => res.json(),
  jsonBody: (body: any) => ({
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  }),
}));

import { studioAppsApi } from "./studioApps";

function okJson(body: any) {
  return Promise.resolve({ ok: true, json: async () => body, text: async () => JSON.stringify(body) } as any);
}

describe("studioAppsApi.publishStudioApp", () => {
  beforeEach(() => apiFetchMock.mockReset());

  const sentBody = async (payload: any) => {
    apiFetchMock.mockReturnValue(okJson({ slug: "s" }));
    await studioAppsApi.publishStudioApp(payload);
    const [url, init] = apiFetchMock.mock.calls[apiFetchMock.mock.calls.length - 1];
    expect(url).toBe("/api/studio-apps/publish");
    expect(init.method).toBe("POST");
    return JSON.parse(init.body);
  };

  it("translates the studio UI auth modes to the server enum", async () => {
    expect((await sentBody({ authMode: "off" })).authMode).toBe("NONE");
    expect((await sentBody({ authMode: "optional" })).authMode).toBe("OPTIONAL");
    expect((await sentBody({ authMode: "required" })).authMode).toBe("REQUIRED");
  });

  it("passes server enum values through untouched", async () => {
    expect((await sentBody({ authMode: "NONE" })).authMode).toBe("NONE");
    expect((await sentBody({ authMode: "OPTIONAL" })).authMode).toBe("OPTIONAL");
  });

  it("omits authMode when absent (server defaults to NONE) and fails loud on unknowns", async () => {
    expect((await sentBody({})).authMode).toBeUndefined();
    expect((await sentBody({ authMode: "bogus" })).authMode).toBe("bogus");
  });
});
