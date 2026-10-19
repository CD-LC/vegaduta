import { afterEach, describe, expect, it, vi } from "vitest";

// The mode switch in authClient.ts: keycloak mode must behave exactly as before (no instance
// until the keycloak-js chunk has loaded), local mode must hand out the adapter without ever
// loading keycloak-js.
afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe("authClient mode switch", () => {
  it("keycloak mode (default): not loaded, no instance, and loadKeycloak still imports keycloak-js", async () => {
    vi.stubEnv("NEXT_PUBLIC_AUTH_MODE", "");
    const client = await import("./authClient");

    expect(client.isKeycloakLoaded()).toBe(false);
    expect(client.getKeycloak()).toBeNull();

    await client.loadKeycloak();
    expect(client.isKeycloakLoaded()).toBe(true);
    const kc = client.getKeycloak();
    expect(kc).not.toBeNull();
    expect(kc.constructor.name).not.toBe("LocalAuthClient");
  });

  it("local mode: always loaded, serves the adapter singleton, never needs keycloak-js", async () => {
    vi.stubEnv("NEXT_PUBLIC_AUTH_MODE", "local");
    const client = await import("./authClient");
    const { LocalAuthClient } = await import("./localAuthClient");

    expect(client.isKeycloakLoaded()).toBe(true);
    await client.loadKeycloak();
    const kc = client.getKeycloak();
    expect(kc).toBeInstanceOf(LocalAuthClient);
    expect(client.getKeycloak()).toBe(kc);
    expect(client.KEYCLOAK_URL).toBe("");

    client.resetKeycloakInstance();
    expect(client.getKeycloak()).not.toBe(kc);
  });

  it("local mode: the adapter's hooks stamp the was-signed-in hint", async () => {
    vi.stubEnv("NEXT_PUBLIC_AUTH_MODE", "local");
    const client = await import("./authClient");
    const kc = client.getKeycloak();

    expect(typeof kc.onTokenExpired).toBe("function");
    kc.onAuthSuccess();
    expect(window.localStorage.getItem("vd_authed")).toBe("1");
  });
});
