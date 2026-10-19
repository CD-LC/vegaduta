"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { RouteTransitionOverlay } from "./components/RouteTransition";
import { isPersonalInstall } from "./lib/personalInstall";

// The "/" route module - deliberately a thin shim.
//
// "/" serves two audiences. AuthProvider renders GenesisExperience (the
// anonymous marketing landing) for a visitor with no Keycloak session, and
// only renders THIS page for a signed-in member. Next.js still downloads the
// route module's whole static closure for every visitor, so this file must
// stay tiny: the old dashboard body lives in components/dashboard/
// DashboardHome.tsx and is served at /home, not here.
//
// Signed-in home is the Build Studio (/build) for everyone, admins included:
// the first thing a member should see is "what do you want to build?", with
// the guided path from idea to live. The old dashboard is one click away at
// /home (rail: Home), and /admin stays in the rail for admins.
//
// Exception: on a personal install (local auth) the signed-in home is the
// Assistant (/assistant) - for a one-person install the chat IS the product,
// and the Build Studio's multi-tenant surfaces are the ones the rail hides
// anyway (see lib/personalInstall.ts).
//
// While the redirect is in flight the page renders the app's branded cover
// (RouteTransitionOverlay, the same pulsing-emblem panel every other
// transition uses), so the hand-off reads as one continuous loading state.
//
// 2026-09-29: an unused next/dynamic DashboardHome loader and a useAuth()
// read were removed (noUnusedLocals). Neither was rendered or read since the
// redirect to /build landed; see git history for the load-perf rationale.
export default function DashboardPage() {
  const router = useRouter();

  useEffect(() => {
    router.replace(isPersonalInstall() ? "/assistant" : "/build");
  }, [router]);

  return <RouteTransitionOverlay active />;
}
