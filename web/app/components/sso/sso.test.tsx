import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import en from "../../../messages/en.json";

const sso = vi.hoisted(() => ({
  status: vi.fn(), save: vi.fn(), remove: vi.fn(), listTokens: vi.fn(), mintToken: vi.fn(), revokeToken: vi.fn(),
}));
vi.mock("../../lib/api/sso", () => ({ ssoApi: sso }));
vi.mock("../AppShell", () => ({ default: ({ children }: { children?: ReactNode }) => <div>{children}</div> }));
vi.mock("../RouteTransition", () => ({ usePageReady: (): void => undefined }));
vi.mock("../useRequireScope", () => ({ useRequireScope: () => true }));
vi.mock("next/navigation", () => ({ usePathname: () => "/admin/sso" }));
vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
  },
}));

import SsoPage from "../../admin/sso/page";
import ScimTokensTable from "./ScimTokensTable";
import SsoConnectionForm, { validateSso } from "./SsoConnectionForm";

const status = (over: Record<string, unknown> = {}) => ({
  configured: false, providerType: null as string | null, displayName: null as string | null, alias: null as string | null, realmEnabled: null as boolean | null, clientSecretSet: false,
  brokerRedirectUri: "https://auth.example.com/broker/x/endpoint", ...over,
});
beforeEach(() => Object.values(sso).forEach((f) => f.mockReset()));
afterEach(cleanup);

describe("SSO page", () => {
  it("has the connection and SCIM tabs", async () => {
    sso.status.mockResolvedValue(status());
    sso.listTokens.mockResolvedValue([]);
    render(<SsoPage />);
    await screen.findByText("Not configured");
    fireEvent.click(screen.getByRole("tab", { name: "SCIM tokens" }));
    await screen.findByText("No SCIM tokens");
  });
});

describe("SsoConnectionForm", () => {
  const form = (over: Record<string, unknown> = {}) => ({
    providerType: "OIDC" as const, displayName: "Acme", enabled: true, oidcDiscoveryUrl: "https://i/.well-known", oidcAuthorizationUrl: "",
    oidcTokenUrl: "", oidcUserInfoUrl: "", oidcJwksUrl: "", oidcIssuer: "", oidcClientId: "cid", clientSecret: "s", samlEntityId: "",
    samlSsoUrl: "", samlX509Cert: "", samlNameIdFormat: "", ...over,
  });
  it("validates per protocol", () => {
    expect(validateSso(form(), false)).toBeNull();
    expect(validateSso(form({ displayName: " " }), false)).toBe("errDisplayName");
    expect(validateSso(form({ oidcDiscoveryUrl: "" }), false)).toBe("errOidcEndpoints");
    expect(validateSso(form({ clientSecret: "" }), false)).toBe("errClientSecret");
    expect(validateSso(form({ clientSecret: "" }), true)).toBeNull();
    expect(validateSso(form({ providerType: "SAML" }), false)).toBe("errSamlRequired");
  });
  it("saves an OIDC connection and clears the secret field", async () => {
    sso.status.mockResolvedValue(status());
    sso.save.mockResolvedValue(status({ configured: true, providerType: "OIDC", displayName: "Acme", alias: "acme", clientSecretSet: true }));
    render(<SsoConnectionForm />);
    await screen.findByText("Not configured");
    expect(screen.getByDisplayValue("https://auth.example.com/broker/x/endpoint")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "Acme" } });
    fireEvent.change(screen.getByLabelText("Discovery URL"), { target: { value: "https://idp/.well-known/openid-configuration" } });
    fireEvent.change(screen.getByLabelText("Client ID"), { target: { value: "cid" } });
    fireEvent.change(screen.getByLabelText("Client secret"), { target: { value: "shh" } });
    fireEvent.click(screen.getByRole("button", { name: "Save connection" }));
    await screen.findByText("SSO connection saved.");
    expect(sso.save.mock.calls[0][0]).toMatchObject({ providerType: "OIDC", displayName: "Acme", clientSecret: "shh", oidcClientId: "cid" });
    expect((screen.getByLabelText("Client secret") as HTMLInputElement).value).toBe("");
  });
  it("shows the server's plan error", async () => {
    sso.status.mockResolvedValue(status());
    sso.save.mockRejectedValue(new Error("Your plan does not include SSO"));
    render(<SsoConnectionForm />);
    await screen.findByText("Not configured");
    fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "A" } });
    fireEvent.change(screen.getByLabelText("Discovery URL"), { target: { value: "https://x" } });
    fireEvent.change(screen.getByLabelText("Client ID"), { target: { value: "c" } });
    fireEvent.change(screen.getByLabelText("Client secret"), { target: { value: "s" } });
    fireEvent.click(screen.getByRole("button", { name: "Save connection" }));
    await screen.findByText("Your plan does not include SSO");
  });
  it("removes a connection only after confirmation", async () => {
    sso.status.mockResolvedValue(status({ configured: true, providerType: "SAML", displayName: "Okta", alias: "okta", clientSecretSet: false }));
    sso.remove.mockResolvedValue(undefined);
    render(<SsoConnectionForm />);
    fireEvent.click(await screen.findByRole("button", { name: "Remove connection" }));
    const dialog = await screen.findByRole("alertdialog");
    expect(sso.remove).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Remove connection" }));
    await vi.waitFor(() => expect(sso.remove).toHaveBeenCalled());
  });
  it("shows an error with retry", async () => {
    sso.status.mockRejectedValueOnce(new Error("down")).mockResolvedValue(status());
    render(<SsoConnectionForm />);
    await screen.findByText("down");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    await screen.findByText("Not configured");
  });
});

describe("ScimTokensTable", () => {
  it("lists tokens, reveals a new one once, and revokes after confirmation", async () => {
    sso.listTokens.mockResolvedValue([{ id: "t1", label: "Okta", createdAt: "2026-10-01T00:00:00Z", lastUsedAt: null }]);
    sso.mintToken.mockResolvedValue({ id: "t2", token: "vdscim_secret", label: "Azure" });
    sso.revokeToken.mockResolvedValue(undefined);
    render(<ScimTokensTable />);
    await screen.findByText("Okta");
    expect(screen.getByText("Never")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Label"), { target: { value: "Azure" } });
    fireEvent.click(screen.getByRole("button", { name: "Create token" }));
    await screen.findByDisplayValue("vdscim_secret");
    expect(sso.mintToken).toHaveBeenCalledWith("Azure");
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByDisplayValue("vdscim_secret")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Revoke" }));
    const dialog = await screen.findByRole("alertdialog");
    expect(sso.revokeToken).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Revoke token" }));
    await vi.waitFor(() => expect(sso.revokeToken).toHaveBeenCalledWith("t1"));
  });
  it("shows a plan error when minting is refused", async () => {
    sso.listTokens.mockResolvedValue([]);
    sso.mintToken.mockRejectedValue(new Error("Your plan does not include SCIM provisioning"));
    render(<ScimTokensTable />);
    await screen.findByText("No SCIM tokens");
    fireEvent.click(screen.getByRole("button", { name: "Create token" }));
    await screen.findByText("Your plan does not include SCIM provisioning");
  });
});
