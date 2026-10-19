import { NextResponse } from "next/server";
import { adminHeaders, forwardToAcademy } from "../../../../../lib/academyProxy";
import { verifyPlatformAdmin } from "../../../../../lib/serverAuth";

// POST /api/academy/learners/:id/unlock → forwards to Academy
// POST /api/admin/academy/learners/:id/unlock. Opens every not-yet-completed
// lesson for that one learner (sequential gating bypassed for them only;
// their completion history and any other learner are untouched). The
// Academy records an `admin_course_unlocked` telemetry event so this is
// always auditable as admin-granted, not earned - see
// vegaduta-webapp/src/learning-portal/admin-learners.ts for the exact
// semantics this forwards to.
//
// This touches nothing about billing/plan: it unlocks the lesson spine for
// exactly ONE learner — sequential gating bypassed for them only, and every
// other learner's progress is untouched.
//
// SECURITY: same auth requirement as every other route in this proxy family
// - see app/lib/serverAuth.ts.

export const dynamic = "force-dynamic";

type RouteContext = { params: Promise<{ id: string }> };

export async function POST(request: Request, { params }: RouteContext) {
  const authResult = await verifyPlatformAdmin(request);
  if (!authResult.ok) {
    return NextResponse.json({ error: authResult.error }, { status: authResult.status });
  }

  const { id } = await params;

  return forwardToAcademy(
    `/api/admin/academy/learners/${encodeURIComponent(id)}/unlock`,
    { method: "POST", headers: adminHeaders() },
    { route: "academy_learners_unlock", learnerId: id }
  );
}
