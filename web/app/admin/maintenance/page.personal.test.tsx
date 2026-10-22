import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

// A stable t: the page re-runs its refresh effect whenever t changes identity.
vi.mock("../../lib/i18n", () => {
  const t = (key: string) => key;
  return { useTranslations: () => t };
});
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("../../components/AuthProvider", () => ({
  useAuth: () => ({ isAdmin: true, hasPermission: () => true }),
}));
vi.mock("../../components/AppShell", () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock("../../components/RouteTransition", () => ({ usePageReady: () => {} }));
vi.mock("../../components/ax/PageHeader", () => ({
  default: ({ actions }: { actions: React.ReactNode }) => <div>{actions}</div>,
}));
vi.mock("../../components/dashboard/SystemStatusPanel", () => ({ default: (): null => null }));
vi.mock("../../components/dashboard/ConnectedPlatformsPanel", () => ({
  default: ({ platforms }: { platforms: { key: string }[] }) => (
    <ul data-testid="platforms">
      {platforms.map((p) => (
        <li key={p.key}>{p.key}</li>
      ))}
    </ul>
  ),
}));
vi.mock("../../components/AdminUsersPanel", () => ({ default: () => <div data-testid="users-panel" /> }));
vi.mock("../../components/ApprovalsPanel", () => ({ default: (): null => null }));
vi.mock("../../components/DataPrivacySection", () => ({ default: (): null => null }));
vi.mock("../../components/dashboard/dashboardStyles", () => ({}));

const calls: Record<string, ReturnType<typeof vi.fn>> = {};
vi.mock("../../lib/api", () => ({
  API_BASE: "http://core.test",
  api: new Proxy(
    {},
    {
      get: (_t, name: string) => (calls[name] ??= vi.fn(() => Promise.resolve(name === "listAdminUsers" ? [] : null))),
    }
  ),
}));

// PLATFORM_CHECKS is read once at module load, so each case stubs the edition and imports a fresh page.
async function loadPage(edition: string) {
  vi.resetModules();
  vi.unstubAllEnvs();
  vi.stubEnv("NEXT_PUBLIC_EDITION", edition);
  return (await import("./page")).default;
}

const invoked = (name: string) => calls[name]?.mock.calls.length ?? 0;

beforeEach(() => {
  for (const k of Object.keys(calls)) delete calls[k];
});

afterEach(() => {
  vi.unstubAllEnvs();
  delete window.__ENV;
});

describe("/admin/maintenance Users tab", () => {
  it("is hidden on a personal install, with no user list fetched and no slack/discord/x credential GETs", async () => {
    const PlatformMaintenancePage = await loadPage("personal");
    render(<PlatformMaintenancePage />);
    await waitFor(() => expect(invoked("getTwilioCredential")).toBe(1));
    expect(screen.queryByRole("tab", { name: "Users" })).toBeNull();
    expect(screen.getByRole("tab", { name: "Overview" })).toBeTruthy();
    expect(invoked("listAdminUsers")).toBe(0);
    for (const name of ["getSlackCredential", "getDiscordCredential", "getXCredential"]) expect(invoked(name), name).toBe(0);
  });

  it("stays on a full install, which also lists and probes everything", async () => {
    const PlatformMaintenancePage = await loadPage("full");
    render(<PlatformMaintenancePage />);
    await waitFor(() => expect(invoked("listAdminUsers")).toBe(1));
    expect(screen.getByRole("tab", { name: "Users" })).toBeTruthy();
    await waitFor(() => expect(invoked("getXCredential")).toBe(1));
    expect(invoked("getSlackCredential")).toBe(1);
  });
});
