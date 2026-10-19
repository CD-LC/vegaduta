import type { Page } from "@playwright/test";
import { SCHEME } from "./prod-env";

/**
 * Third-party / infra noise that is expected on the real deployed domain and
 * is not a regression in our own code: Cloudflare's RUM beacon
 * (cloudflareinsights.com / beacon.min.js), and errors thrown by browser
 * extensions injecting content scripts (chrome-extension://...). Anything
 * else logged at "error" level (or an uncaught page exception) is treated
 * as a real bug.
 */
const IGNORED_PATTERNS: RegExp[] = [
  /cloudflareinsights\.com/i,
  /beacon\.min\.js/i,
  /chrome-extension:\/\//i,
  /moz-extension:\/\//i,
  // Consequence of serving the console over plain http (kind stage, E2E_SCHEME=http): requestStorageAccess() is
  // refused in an insecure context. Real on an https deployment, so only ignored when the scheme is not https.
  ...(SCHEME === "https" ? [] : [/requestStorageAccess: May not be used in an insecure context/i]),
];

function isIgnored(text: string): boolean {
  return IGNORED_PATTERNS.some((re) => re.test(text));
}

export interface ConsoleErrorCollector {
  /** Console messages at level "error" plus uncaught `pageerror` exceptions, minus known noise. */
  errors: string[];
}

/**
 * Attaches listeners that collect real console/page errors for the lifetime
 * of the page. Call this BEFORE navigating so nothing logged during the
 * initial load is missed.
 */
export function collectConsoleErrors(page: Page): ConsoleErrorCollector {
  const collector: ConsoleErrorCollector = { errors: [] };

  page.on("console", (msg) => {
    if (msg.type() !== "error") return;
    const text = msg.text();
    if (isIgnored(text)) return;
    collector.errors.push(text);
  });

  page.on("pageerror", (err) => {
    const text = err.message || String(err);
    if (isIgnored(text)) return;
    collector.errors.push(text);
  });

  return collector;
}
