import type { APIRequestContext, BrowserContext } from "@playwright/test";
import { API_BASE_URL } from "./e2e-env";

/**
 * Demo-sandbox helpers for the /try + shared-agent (@pending-deploy) specs.
 * requires V131 + 2743b59/6d3b474 deployed.
 *
 * The sandbox surface is unauthenticated by design: POST /api/demo/session
 * provisions a real, rate-limited 5-day tenant and returns the raw demo token
 * exactly once (only its SHA-256 is stored server-side — see
 * web/app/components/try/demoClient.ts). These helpers create a sandbox via
 * the API (much faster and cheaper than driving the /try provisioning UI) and
 * seed its token into localStorage so /try resumes it on page load.
 *
 * IMPORTANT — rate limits: every createDemoSession() call burns one of the
 * source IP's rate-limited sandbox slots (DemoSessionRateLimiter). Specs that
 * use these helpers should (a) create at most one sandbox per test, (b) run on
 * a single Playwright project (chromium) rather than once per project, and
 * (c) treat a null return as "environment declined" and skip, not fail.
 */

// The web app's own build bakes NEXT_PUBLIC_API_BASE_URL; this suite mirrors
// that with an env var so it can follow E2E_BASE_URL to a different
// environment. Never localhost on a deployed environment.
export const DEMO_API_BASE =
  API_BASE_URL;

/** Same key /try reads on mount to resume an existing sandbox. */
export const DEMO_TOKEN_KEY = "vegaduta.demoToken";

export type DemoSessionSeed = {
  token: string;
  agentName: string | null;
};

/**
 * Provisions a fresh demo sandbox. Returns null when the environment declines
 * (rate limited, sandbox disabled, core unreachable) — callers should
 * `test.skip(!seed, ...)` rather than fail, because a declined sandbox is an
 * environment condition, not a regression in the feature under test.
 */
export async function createDemoSession(
  request: APIRequestContext,
  persona?: string
): Promise<DemoSessionSeed | null> {
  try {
    const res = await request.post(`${DEMO_API_BASE}/api/demo/session`, {
      data: persona ? { persona } : {},
    });
    if (!res.ok()) return null;
    const body = await res.json().catch(() => null);
    if (!body || typeof body.demoToken !== "string" || !body.demoToken) return null;
    return {
      token: body.demoToken,
      agentName: typeof body.agentName === "string" ? body.agentName : null,
    };
  } catch {
    return null;
  }
}

/**
 * POST /api/demo/session/share — mints (or re-returns) the public share link
 * for this sandbox's agent. Returns the absolute shareUrl, or null on any
 * failure.
 */
export async function createShareLink(
  request: APIRequestContext,
  token: string
): Promise<string | null> {
  try {
    const res = await request.post(`${DEMO_API_BASE}/api/demo/session/share`, {
      headers: { "X-Demo-Token": token },
    });
    if (!res.ok()) return null;
    const body = await res.json().catch(() => null);
    if (!body || typeof body.shareUrl !== "string" || !body.shareUrl) return null;
    return body.shareUrl;
  } catch {
    return null;
  }
}

/**
 * Seeds the demo token into localStorage BEFORE any page script runs, so a
 * subsequent page.goto("/try") takes the resume path instead of provisioning
 * a second sandbox. Must be called before the first navigation in the context.
 */
export async function seedDemoToken(
  context: BrowserContext,
  token: string
): Promise<void> {
  await context.addInitScript(
    ({ key, value }: { key: string; value: string }) => {
      try {
        window.localStorage.setItem(key, value);
      } catch {
        /* storage blocked — the spec will provision via the UI instead */
      }
    },
    { key: DEMO_TOKEN_KEY, value: token }
  );
}

/**
 * The path component of an absolute shareUrl, so specs can navigate against
 * baseURL (and E2E_BASE_URL overrides keep working) instead of hard-coding
 * the production app host.
 */
export function sharePath(shareUrl: string): string {
  const u = new URL(shareUrl);
  return u.pathname + u.search;
}
