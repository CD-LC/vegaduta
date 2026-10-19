"use client";

import { useCallback, useState } from "react";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import { usePageReady } from "../../components/RouteTransition";
import PageHeader from "../../components/ax/PageHeader";
import InboxPanel from "./InboxPanel";

// Operator inbox (live human handoff, backend V214; rebuilt 2026-09-23
// into the full conversations operations page) - same thin-page/fat-panel
// split as /admin/channels: this page owns only the admin gate, the shell
// chrome and the header's counts; InboxPanel owns the conversation list,
// the transcript, and every interaction. The header counts now come from
// the panel's /api/handoff/stats fetch (via onCountsChange) rather than a
// second request - one cheap aggregate call serves both.
//
// i18n + nav-entry follow-ups are deliberately absent, not forgotten:
// web/messages/en.json and components/AppShell.tsx are other workstreams'
// files - see InboxPanel's header comment and the coordination log entry.
export default function AdminInboxPage() {
  // MANAGE_CHANNELS scope (granular RBAC, 2026-09-23) - the human-handoff
  // inbox is channel operations, same backend permission as the channel
  // pairing/contact surfaces it feeds.
  const allowed = useRequireScope("MANAGE_CHANNELS");
  const [loaded, setLoaded] = useState(false);
  const [waiting, setWaiting] = useState<number | null>(null);
  const [held, setHeld] = useState<number | null>(null);

  const onCountsChange = useCallback((waitingCount: number | null, heldCount: number | null) => {
    setWaiting(waitingCount);
    setHeld(heldCount);
    // Fires on failure too (with nulls) - the route cover must release
    // either way; the panel renders its own failure/not-wired banners.
    setLoaded(true);
  }, []);

  // Non-admins redirect to "/" (return null below) → ready immediately.
  // The panel reports its first list result (success or failure) via
  // onCountsChange/its own banners; gate the route cover on the admin check
  // only, so a slow first fetch shows the panel's own skeleton states
  // rather than holding the whole route cover hostage.
  usePageReady(loaded || !allowed);

  if (!allowed) return null;

  return (
    <AppShell eyebrow="Conversations">
      <PageHeader
        title="Inbox"
        subtitle={
          <span className="tabular-nums">
            <strong className="font-semibold text-foreground">{waiting ?? "—"}</strong> waiting for a human{" "}
            <span className="text-muted-foreground">/</span> {held ?? "—"} human-held
          </span>
        }
      />
      <InboxPanel onCountsChange={onCountsChange} />
    </AppShell>
  );
}
