"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { AlertTriangle, Search } from "lucide-react";
import { api } from "../lib/api";
import type { ResearchStatus } from "../lib/api/types";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { Alert, AlertDescription, AlertTitle } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { useTranslations } from "../lib/i18n";
import { usePageReady } from "../components/RouteTransition";
import ResearchStatusBanner from "../components/research/ResearchStatusBanner";
import ResearchComposer from "../components/research/ResearchComposer";
import { takeResearchPrefill } from "../components/research/prefill";
import ResearchHistoryList from "../components/research/ResearchHistoryList";
import { historyReadable, researchAvailability } from "../components/research/researchRun";

/**
 * Research - ask a question, get a report with quoted, linked sources.
 * Backed by core's /api/research (ResearchRunController): a run is a
 * background job on the signed-in user's own research agent, so it keeps
 * going after this tab closes and the history below is that user's alone.
 *
 * Availability is server-driven. GET /api/research/status always answers for a
 * signed-in user, even with every switch off, and this page shows exactly what
 * it says: not available here, switched off, waiting for the platform search
 * key, or ready. A core build without the endpoint (404) reads as "not
 * available" rather than as an error.
 */
export default function ResearchPage() {
  const t = useTranslations("researchPage");
  const router = useRouter();
  const [status, setStatus] = useState<ResearchStatus | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  // A question handed over by "Run again" / a follow-up chip. Read once, on mount.
  const [prefill, setPrefill] = useState("");
  const hasStatus = useRef(false);

  const loadStatus = useCallback(async () => {
    try {
      const next = await api.getResearchStatus();
      hasStatus.current = true;
      setStatus(next);
      setLoadFailed(false);
    } catch (err: any) {
      // A re-check that fails (after a refused start) keeps what is on screen:
      // dropping the status would unmount the composer and lose the question.
      if (hasStatus.current && !(err?.status === 404 || err?.status === 503)) return;
      setStatus(null);
      // 404/503: the feature is not on this deployment - a state, not a failure.
      setLoadFailed(!(err?.status === 404 || err?.status === 503));
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    setPrefill(takeResearchPrefill());
    void loadStatus();
  }, [loadStatus]);
  usePageReady(loaded);

  const availability = researchAvailability(status);

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader title={t("title")} subtitle={t("subtitle")} icon={Search} />

      {loaded && loadFailed && (
        <Alert variant="destructive" className="mb-6">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("statusFailedTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("statusFailedBody")}</p>
            <Button size="sm" variant="outline" onClick={loadStatus}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {loaded && !loadFailed && (
        <div className="space-y-6">
          <ResearchStatusBanner availability={availability} />
          {availability === "ready" && status && (
            <ResearchComposer
              key={prefill}
              initialQuestion={prefill}
              status={status}
              onStarted={(job) => router.push(`/research/${job.id}`)}
              onRefused={loadStatus}
            />
          )}
          {historyReadable(status) && <ResearchHistoryList />}
        </div>
      )}
    </AppShell>
  );
}
