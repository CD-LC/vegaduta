import { afterEach, describe, expect, it } from "vitest";
import {
  PUBLIC_ENV_KEYS,
  PUBLIC_POLICY_KEYS,
  getPublicEnv,
  getPublicPolicy,
  publicEnvScript,
  publicEnvSnapshot,
} from "./env";

// The policy settings (billing switch, OAuth state cookie switch, trusted image hosts) have their own reader
// so their values never share a return value with the URL settings (see PUBLIC_POLICY_KEYS).
describe("lib/env getPublicPolicy", () => {
  afterEach(() => {
    delete window.__ENV;
    for (const key of PUBLIC_POLICY_KEYS) delete process.env[key];
  });

  it("is a subset of the allowlisted public keys, so the root layout still ships each one", () => {
    for (const key of PUBLIC_POLICY_KEYS) expect(PUBLIC_ENV_KEYS).toContain(key);
  });

  it("keeps the precedence of getPublicEnv: runtime window.__ENV, then build time, then undefined", () => {
    expect(getPublicPolicy("NEXT_PUBLIC_BILLING_ENABLED")).toBeUndefined();
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    expect(getPublicPolicy("NEXT_PUBLIC_BILLING_ENABLED")).toBe("1");
    window.__ENV = { NEXT_PUBLIC_BILLING_ENABLED: "0" };
    expect(getPublicPolicy("NEXT_PUBLIC_BILLING_ENABLED")).toBe("0");
    window.__ENV = { NEXT_PUBLIC_BILLING_ENABLED: "" };
    expect(getPublicPolicy("NEXT_PUBLIC_BILLING_ENABLED")).toBe("1");
  });

  it("reads every policy key from its own build-time value", () => {
    process.env.NEXT_PUBLIC_OAUTH_STATE_COOKIE = "1";
    process.env.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS = ".example.com,cdn.example.org";
    expect(getPublicPolicy("NEXT_PUBLIC_OAUTH_STATE_COOKIE")).toBe("1");
    expect(getPublicPolicy("NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS")).toBe(".example.com,cdn.example.org");
  });

  it("still appears in the snapshot and script that the root layout embeds", () => {
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    process.env.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS = ".example.com";
    const snap = publicEnvSnapshot();
    expect(snap.NEXT_PUBLIC_BILLING_ENABLED).toBe("1");
    expect(snap.NEXT_PUBLIC_TRUSTED_IMAGE_HOSTS).toBe(".example.com");
    expect(publicEnvScript()).toContain('"NEXT_PUBLIC_BILLING_ENABLED":"1"');
  });
});

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

describe("lib/env NEXT_PUBLIC_EDITION", () => {
  afterEach(() => {
    delete window.__ENV;
    delete process.env.NEXT_PUBLIC_EDITION;
  });

  it("is an allowlisted public key, so the root layout ships it to the browser", () => {
    expect(PUBLIC_ENV_KEYS).toContain("NEXT_PUBLIC_EDITION");
    process.env.NEXT_PUBLIC_EDITION = "personal";
    expect(publicEnvSnapshot().NEXT_PUBLIC_EDITION).toBe("personal");
    expect(publicEnvScript()).toContain('"NEXT_PUBLIC_EDITION":"personal"');
  });

  it("follows the runtime > build-time precedence of every other key", () => {
    process.env.NEXT_PUBLIC_EDITION = "full";
    expect(getPublicEnv("NEXT_PUBLIC_EDITION")).toBe("full");
    window.__ENV = { NEXT_PUBLIC_EDITION: "personal" };
    expect(getPublicEnv("NEXT_PUBLIC_EDITION")).toBe("personal");
    window.__ENV = { NEXT_PUBLIC_EDITION: "" };
    expect(getPublicEnv("NEXT_PUBLIC_EDITION")).toBe("full");
  });

  it("is unset by default", () => {
    delete process.env.NEXT_PUBLIC_EDITION;
    expect(getPublicEnv("NEXT_PUBLIC_EDITION")).toBeUndefined();
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
