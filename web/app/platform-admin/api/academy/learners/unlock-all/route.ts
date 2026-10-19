import { NextResponse } from "next/server";
import { adminHeaders, forwardToAcademy } from "../../../../lib/academyProxy";
import { verifyPlatformAdmin } from "../../../../lib/serverAuth";

// POST /api/academy/learners/unlock-all → forwards to Academy
// POST /api/admin/academy/learners/unlock-all. Opens every not-yet-completed
// lesson for EVERY learner on the instance — the "unlock all courses for all
// users" control. Same semantics as the per-learner unlock (sequential gating
// bypassed, completion history untouched, per-learner
// `admin_course_unlocked` telemetry per learner) — just applied to the whole
// learner collection at once. See
// vegaduta-webapp/src/learning-portal/admin-learners.ts#unlockCourseForAllLearners.
//
// SECURITY: same auth requirement as every other route in this proxy family
// - see app/lib/serverAuth.ts.

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  const authResult = await verifyPlatformAdmin(request);
  if (!authResult.ok) {
    return NextResponse.json({ error: authResult.error }, { status: authResult.status });
  }

  return forwardToAcademy(
    "/api/admin/academy/learners/unlock-all",
    { method: "POST", headers: adminHeaders() },
    { route: "academy_learners_unlock_all" }
  );
}
