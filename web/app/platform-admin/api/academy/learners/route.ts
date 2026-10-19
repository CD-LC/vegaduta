import { NextResponse } from "next/server";
import { adminHeaders, forwardToAcademy } from "../../../lib/academyProxy";
import { verifyPlatformAdmin } from "../../../lib/serverAuth";

// GET /api/academy/learners → server-side proxy → Academy
// GET /api/admin/academy/learners (see app/lib/academyProxy.ts). The Academy
// admin API is guarded server-side by one auth check (adminKeyAuthorized)
// and one SCHOLARSHIP_ADMIN_KEY shared secret.
//
// SECURITY: a Next.js route handler is reachable directly over HTTP
// regardless of the UI, and this one holds a privileged key, so it must
// authenticate + authorize BEFORE using it. See app/lib/serverAuth.ts.

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const authResult = await verifyPlatformAdmin(request);
  if (!authResult.ok) {
    return NextResponse.json({ error: authResult.error }, { status: authResult.status });
  }

  return forwardToAcademy(
    "/api/admin/academy/learners",
    { method: "GET", headers: adminHeaders() },
    { route: "academy_learners_list" }
  );
}
