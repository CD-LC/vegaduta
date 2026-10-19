"use client";

import nextDynamic from "next/dynamic";
import { RouteTransitionOverlay } from "../components/RouteTransition";

// The classic dashboard. "/" now sends signed-in members to the Build Studio
// (/build); this route keeps the dashboard one click away (rail: Home). Same
// dynamic-import shape as the old "/" shim so the dashboard chunk loads only
// for someone who asks for it.
const DashboardHome = nextDynamic(() => import("../components/dashboard/DashboardHome"), {
  ssr: false,
  loading: () => <RouteTransitionOverlay active />,
});

export default function HomePage() {
  return <DashboardHome />;
}
