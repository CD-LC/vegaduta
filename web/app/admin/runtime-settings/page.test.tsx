// The /admin/runtime-settings route: derives the viewer's settings role from the signed-in user, renders the real
// settings screen against a faked settings API, and links to the docs.
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

const auth = { isAdmin: false, isPlatformAdmin: false, hasPermission: (_: string) => false };
vi.mock("../../components/AuthProvider", () => ({ useAuth: () => auth }));
vi.mock("../../components/AppShell", () => ({ default: ({ children }: { children: ReactNode }) => <div>{children}</div> }));
vi.mock("next/navigation", () => ({ usePathname: () => "/admin/runtime-settings", useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));

import type * as Settings from "../../lib/api/settings";
import { I18nProvider } from "../../lib/i18n";
import { MESSAGES, makeClient } from "../../components/settings/settingsTestKit";

const fake = makeClient();
vi.mock("../../lib/api/settings", async (importActual) => {
  const actual = await importActual<typeof Settings>();
  return { ...actual, get settingsApi() { return fake; } };
});

import AdminRuntimeSettingsPage from "./page";
import { settingsRoleFor } from "./settingsRole";

const renderPage = () =>
  render(
    <I18nProvider locale="en" messages={MESSAGES} enMessages={MESSAGES} complete>
      <AdminRuntimeSettingsPage />
    </I18nProvider>
  );

afterEach(() => {
  cleanup();
  auth.isAdmin = false;
  auth.isPlatformAdmin = false;
  auth.hasPermission = () => false;
});

describe("settingsRoleFor", () => {
  it("maps the signed-in user to the role the settings API will apply", () => {
    expect(settingsRoleFor({ isAdmin: true, isPlatformAdmin: false, hasPermission: () => false })).toBe("TENANT_ADMIN");
    expect(settingsRoleFor({ isAdmin: false, isPlatformAdmin: true, hasPermission: () => false })).toBe("TENANT_ADMIN");
    expect(settingsRoleFor({ isAdmin: false, isPlatformAdmin: false, hasPermission: (n) => n === "MANAGE_AGENTS" })).toBe("EDITOR");
    expect(settingsRoleFor({ isAdmin: false, isPlatformAdmin: false, hasPermission: () => false })).toBe("VIEWER");
  });
});

describe("/admin/runtime-settings", () => {
  it("renders the header, the docs link and an editable screen for a tenant admin", async () => {
    auth.isAdmin = true;
    renderPage();
    expect(screen.getByRole("heading", { level: 1, name: "Runtime settings" })).toBeInTheDocument();
    expect(screen.getByTestId("runtime-docs-link")).toHaveAttribute("href", "/help/docs");
    const steps = await screen.findByRole("spinbutton", { name: "Max tool steps" });
    expect(steps).not.toHaveAttribute("readonly");
    expect(screen.queryByTestId("runtime-readonly")).toBeNull();
  });

  it("renders the same values read-only for a member without admin rights", async () => {
    renderPage();
    const steps = await screen.findByRole("spinbutton", { name: "Max tool steps" });
    expect(steps).toHaveAttribute("readonly");
    expect(screen.getByTestId("runtime-readonly")).toBeInTheDocument();
  });
});
