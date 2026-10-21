import { NextResponse } from "next/server";
import { adminHeaders, forwardToAcademy } from "../../../../../lib/academyProxy";
import { verifyPlatformAdmin } from "../../../../../lib/serverAuth";

// POST /api/academy/tenants/:tenantId/unlock → forwards to Academy
// POST /api/admin/academy/tenants/:tenantId/unlock. Opens the whole lesson
// spine for every learner whose Academy account is linked to that tenant —
// strict non-null tenant_id equality on the Academy side (learners with no
// tenant linkage can never be swept in; see THE NULL RULE in
// the Academy service/src/learning-portal/tenant-progress.ts, which
// unlockCourseForTenant honours identically).
//
// SECURITY: same auth requirement as every other route in this proxy family
// - see app/lib/serverAuth.ts.

export const dynamic = "force-dynamic";

type RouteContext = { params: Promise<{ tenantId: string }> };

export async function POST(request: Request, { params }: RouteContext) {
  const authResult = await verifyPlatformAdmin(request);
  if (!authResult.ok) {
    return NextResponse.json({ error: authResult.error }, { status: authResult.status });
  }

  const { tenantId } = await params;

  return forwardToAcademy(
    `/api/admin/academy/tenants/${encodeURIComponent(tenantId)}/unlock`,
    { method: "POST", headers: adminHeaders() },
    { route: "academy_tenant_unlock", tenantId }
  );
}
