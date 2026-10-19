import { NextResponse } from "next/server";
import { logger } from "../../lib/logger";
import { clientIp, createRateLimiter, readCappedText } from "../../lib/requestGuards";

// Collects Content-Security-Policy violation reports (security audit 2026-10).
// The report-only policy in next.config.js had no report-uri, so its evidence
// only ever reached individual browser consoles and could never justify
// promoting a directive to enforced. Each report is logged as ONE line with
// only the directive, the blocked ORIGIN and the page PATH - never full URLs,
// which can carry tokens or personal data in their query strings.

const MAX_BODY_BYTES = 8 * 1024;
const allow = createRateLimiter(30, 60 * 1000);

function originOf(value: unknown): string | undefined {
  if (typeof value !== "string" || value.length === 0) return undefined;
  try {
    return new URL(value).origin;
  } catch {
    // "inline", "eval", "data", "blob" - keywords, not URLs
    return value.replace(/[^a-z-]/gi, "").slice(0, 20) || undefined;
  }
}

function pathOf(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  try {
    return new URL(value).pathname.slice(0, 200);
  } catch {
    return undefined;
  }
}

type Report = Record<string, unknown>;

// Legacy report-uri bodies are {"csp-report": {...}} with kebab-case keys;
// Reporting API (report-to) bodies are [{type: "csp-violation", body: {...}}] in camelCase.
function extract(parsed: unknown): Report[] {
  if (Array.isArray(parsed)) {
    return parsed
      .filter((r) => r && typeof r === "object" && (r as Report).type === "csp-violation")
      .map((r) => ((r as Report).body ?? {}) as Report)
      .slice(0, 10);
  }
  if (parsed && typeof parsed === "object" && "csp-report" in parsed) {
    return [(parsed as Report)["csp-report"] as Report];
  }
  return [];
}

export async function POST(request: Request) {
  if (!allow(clientIp(request))) {
    return new NextResponse(null, { status: 204 });
  }
  const text = await readCappedText(request, MAX_BODY_BYTES);
  if (text === null) {
    return new NextResponse(null, { status: 413 });
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return new NextResponse(null, { status: 400 });
  }
  for (const r of extract(parsed)) {
    const directive = r["effective-directive"] ?? r["violated-directive"] ?? r.effectiveDirective;
    logger.warn(
      {
        source: "csp",
        directive: typeof directive === "string" ? directive.slice(0, 40) : undefined,
        blocked: originOf(r["blocked-uri"] ?? r.blockedURL),
        page: pathOf(r["document-uri"] ?? r.documentURL),
        disposition: typeof r.disposition === "string" ? r.disposition.slice(0, 10) : undefined,
      },
      "csp violation"
    );
  }
  return new NextResponse(null, { status: 204 });
}
