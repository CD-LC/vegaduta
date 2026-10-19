import { describe, expect, it, vi } from "vitest";

vi.mock("../lib/auth", () => ({ auth: { getUserId: () => "user-1" } }));

import { signingInUrl } from "./TwoFactorButton";

describe("signingInUrl", () => {
  it("points keycloak-js's account URL at the Signing in page, keeping the referrer", () => {
    const url = signingInUrl(
      "https://auth.vegaduta.ai/realms/agentic-ai/account?referrer=agentic-ai-web&referrer_uri=https%3A%2F%2Fapp.vegaduta.ai%2F",
    );
    expect(url).toBe(
      "https://auth.vegaduta.ai/realms/agentic-ai/account/account-security/signing-in?referrer=agentic-ai-web&referrer_uri=https%3A%2F%2Fapp.vegaduta.ai%2F",
    );
  });

  it("handles a trailing slash", () => {
    expect(signingInUrl("https://auth.vegaduta.ai/realms/agentic-ai/account/")).toBe(
      "https://auth.vegaduta.ai/realms/agentic-ai/account/account-security/signing-in",
    );
  });

  it("returns null when there is no account URL yet, so the button hides instead of linking nowhere", () => {
    expect(signingInUrl(null)).toBeNull();
    expect(signingInUrl("not a url")).toBeNull();
  });
});
