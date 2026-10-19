import { NextResponse } from "next/server";
import { adminHeaders, forwardToAcademy } from "../../../../../lib/academyProxy";
import { verifyPlatformAdmin } from "../../../../../lib/serverAuth";

// DELETE /api/academy/learners/:id/history → forwards to Academy
// DELETE /api/admin/academy/learners/:id/history. Wipes one learner's
// learning history — progress, quiz attempts, lab progress, challenge
// progress, review scheduling state, telemetry — and re-seeds them to the
// day-one shape. The learner, their account and their sessions survive, so
// login keeps working; scholarship claim rows are deliberately kept (they
// are admin-facing fulfilment attestations, not learner history). The
// Academy writes an `admin_history_deleted` telemetry event AFTER the wipe
// so the audit trail survives the deletion it describes. See
// vegaduta-webapp/src/learning-portal/admin-learners.ts#deleteLearnerHistory.
//
// SECURITY: same auth requirement as every other route in this proxy family
// - see app/lib/serverAuth.ts.

export const dynamic = "force-dynamic";

type RouteContext = { params: Promise<{ id: string }> };

export async function DELETE(request: Request, { params }: RouteContext) {
  const authResult = await verifyPlatformAdmin(request);
  if (!authResult.ok) {
    return NextResponse.json({ error: authResult.error }, { status: authResult.status });
  }

  const { id } = await params;

  return forwardToAcademy(
    `/api/admin/academy/learners/${encodeURIComponent(id)}/history`,
    { method: "DELETE", headers: adminHeaders() },
    { route: "academy_learner_history_delete", learnerId: id }
  );
}
