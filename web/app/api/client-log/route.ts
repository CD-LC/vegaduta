import { NextResponse } from "next/server";
import { logger } from "../../lib/logger";
import { clientIp, createRateLimiter, readCappedText } from "../../lib/requestGuards";

// Low-volume beacon endpoint for browser-side errors worth keeping (see
// app/lib/clientLogger.ts). Deliberately allow-listed by type rather
// than accepting an arbitrary free-text log — keeps volume and PII
// exposure low, per the build plan's PII/secret-scrubbing note.
const ALLOWED_TYPES = new Set(["auth_failure", "api_call_failed", "client_error", "api_backend_unreachable"]);

// This route is unauthenticated by design (it reports sign-in failures), so
// everything about the payload is bounded: an 8 KB body, and a context reduced
// to a few short scalar fields with control characters stripped - a nested
// object or a newline-laden string would otherwise let any anonymous caller
// flood or forge lines in the server log (security audit 2026-09-18).
const MAX_BODY_BYTES = 8 * 1024;
const MAX_CONTEXT_KEYS = 12;
const MAX_CONTEXT_VALUE_LENGTH = 300;

function clean(value: string, max: number): string {
   
  return value.replace(/[\u0000-\u001f\u007f]+/g, " ").slice(0, max);
}

function sanitizeContext(context: unknown): Record<string, string | number | boolean> | undefined {
  if (!context || typeof context !== "object" || Array.isArray(context)) return undefined;
  const out: Record<string, string | number | boolean> = {};
  for (const [key, value] of Object.entries(context as Record<string, unknown>).slice(0, MAX_CONTEXT_KEYS)) {
    const safeKey = clean(key, 40);
    if (typeof value === "string") out[safeKey] = clean(value, MAX_CONTEXT_VALUE_LENGTH);
    else if (typeof value === "number" || typeof value === "boolean") out[safeKey] = value;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

// Per-IP, so one client cannot flood the server logs through this endpoint.
const allow = createRateLimiter(60, 60 * 1000);

export async function POST(request: Request) {
  if (!allow(clientIp(request))) {
    return NextResponse.json({ error: "rate_limited" }, { status: 429 });
  }
  let body: unknown;
  try {
    // Capped while streaming - request.text() buffered the whole body before this check.
    const raw = await readCappedText(request, MAX_BODY_BYTES);
    if (raw === null) {
      return NextResponse.json({ error: "payload_too_large" }, { status: 413 });
    }
    body = JSON.parse(raw);
  } catch {
    return NextResponse.json({ error: "invalid_json" }, { status: 400 });
  }

  const { type, message, context } = (body ?? {}) as {
    type?: unknown;
    message?: unknown;
    context?: unknown;
  };

  if (typeof type !== "string" || !ALLOWED_TYPES.has(type)) {
    return NextResponse.json({ error: "unknown_type" }, { status: 400 });
  }

  logger.warn(
    {
      source: "client",
      type,
      context: sanitizeContext(context),
      userAgent: clean(request.headers.get("user-agent") || "", 300) || undefined,
    },
    typeof message === "string" ? clean(message, 2000) : "client-reported error"
  );

  return NextResponse.json({ ok: true });
}
