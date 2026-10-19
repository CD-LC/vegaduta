"use client";

import { usePageReady } from "../components/RouteTransition";
import TryStudio from "../components/studio/TryStudio";

// /try — the Agent Studio: design real agents in a demo tenant, run
// missions with a live tool trace, chain agents into a workflow with a real
// approval pause, deploy to a share link or Telegram. No chatbot.
//
// A thin route shell on purpose: everything lives in TryStudio so the page
// itself stays a client boundary and nothing else.
//
// usePageReady(true), unconditionally, and that is deliberate: the studio's
// boot narration ("Opening a space for you in the demo tenant…") is content,
// not a flash to be hidden behind RouteTransition's cover.
export default function TryPage() {
  usePageReady(true);
  return <TryStudio />;
}
