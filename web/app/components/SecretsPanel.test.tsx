// The vault panel's security-relevant behaviour, not its copy.
//
// These tests exist because the three things most likely to be "improved" into
// a leak later are all invisible in a screenshot: that no plaintext value is
// ever rendered or requested, that the copy button copies the REFERENCE rather
// than anything secret, and that a non-admin cannot aim a write at workspace
// scope. Each of those gets an assertion here so a regression fails the build
// instead of shipping.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { readFileSync } from "node:fs";

const listSecrets = vi.fn();
const storeSecret = vi.fn();
const revokeSecret = vi.fn();
const purgeSecret = vi.fn();
const updateSecretAllowedHosts = vi.fn();

vi.mock("../lib/api", () => ({
  api: {
    get listSecrets() { return listSecrets; },
    get storeSecret() { return storeSecret; },
    get revokeSecret() { return revokeSecret; },
    get purgeSecret() { return purgeSecret; },
    get updateSecretAllowedHosts() { return updateSecretAllowedHosts; },
  },
}));

// t() returns the key, so assertions bind to keys rather than to English copy
// that product may reword at any time.
vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

let mockIsAdmin = true;
// Granular RBAC (2026-09-23): the panel now asks hasPermission(
// "MANAGE_INTEGRATIONS") - the mock answers it from the same flag the old
// isAdmin mock drove, so every test below keeps its meaning.
vi.mock("./AuthProvider", () => ({
  useAuth: () => ({ hasPermission: () => mockIsAdmin }),
}));

import SecretsPanel from "./SecretsPanel";
import type { VaultSecret } from "../lib/api";

function secret(over: Partial<VaultSecret> = {}): VaultSecret {
  return {
    id: "id-1",
    name: "STRIPE_KEY",
    reference: "{{secret.STRIPE_KEY}}",
    scope: "TENANT",
    kind: "API_KEY",
    description: "Billing",
    preview: "4242",
    status: "ACTIVE",
    codeToolAccess: false,
    expiresAt: null,
    expired: false,
    lastUsedAt: null,
    rotatedAt: null,
    createdBy: "u1",
    createdAt: "2026-09-12T00:00:00Z",
    updatedAt: "2026-09-12T00:00:00Z",
    ...over,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockIsAdmin = true;
});

