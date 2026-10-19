// The "/" shim's one behavioural contract (2026-10): an authenticated visitor
// is redirected to /build, the signed-in home. The anonymous landing is
// AuthProvider's own branch and never reaches this component, so the test
// mocks useAuth ready-and-signed-in rather than standing the provider up.
// next/dynamic is stubbed entirely - the DashboardHome chunk and how it loads
// are Next internals, not what this guards.

import { render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => "/",
}));

vi.mock("next/dynamic", () => ({
  default: () => (): null => null,
}));

vi.mock("./components/AuthProvider", () => ({
  useAuth: (): { isAdmin: boolean } => ({ isAdmin: true }),
}));

vi.mock("./components/RouteTransition", () => ({
  RouteTransitionOverlay: (): null => null,
}));

const { personal } = vi.hoisted(() => ({ personal: { value: false } }));

vi.mock("./lib/personalInstall", () => ({
  isPersonalInstall: () => personal.value,
}));

import DashboardPage from "./page";

describe("DashboardPage", () => {
  it("redirects an authenticated visitor to /build", () => {
    render(<DashboardPage />);
    expect(replace).toHaveBeenCalledWith("/build");
  });

  it("redirects to /assistant on a personal install", () => {
    personal.value = true;
    render(<DashboardPage />);
    expect(replace).toHaveBeenCalledWith("/assistant");
    personal.value = false;
  });
});
