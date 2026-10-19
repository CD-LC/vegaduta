// The providers page flags a credential the provider has been rejecting, with a "Replace key" way out.

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

vi.mock("../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("next/link", () => ({ default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a> }));
vi.mock("./AuthProvider", () => ({ useAuth: () => ({ hasPermission: () => true }) }));
vi.mock("../lib/api", () => {
  const empty = vi.fn(async () => null);
  return {
    api: new Proxy(
      {
        getTenantSettings: vi.fn(async () => ({ plan: "PREMIUM", experienceMode: "ADVANCED", usePlatformLlm: false })),
        platformLlm: vi.fn(async () => null),
        listElevenLabsVoices: vi.fn(async () => []),
      } as Record<string, unknown>,
      { get: (target, key: string) => target[key] ?? empty },
    ),
  };
});

import ProvidersPanel from "./ProvidersPanel";

afterEach(cleanup);

const rows = [
  { providerName: "OPENROUTER", status: "ACTIVE", createdAt: "2026-09-01T00:00:00Z", health: "ok", recentFailures: 0 },
  { providerName: "GOOGLE_VERTEX", status: "ACTIVE", createdAt: "2026-09-01T00:00:00Z", health: "rejected", recentFailures: 24, lastError: "401 invalid credentials" },
  { providerName: "ANTHROPIC", status: "ACTIVE", createdAt: "2026-09-01T00:00:00Z" },
];

describe("ProvidersPanel - rejected credentials", () => {
  it("badges only the rejected provider and offers Replace key on it", async () => {
    render(<ProvidersPanel providers={rows as never} onChanged={() => {}} />);
    const badges = await screen.findAllByTestId("provider-rejected-badge");
    expect(badges).toHaveLength(1);
    expect(badges[0].textContent).toContain("rejectedBadge");
    expect(badges[0].getAttribute("title")).toBe("401 invalid credentials");
    expect(screen.getAllByRole("button", { name: "replaceKey" })).toHaveLength(1);
  });

  it("Replace key opens the key form for that provider", async () => {
    render(<ProvidersPanel providers={rows as never} onChanged={() => {}} />);
    fireEvent.click(await screen.findByRole("button", { name: "replaceKey" }));
    expect(await screen.findByText("activateHeader")).toBeTruthy();
  });

  it("shows nothing extra when no provider reports health", async () => {
    render(<ProvidersPanel providers={rows.map(({ providerName, status, createdAt }) => ({ providerName, status, createdAt })) as never} onChanged={() => {}} />);
    await screen.findAllByText("ACTIVE");
    expect(screen.queryByTestId("provider-rejected-badge")).toBeNull();
  });
});
