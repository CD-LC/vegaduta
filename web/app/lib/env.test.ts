import { afterEach, describe, expect, it } from "vitest";
import { getPublicEnv, publicEnvScript, publicEnvSnapshot } from "./env";

// Runtime public config (lib/env.ts). jsdom gives us a `window`, so these
// exercise the BROWSER branch: window.__ENV first, then the (here: live)
// build-time process.env value.
describe("lib/env getPublicEnv", () => {
  const saved = process.env.NEXT_PUBLIC_API_BASE_URL;
  afterEach(() => {
    delete window.__ENV;
    if (saved === undefined) delete process.env.NEXT_PUBLIC_API_BASE_URL;
    else process.env.NEXT_PUBLIC_API_BASE_URL = saved;
  });

  it("prefers the runtime window.__ENV value over the build-time one", () => {
    process.env.NEXT_PUBLIC_API_BASE_URL = "http://build-time:8080";
    window.__ENV = { NEXT_PUBLIC_API_BASE_URL: "https://api.example.com" };
    expect(getPublicEnv("NEXT_PUBLIC_API_BASE_URL")).toBe("https://api.example.com");
  });

  it("falls back to the build-time value when the runtime one is missing or empty", () => {
    process.env.NEXT_PUBLIC_API_BASE_URL = "http://build-time:8080";
    expect(getPublicEnv("NEXT_PUBLIC_API_BASE_URL")).toBe("http://build-time:8080");
    window.__ENV = { NEXT_PUBLIC_API_BASE_URL: "" };
    expect(getPublicEnv("NEXT_PUBLIC_API_BASE_URL")).toBe("http://build-time:8080");
  });

  it("returns undefined when neither is set (call sites keep their own defaults)", () => {
    delete process.env.NEXT_PUBLIC_API_BASE_URL;
    expect(getPublicEnv("NEXT_PUBLIC_API_BASE_URL")).toBeUndefined();
  });
});

describe("lib/env publicEnvScript", () => {
  afterEach(() => {
    delete process.env.NEXT_PUBLIC_COFFEE_URL;
    delete process.env.SOME_SERVER_SECRET;
  });

  it("serialises only allowlisted keys, never other env vars", () => {
    process.env.SOME_SERVER_SECRET = "do-not-leak";
    process.env.NEXT_PUBLIC_COFFEE_URL = "https://coffee.example.com";
    const snap = publicEnvSnapshot();
    expect(snap.NEXT_PUBLIC_COFFEE_URL).toBe("https://coffee.example.com");
    expect(JSON.stringify(snap)).not.toContain("do-not-leak");
    expect(publicEnvScript()).not.toContain("do-not-leak");
  });

  it("escapes values so they cannot close the script tag", () => {
    process.env.NEXT_PUBLIC_COFFEE_URL = "</script><script>alert(1)</script>";
    const body = publicEnvScript();
    expect(body).not.toContain("</script>");
    expect(body).not.toContain("<");
    expect(body.startsWith("window.__ENV=Object.freeze(")).toBe(true);
  });
});
