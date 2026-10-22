"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { Plus } from "lucide-react";
import { api } from "../../lib/api";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import { usePageReady } from "../../components/RouteTransition";
import ChannelsPanel from "../../components/ChannelsPanel";
import { offeredChannelOrder } from "../../components/channels/ChannelInspectorCards";
import PageHeader from "../../components/ax/PageHeader";
import { Button } from "../../components/ui/button";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Skeleton } from "../../components/ui/skeleton";
import { useTranslations } from "../../lib/i18n";

// AXON Channels screen (#472) - rebuilt to pixel-match the design handoff
// (Channels.dc.html): topbar breadcrumb reads real live/connected counts off
// ChannelStatusController's aggregate (ChannelsPanel fetches the same
// endpoint again for its grid/log/inspector - two lightweight calls to one
// cheap endpoint beats threading state through a prop from here), same
// "page fetches the topbar's own numbers, panel owns interaction state"
// split as /tools and /knowledge.
function AdminChannelsInner() {
  const t = useTranslations("adminChannelsPage");
  // MANAGE_CHANNELS scope (granular RBAC, 2026-09-23) - was blanket isAdmin;
  // a delegated "support lead" can administer channels without any other
  // admin surface.
  const allowed = useRequireScope("MANAGE_CHANNELS");
  const [agents, setAgents] = useState([]);
  const [status, setStatus] = useState(null);
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const tx = useTranslations("channelsUsageMcp");
  const panelRef = useRef(null);

  const refresh = useCallback(() => {
    setLoadFailed(false);
    const agents$ = api
      .listAgents()
      .then(setAgents)
      .catch(() => {
        setAgents([]);
        setLoadFailed(true);
      });
    const status$ = api
      .getChannelsStatus()
      .then(setStatus)
      .catch(() => {
        setStatus(null);
        setLoadFailed(true);
      });
    void Promise.allSettled([agents$, status$]).finally(() => setLoaded(true));
  }, []);

  useEffect(() => {
    if (!allowed) return;
    refresh();
  }, [allowed, refresh]);
  // Non-scoped callers redirect to "/" (return null below) → ready immediately.
  usePageReady(loaded || !allowed);

  if (!allowed) return null;

  // Count only channels that have a tile (sms is configured inside WhatsApp's
  // card), so the header and the grid agree.
  const offeredKeys = offeredChannelOrder();
  const tiles = status ? status.channels.filter((c: { key: string }) => offeredKeys.includes(c.key)) : null;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader
        title={t("pageTitle")}
        subtitle={
          <span className="tabular-nums">
            <strong className="font-semibold text-foreground">{tiles ? tiles.filter((c: { state: string }) => c.state === "CONNECTED").length : "—"}</strong>{" "}
            {t("liveLabel")} <span className="text-muted-foreground">/</span> {tiles ? tiles.length : "—"}{" "}
            {t("availableLabel")}
          </span>
        }
        actions={
          <Button type="button" size="sm" onClick={() => panelRef.current?.openConnect()}>
            <Plus size={13} aria-hidden="true" /> {t("connectChannelButton")}
          </Button>
        }
      />

      {loaded && loadFailed && (
        <Alert variant="destructive" className="mb-4" data-testid="channels-load-error">
          <AlertDescription className="flex flex-wrap items-center gap-3">
            <span>{tx("channelsLoadError")}</span>
            <Button type="button" size="sm" variant="outline" onClick={refresh}>
              {tx("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      <ChannelsPanel ref={panelRef} agents={agents} />
    </AppShell>
  );
}

// SocialChannelsPanel/legacy X-OAuth-callback ?x=connected|error param is
// now read inside ChannelsPanel itself (folded in during #472's rebuild) via
// useSearchParams, which Next.js requires a Suspense boundary for.
export default function AdminChannelsPage() {
  return (
    <Suspense fallback={<Skeleton className="m-6 h-64 rounded-xl" />}>
      <AdminChannelsInner />
    </Suspense>
  );
}
