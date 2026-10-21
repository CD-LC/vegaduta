import { NextResponse } from "next/server";
import { logger } from "./logger";

// Server-only helpers behind every Academy admin proxy route in this app:
// today that's app/api/academy/learners/** (the per-learner course-unlock
// proxy), which hits the Academy's `/api/admin/**` surface behind an
// X-Admin-Key check (see the Academy service/src/server.ts's shared
// `adminKeyAuthorized`). The scholarship-fulfilment routes that shared this
// file were removed when the Fast-Track Scholarship program was retired —
// the plumbing stays because the learner-unlock routes use the identical
// shared-key-never-reaches-the-browser posture: the browser calls
// same-origin routes here, which forward to the Academy with the X-Admin-Key
// header from *server* env. See the Academy service/ADMIN-SCHOLARSHIP-CONTRACT.md
// ("web-admin integration shape") for the key contract.
//
// Env (server-only, NOT NEXT_PUBLIC_*): ACADEMY_ADMIN_API_BASE (e.g.
// http://localhost:4100) + SCHOLARSHIP_ADMIN_KEY (the env name predates the
// program's retirement; it's the Academy admin API's shared key). See
// .env.local.example.

const ACADEMY_BASE = process.env.ACADEMY_ADMIN_API_BASE || "http://localhost:4100";
const ADMIN_KEY = process.env.SCHOLARSHIP_ADMIN_KEY || "";

export function adminHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  if (ADMIN_KEY) headers["X-Admin-Key"] = ADMIN_KEY;
  return headers;
}

// Talks to the Academy, relays its status/body faithfully, and turns a hard
// connection failure (Academy down) into a clean 502 rather than a raw stack.
export async function forwardToAcademy(
  path: string,
  init: RequestInit,
  context: Record<string, unknown>
): Promise<NextResponse> {
  const url = `${ACADEMY_BASE}${path}`;
  let upstream: Response;
  try {
    upstream = await fetch(url, { ...init, cache: "no-store" });
  } catch (err) {
    logger.error({ ...context, url, err: String((err as Error)?.message || err) }, "academy_proxy_unreachable");
    return NextResponse.json(
      { error: "The Academy admin service is unreachable. Please try again shortly." },
      { status: 502 }
    );
  }

  let body: unknown = null;
  const text = await upstream.text();
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = { error: text.slice(0, 2000) };
    }
  }
  if (!upstream.ok) {
    logger.warn({ ...context, url, status: upstream.status }, "academy_proxy_upstream_error");
  }
  return NextResponse.json(body ?? {}, { status: upstream.status });
}
