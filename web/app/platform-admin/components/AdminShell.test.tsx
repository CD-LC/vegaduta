import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

const apiMock = vi.hoisted(() => ({
  getPlatformHealth: vi.fn(async () => ({})),
  listPendingMcpReviews: vi.fn(async () => []),
  getAdminTemplatesPendingQuiet: vi.fn(async () => []),
  getAbuseThrottled: vi.fn(async () => []),
  getKillSwitchStatus: vi.fn(async () => ({ stopped: false })),
  resumePlatform: vi.fn(),
}));
const prefetchApiGets = vi.hoisted(() => vi.fn());

vi.mock("../lib/api", () => ({ api: apiMock, prefetchApiGets }));
vi.mock("./PlatformAdminGate", () => ({
  useAuth: () => ({ username: "owner", roles: [] as string[], logout: vi.fn() }),
}));
vi.mock("next/navigation", () => ({ usePathname: () => "/platform-admin/llm-providers" }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: any) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));
vi.mock("./BrandIdentityModal", () => ({ default: (): null => null }));
vi.mock("./NotificationBell", () => ({ default: (): null => null }));
vi.mock("./admin/ConfirmDialog", () => ({ useConfirm: () => vi.fn() }));

// The prefetch guard is module state (once per identity), so each test loads a fresh AdminShell.
async function renderShell() {
  vi.resetModules();
  const { default: AdminShell } = await import("./AdminShell");
  render(<AdminShell>content</AdminShell>);
}

const prefetched = (): string[] => prefetchApiGets.mock.calls.flatMap((c) => c[0] as string[]);

beforeEach(() => {
  window.localStorage.clear();
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: true,
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })) as unknown as typeof window.matchMedia;
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllEnvs();
  delete window.__ENV;
});

describe("AdminShell on a personal install", () => {
  it("skips the operator queue reads and the spend prefetch, keeps health and the kill switch", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    await renderShell();
    await waitFor(() => expect(apiMock.getKillSwitchStatus).toHaveBeenCalled());

    expect(apiMock.listPendingMcpReviews).not.toHaveBeenCalled();
    expect(apiMock.getAdminTemplatesPendingQuiet).not.toHaveBeenCalled();
    expect(apiMock.getAbuseThrottled).not.toHaveBeenCalled();
    expect(apiMock.getPlatformHealth).toHaveBeenCalled();

    const urls = prefetched();
    expect(urls).not.toContain("/api/platform/spend/summary");
    expect(urls).not.toContain("/api/platform/mcp-review/pending");
    expect(urls).not.toContain("/api/platform/abuse-monitor/throttled");
    expect(urls).toContain("/api/platform/health");
    expect(urls).toContain("/api/platform/kill-switch");
  });

  it("shows only the allowlisted destinations in the rail", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    await renderShell();
    const nav = screen.getByRole("navigation", { name: /platform admin sections/i });
    const hrefs = Array.from(nav.querySelectorAll("a"))
      .map((a) => a.getAttribute("href"))
      .filter((h) => h !== "/platform-admin"); // the wordmark, which redirects to the LLM pools
    expect(hrefs.sort()).toEqual(
      ["/platform-admin/llm-providers", "/platform-admin/settings", "/platform-admin/tool-credentials"].sort()
    );
  });
});

describe("AdminShell on a shared install", () => {
  it("still reads the queues and prefetches the spend summary", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "full");
    await renderShell();
    await waitFor(() => expect(apiMock.getAbuseThrottled).toHaveBeenCalled());

    expect(apiMock.listPendingMcpReviews).toHaveBeenCalled();
    expect(apiMock.getAdminTemplatesPendingQuiet).toHaveBeenCalled();
    expect(prefetched()).toEqual(
      expect.arrayContaining([
        "/api/platform/spend/summary",
        "/api/platform/mcp-review/pending",
        "/api/platform/abuse-monitor/throttled",
      ])
    );
  });
});
