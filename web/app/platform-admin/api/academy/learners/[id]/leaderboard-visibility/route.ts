import { NextResponse } from "next/server";
import { adminHeaders, forwardToAcademy } from "../../../../../lib/academyProxy";
import { verifyPlatformAdmin } from "../../../../../lib/serverAuth";

// POST /api/academy/learners/:id/leaderboard-visibility → forwards to Academy
// POST /api/admin/academy/learners/:id/leaderboard-visibility with the body
// passed through unchanged ({ hidden: boolean }). The "not on the board"
// moderation toggle: hidden=true removes the learner from the public
// leaderboard only — their learning, XP and admin stats are unaffected, and
// the toggle is reversible. See
// vegaduta-webapp/src/learning-portal/admin-learners.ts#setLearnerLeaderboardVisibility.
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
  const body = await request.text();

  return forwardToAcademy(
    `/api/admin/academy/learners/${encodeURIComponent(id)}/leaderboard-visibility`,
    {
      method: "POST",
      headers: adminHeaders({ "Content-Type": "application/json" }),
      body,
    },
    { route: "academy_learner_leaderboard_visibility", learnerId: id }
  );
}
