import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const apiFetch = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...a: unknown[]) => apiFetch(...a),
  toJson: async (res: Response) => res.json(),
  jsonBody: (p: unknown) => ({ headers: {}, body: JSON.stringify(p) }),
}));

import { channelsApi } from "./channels";

function ok() {
  return Promise.resolve(new Response(JSON.stringify({ url: "https://idp.example/auth" }), { status: 200 }));
}

describe("OAuth authorize-url calls (SEC-018)", () => {
  beforeEach(() => {
    apiFetch.mockReset();
    apiFetch.mockImplementation(ok);
  });
  afterEach(() => {
    delete (window as { __ENV?: unknown }).__ENV;
  });

  const calls: Array<[string, () => Promise<unknown>, string]> = [
    ["google", () => channelsApi.getGoogleAuthorizeUrl(), "/api/channels/google/authorize-url"],
    ["google personal", () => channelsApi.getGooglePersonalAuthorizeUrl(), "/api/channels/google/personal/authorize-url"],
    ["youtube", () => channelsApi.getYoutubeUploadAuthorizeUrl(), "/api/channels/google/youtube-upload/authorize-url"],
    ["x", () => channelsApi.getXAuthorizeUrl(), "/api/channels/x/authorize-url"],
    ["atlassian", () => channelsApi.getAtlassianAuthorizeUrl(), "/api/mcp/registry/atlassian/authorize-url"],
  ];

  it.each(calls)("%s never uses the GET cache (single-use state)", async (_n, call, path) => {
    await call();
    expect(apiFetch).toHaveBeenCalledWith(path, expect.objectContaining({ skipGetCache: true }));
    expect(apiFetch.mock.calls[0][1].credentials).toBeUndefined();
  });

  it.each(calls)("%s sends credentials once the state cookie is enabled", async (_n, call, path) => {
    (window as { __ENV?: Record<string, string> }).__ENV = { NEXT_PUBLIC_OAUTH_STATE_COOKIE: "1" };
    await call();
    expect(apiFetch).toHaveBeenCalledWith(path, expect.objectContaining({ credentials: "include", skipGetCache: true }));
  });
});
