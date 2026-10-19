// Prod signed-in pass 2026-10-05, bug 8: /features, /help, /templates,
// /directory, /plugins, /code and /forms/<token> logged 401s for
// /api/tenant/settings (and, on /features and /help, the three sidebar
// capability calls). Authenticated background calls were firing with no
// session: gated on the "vd_authed" localStorage hint, or not gated at all.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";

// A stand-in for the server: with no session every one of these 401s.
const session = { live: false };
const unauthorized = () => Promise.reject(new Error("401"));
const apiMock = {
  getTenantSettings: vi.fn(() => (session.live ? Promise.resolve({ plan: "PRO" }) : unauthorized())),
  platformLlm: vi.fn(() => Promise.resolve({ available: false })),
  getDataTablesConfig: vi.fn(() => (session.live ? Promise.resolve({ enabled: true }) : unauthorized())),
  getScheduledTasksConfig: vi.fn(() => (session.live ? Promise.resolve({ enabled: true }) : unauthorized())),
  getCodingTasksConfig: vi.fn(() => (session.live ? Promise.resolve({ enabled: true }) : unauthorized())),
};
vi.mock("../lib/api", () => ({ api: apiMock }));
vi.mock("../lib/toastBus", () => ({ toast: { info: vi.fn() } }));
// The real AuthProvider drags in keycloak-js; only its context object matters here.
vi.mock("./AuthProvider", async () => {
  const React = await import("react");
  return { AuthContext: React.createContext(null) };
});

async function load() {
  const { AuthContext } = (await import("./AuthProvider")) as unknown as {
    AuthContext: React.Context<{ username: string | null } | null>;
  };
  const { PlatformModelChangeNotice } = await import("./PlatformModelChangeNotice");
  const { useDataTablesEnabled } = await import("./dataTables/useDataTablesEnabled");
  const { useScheduledTasksEnabled } = await import("./scheduledTasks/useScheduledTasksEnabled");
  const { useCodingTasksEnabled } = await import("./codingTasks/useCodingTasksEnabled");

  // What AppShell's sidebar does with the three flags.
  function Sidebar() {
    const tables = useDataTablesEnabled();
    const scheduled = useScheduledTasksEnabled();
    const coding = useCodingTasksEnabled();
    return <nav data-testid="nav">{[tables && "tables", scheduled && "scheduled", coding && "coding"].filter(Boolean).join(",")}</nav>;
  }
  const Page = () => (
    <>
      <PlatformModelChangeNotice />
      <Sidebar />
    </>
  );
  const withAuth = (username: string | null, children: ReactNode) => (
    <AuthContext.Provider value={{ username }}>{children}</AuthContext.Provider>
  );
  return { Page, withAuth };
}

const allCalls = () => Object.values(apiMock).reduce((n, fn) => n + fn.mock.calls.length, 0);

describe("authenticated background calls wait for a real session (bug 8)", () => {
  beforeEach(() => {
    vi.resetModules(); // the capability hooks cache their answer per module
    Object.values(apiMock).forEach((fn) => fn.mockClear());
    session.live = false;
    // A returning user: the hint is set on every one of these page loads.
    window.localStorage.setItem("vd_authed", "1");
  });
  afterEach(() => {
    cleanup();
    window.localStorage.clear();
  });

  it("fires nothing on a PUBLIC route (no AuthContext at all), even with the signed-in hint", async () => {
    const { Page } = await load();
    render(<Page />);
    await act(async () => {
      await Promise.resolve();
    });
    expect(allCalls()).toBe(0);
  });

  it("fires nothing on /features or /help while the session is unresolved or absent", async () => {
    const { Page, withAuth } = await load();
    render(withAuth(null, <Page />));
    await act(async () => {
      await Promise.resolve();
    });
    expect(allCalls()).toBe(0);
  });

  it("asks once the session is ready, and the nav items appear (no cached 'off' from an early 401)", async () => {
    const { Page, withAuth } = await load();
    const view = render(withAuth(null, <Page />)); // Keycloak still resolving
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.getByTestId("nav").textContent).toBe("");

    session.live = true; // token is attached from here on
    view.rerender(withAuth("asha", <Page />));

    await waitFor(() => expect(screen.getByTestId("nav").textContent).toBe("tables,scheduled,coding"));
    expect(apiMock.getTenantSettings).toHaveBeenCalledTimes(1);
    expect(apiMock.getDataTablesConfig).toHaveBeenCalledTimes(1);
    expect(apiMock.getScheduledTasksConfig).toHaveBeenCalledTimes(1);
    expect(apiMock.getCodingTasksConfig).toHaveBeenCalledTimes(1);
  });
});
