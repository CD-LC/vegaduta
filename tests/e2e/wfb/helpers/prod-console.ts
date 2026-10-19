import type { Page } from "@playwright/test";

/**
 * URL-aware console/network error collection for the production suite.
 *
 * Why not reuse helpers/console-errors.ts: that one filters by the CONSOLE
 * MESSAGE TEXT, and Chrome's message for a failed subresource is the bare
 * string "Failed to load resource: net::ERR_NAME_NOT_RESOLVED" - it carries
 * no URL at all. So its `cloudflareinsights` ignore pattern can never match,
 * and every page on production "fails" on the Cloudflare RUM beacon. This
 * collector attributes network failures via the request/response events,
 * where the URL is actually available, and ignores the generic console line.
 */

/** Third-party/infra noise that is not our code. Matched against REQUEST URLs. */
const IGNORED_REQUEST_URLS: RegExp[] = [
  /cloudflareinsights\.com/i,
  /beacon\.min\.js/i,
  /googletagmanager\.com/i,
  /google-analytics\.com/i,
  /api\.open-meteo\.com/i,
  /chrome-extension:\/\//i,
  /moz-extension:\/\//i,
];

/** Console text that is pure noise (extensions) or is redundant with a network event. */
const IGNORED_CONSOLE_TEXT: RegExp[] = [
  // Redundant: the URL-carrying network event is asserted separately below.
  /^Failed to load resource/i,
  /chrome-extension:\/\//i,
  /moz-extension:\/\//i,
];

/**
 * KNOWN, CURRENTLY-OPEN PRODUCTION ISSUES.
 *
 * These are real defects found by this suite, listed here so the suite stays
 * red for NEW regressions instead of being permanently red for known ones.
 * They are documented, not hidden - see README-PROD.md "Open production
 * findings". Delete an entry once the underlying bug is fixed and the suite
 * will start enforcing it.
 *
 * The 2026-08-16 finding 1 (public pages firing authenticated
 * /api/tenant/usage-quota + /api/notifications/unread-count calls that 401
 * with an [auth_failure] log) was verified fixed on live production on
 * 2026-08-28 (no such requests/logs on /help or /features while logged out)
 * - allowlist entries removed.
 */
export const KNOWN_PRODUCTION_ISSUES: RegExp[] = [];

export interface ProdErrorCollector {
  /** Genuine app console errors + uncaught exceptions. */
  consoleErrors: string[];
  /** Network requests that failed outright (DNS, connection, abort). */
  failedRequests: string[];
  /** Responses with a 4xx/5xx status. */
  badResponses: string[];
  /** Everything above, minus known-open production issues. */
  unexpected(): string[];
}

function matchesAny(value: string, patterns: RegExp[]): boolean {
  return patterns.some((re) => re.test(value));
}

/**
 * Attaches listeners for the lifetime of the page. Call BEFORE navigating so
 * nothing logged during the initial load is missed.
 */
export function collectProdErrors(page: Page): ProdErrorCollector {
  const consoleErrors: string[] = [];
  const failedRequests: string[] = [];
  const badResponses: string[] = [];

  page.on("console", (msg) => {
    if (msg.type() !== "error") return;
    const text = msg.text();
    if (matchesAny(text, IGNORED_CONSOLE_TEXT)) return;
    consoleErrors.push(text);
  });

  page.on("pageerror", (err) => {
    const text = err.message || String(err);
    if (matchesAny(text, IGNORED_CONSOLE_TEXT)) return;
    consoleErrors.push(`[pageerror] ${text}`);
  });

  page.on("requestfailed", (req) => {
    const url = req.url();
    if (matchesAny(url, IGNORED_REQUEST_URLS)) return;
    const failure = req.failure()?.errorText || "unknown";
    // Next.js prefetches (?_rsc=) are routinely aborted by the router when a
    // navigation supersedes them - that is normal behaviour, not an error.
    if (failure === "net::ERR_ABORTED") return;
    failedRequests.push(`${failure} ${url}`);
  });

  page.on("response", (res) => {
    const url = res.url();
    if (matchesAny(url, IGNORED_REQUEST_URLS)) return;
    if (res.status() >= 400) badResponses.push(`${res.status()} ${url}`);
  });

  return {
    consoleErrors,
    failedRequests,
    badResponses,
    unexpected() {
      return [...consoleErrors, ...failedRequests, ...badResponses].filter(
        (entry) => !matchesAny(entry, KNOWN_PRODUCTION_ISSUES)
      );
    },
  };
}
