import { NextResponse } from "next/server";
import { verifyPlatformAdmin } from "../../lib/serverAuth";
import { observabilityLinks } from "../../lib/observabilityLinks";

// GET /api/observability-links -> { grafana, prometheus, langfuse, temporal }
// SEC-054: these internal-infrastructure URLs are no longer in window.__ENV;
// they are handed out only to a verified platform admin (same core-backed
// oracle as app/api/academy/**, see app/lib/serverAuth.ts).

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const authResult = await verifyPlatformAdmin(request);
  if (!authResult.ok) {
    return NextResponse.json({ error: authResult.error }, { status: authResult.status });
  }
  return NextResponse.json(observabilityLinks(), { headers: { "Cache-Control": "no-store" } });
}