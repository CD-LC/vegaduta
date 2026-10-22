import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

const nav = vi.hoisted(() => ({ pathname: "/platform-admin/llm-providers" }));

vi.mock("next/navigation", () => ({ usePathname: () => nav.pathname }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: any) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));
vi.mock("../../lib/auth", () => ({ auth: { isAuthenticated: () => true, login: vi.fn(), logout: vi.fn() } }));
vi.mock("../../components/AuthProvider", async () => {
  const { createContext } = await import("react");
  return { AuthContext: createContext({ isPlatformAdmin: true, username: "owner", roles: ["platform-admin"] }) };
});

import PlatformAdminGate from "./PlatformAdminGate";

const at = (pathname: string) => {
  nav.pathname = pathname;
  render(
    <PlatformAdminGate>
      <p>page body</p>
    </PlatformAdminGate>
  );
};

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  delete window.__ENV;
});

describe("PlatformAdminGate edition allowlist", () => {
  it.each(["/platform-admin/llm-providers", "/platform-admin/tool-credentials", "/platform-admin/settings"])(
    "renders %s on a personal install",
    (path) => {
      vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
      at(path);
      expect(screen.getByText("page body")).toBeInTheDocument();
    }
  );

  it.each(["/platform-admin/tenants", "/platform-admin/billing", "/platform-admin/ops", "/platform-admin/users"])(
    "renders not-found for %s on a personal install",
    (path) => {
      vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
      at(path);
      expect(screen.queryByText("page body")).not.toBeInTheDocument();
      expect(screen.getByText("This admin page does not exist.")).toBeInTheDocument();
    }
  );

  it("renders every page on the lite and full tiers", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "full");
    at("/platform-admin/tenants");
    expect(screen.getByText("page body")).toBeInTheDocument();
  });

  it("renders every page when no edition is declared", () => {
    at("/platform-admin/tenants");
    expect(screen.getByText("page body")).toBeInTheDocument();
  });
});
