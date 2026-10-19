import { afterEach, describe, expect, it, vi } from "vitest";
import { authMode, isLocalAuth } from "./authMode";

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("authMode", () => {
  it("defaults to keycloak when the public value is unset or empty", () => {
    vi.stubEnv("NEXT_PUBLIC_AUTH_MODE", "");
    expect(authMode()).toBe("keycloak");
    expect(isLocalAuth()).toBe(false);
  });

  it("is local only for the exact value, ignoring case and whitespace", () => {
    vi.stubEnv("NEXT_PUBLIC_AUTH_MODE", "  Local ");
    expect(isLocalAuth()).toBe(true);
    expect(authMode()).toBe("local");
  });

  it("treats anything unrecognised as keycloak, so a typo can never open a different flow", () => {
    for (const value of ["locall", "none", "oidc", "true"]) {
      vi.stubEnv("NEXT_PUBLIC_AUTH_MODE", value);
      expect(authMode()).toBe("keycloak");
    }
  });
});