describe("SecretsPanel", () => {
  it("renders only the preview, never a value, and offers no way to ask for one", () => {
    const { container } = render(<SecretsPanel secrets={[secret()]} onChanged={vi.fn()} />);

    // The preview is shown...
    expect(screen.getByText(/previewLabel/)).toBeInTheDocument();
    // ...and the real api.ts (not the mock above - reading the source is the
    // whole point) still exposes no read-a-value call. If someone adds a
    // revealSecret/getSecretValue endpoint wrapper later, this fails here
    // rather than quietly becoming available to this panel.
    // Path is relative to vitest's cwd (web/), not to this file - under the
    // vite transform `import.meta.url` is not a file:// URL. Line comments are
    // stripped first, because api.ts documents *why* there is no reveal call
    // and the prose would otherwise match the very pattern being banned.
    const apiSource = readFileSync("app/lib/api.ts", "utf8").replace(/^\s*\/\/.*$/gm, "");
    expect(apiSource).not.toMatch(/(reveal[A-Za-z]*Secret|getSecret|getSecretValue|secretValue)\s*:/i);
    // No password-type input exists outside the add form (which is closed).
    expect(container.querySelectorAll('input[type="password"]')).toHaveLength(0);
  });

  it("copies the reference string, never the secret name alone or a value", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });

    render(<SecretsPanel secrets={[secret()]} onChanged={vi.fn()} />);
    fireEvent.click(screen.getByTestId("secret-copy-STRIPE_KEY"));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith("{{secret.STRIPE_KEY}}"));
  });

  it("a non-admin cannot aim a write at workspace scope", () => {
    mockIsAdmin = false;
    render(<SecretsPanel secrets={[]} onChanged={vi.fn()} />);
    fireEvent.click(screen.getByTestId("secrets-add"));

    const tenant = screen.getByTestId("secrets-scope-tenant") as HTMLButtonElement;
    expect(tenant.disabled).toBe(true);
    // ...and the effective selection is the personal one, so submitting cannot
    // produce a request the server is going to 403.
    expect(screen.getByTestId("secrets-scope-user")).toHaveAttribute("aria-pressed", "true");
  });

  it("rejects a malformed name client-side without calling the API", async () => {
    render(<SecretsPanel secrets={[]} onChanged={vi.fn()} />);
    fireEvent.click(screen.getByTestId("secrets-add"));

    // The input upper-cases as you type, so force a genuinely invalid value:
    // a leading digit, which VALID_NAME rejects.
    const name = screen.getByLabelText("nameLabel");
    fireEvent.change(name, { target: { value: "1BAD" } });
    fireEvent.change(screen.getByLabelText("valueLabel"), { target: { value: "sk_live_x" } });
    fireEvent.submit(screen.getByTestId("secrets-form"));

    await waitFor(() => expect(screen.getByTestId("secrets-error")).toBeInTheDocument());
    expect(storeSecret).not.toHaveBeenCalled();
  });

  it("stores, then clears the plaintext out of component state", async () => {
    storeSecret.mockResolvedValue(secret());
    const onChanged = vi.fn();
    render(<SecretsPanel secrets={[]} onChanged={onChanged} />);
    fireEvent.click(screen.getByTestId("secrets-add"));

    fireEvent.change(screen.getByLabelText("nameLabel"), { target: { value: "STRIPE_KEY" } });
    const value = screen.getByLabelText("valueLabel") as HTMLInputElement;
    fireEvent.change(value, { target: { value: "sk_live_secret" } });
    fireEvent.submit(screen.getByTestId("secrets-form"));

    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(storeSecret).toHaveBeenCalledWith(
      expect.objectContaining({ name: "STRIPE_KEY", value: "sk_live_secret", scope: "TENANT" }),
    );
    // Form is closed and the value no longer lives in any rendered input.
    expect(screen.queryByTestId("secrets-form")).toBeNull();
  });

  it("revokes an active secret and purges a revoked one - never the other way round", async () => {
    revokeSecret.mockResolvedValue(undefined);
    const { rerender } = render(<SecretsPanel secrets={[secret()]} onChanged={vi.fn()} />);

    // ACTIVE offers revoke, not purge.
    expect(screen.getByTestId("secret-revoke-STRIPE_KEY")).toBeInTheDocument();
    expect(screen.queryByTestId("secret-purge-STRIPE_KEY")).toBeNull();

    // REVOKED offers the irreversible one instead.
    rerender(<SecretsPanel secrets={[secret({ status: "REVOKED" })]} onChanged={vi.fn()} />);
    expect(screen.getByTestId("secret-purge-STRIPE_KEY")).toBeInTheDocument();
    expect(screen.queryByTestId("secret-revoke-STRIPE_KEY")).toBeNull();
  });

  it("marks an expired secret as unusable even while its status is still ACTIVE", () => {
    render(<SecretsPanel secrets={[secret({ expired: true })]} onChanged={vi.fn()} />);
    expect(screen.getByText("statusExpired")).toBeInTheDocument();
  });

  it("the code-tool opt-in is off by default and is sent as false", async () => {
    storeSecret.mockResolvedValue(secret());
    render(<SecretsPanel secrets={[]} onChanged={vi.fn()} />);
    fireEvent.click(screen.getByTestId("secrets-add"));

    expect(screen.getByTestId("secrets-codetool-toggle")).toHaveAttribute("aria-pressed", "false");

    fireEvent.change(screen.getByLabelText("nameLabel"), { target: { value: "STRIPE_KEY" } });
    fireEvent.change(screen.getByLabelText("valueLabel"), { target: { value: "sk_live_secret" } });
    fireEvent.submit(screen.getByTestId("secrets-form"));

    // Default-off is the security property: a secret nobody thought about is
    // unreachable from sandboxed tenant code.
    await waitFor(() => expect(storeSecret).toHaveBeenCalled());
    expect(storeSecret).toHaveBeenCalledWith(expect.objectContaining({ codeToolAccess: false }));
  });

  describe("allowed hosts (SEC-006)", () => {
    it("warns on an active secret with no allowed hosts, and shows chips once set", () => {
      const { rerender } = render(<SecretsPanel secrets={[secret({ allowedHosts: [] })]} onChanged={vi.fn()} />);
      expect(screen.getByTestId("secret-nohosts-STRIPE_KEY")).toBeInTheDocument();
      expect(screen.queryByTestId("secret-hosts-STRIPE_KEY")).toBeNull();

      rerender(<SecretsPanel secrets={[secret({ allowedHosts: ["api.stripe.com", "*.example.com"] })]} onChanged={vi.fn()} />);
      expect(screen.queryByTestId("secret-nohosts-STRIPE_KEY")).toBeNull();
      expect(screen.getByTestId("secret-hosts-STRIPE_KEY")).toHaveTextContent("api.stripe.com");
      expect(screen.getByTestId("secret-hosts-STRIPE_KEY")).toHaveTextContent("*.example.com");
    });

    it("treats a core that omits allowedHosts as none (warning shown)", () => {
      render(<SecretsPanel secrets={[secret()]} onChanged={vi.fn()} />);
      expect(screen.getByTestId("secret-nohosts-STRIPE_KEY")).toBeInTheDocument();
    });

    it("sends the normalised host list when creating a secret", async () => {
      storeSecret.mockResolvedValue(secret());
      render(<SecretsPanel secrets={[]} onChanged={vi.fn()} />);
      fireEvent.click(screen.getByTestId("secrets-add"));
      fireEvent.change(screen.getByLabelText("nameLabel"), { target: { value: "STRIPE_KEY" } });
      fireEvent.change(screen.getByLabelText("valueLabel"), { target: { value: "sk_live_secret" } });
      fireEvent.change(screen.getByTestId("secrets-allowed-hosts"), { target: { value: "API.Stripe.com, *.example.com" } });
      fireEvent.submit(screen.getByTestId("secrets-form"));

      await waitFor(() => expect(storeSecret).toHaveBeenCalled());
      expect(storeSecret).toHaveBeenCalledWith(expect.objectContaining({ allowedHosts: ["api.stripe.com", "*.example.com"] }));
    });

    it("blocks create on an invalid host pattern without calling the API", async () => {
      render(<SecretsPanel secrets={[]} onChanged={vi.fn()} />);
      fireEvent.click(screen.getByTestId("secrets-add"));
      fireEvent.change(screen.getByLabelText("nameLabel"), { target: { value: "STRIPE_KEY" } });
      fireEvent.change(screen.getByLabelText("valueLabel"), { target: { value: "sk_live_secret" } });
      fireEvent.change(screen.getByTestId("secrets-allowed-hosts"), { target: { value: "https://evil.example" } });
      fireEvent.submit(screen.getByTestId("secrets-form"));

      await waitFor(() => expect(screen.getByTestId("secrets-error")).toBeInTheDocument());
      expect(storeSecret).not.toHaveBeenCalled();
    });

    it("edits hosts in place via PUT and refreshes", async () => {
      updateSecretAllowedHosts.mockResolvedValue(secret({ allowedHosts: ["api.stripe.com"] }));
      const onChanged = vi.fn();
      render(<SecretsPanel secrets={[secret({ allowedHosts: [] })]} onChanged={onChanged} />);
      fireEvent.click(screen.getByTestId("secret-edit-hosts-STRIPE_KEY"));
      fireEvent.change(screen.getByTestId("secret-hosts-input-STRIPE_KEY"), { target: { value: "api.stripe.com" } });
      fireEvent.click(screen.getByTestId("secret-hosts-save-STRIPE_KEY"));

      await waitFor(() => expect(onChanged).toHaveBeenCalled());
      expect(updateSecretAllowedHosts).toHaveBeenCalledWith("id-1", ["api.stripe.com"]);
    });

    it("does not call the API for an invalid edit", () => {
      render(<SecretsPanel secrets={[secret({ allowedHosts: [] })]} onChanged={vi.fn()} />);
      fireEvent.click(screen.getByTestId("secret-edit-hosts-STRIPE_KEY"));
      fireEvent.change(screen.getByTestId("secret-hosts-input-STRIPE_KEY"), { target: { value: "*" } });
      fireEvent.click(screen.getByTestId("secret-hosts-save-STRIPE_KEY"));
      expect(updateSecretAllowedHosts).not.toHaveBeenCalled();
      expect(screen.getByText("hostsError_wildcardPosition")).toBeInTheDocument();
    });
  });
});
