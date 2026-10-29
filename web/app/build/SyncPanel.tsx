"use client";

// Two-way sync panel for a project living on a branch in the user's repo.
// Shown under the GitHub activity pane. Reads status/external edits on mount,
// offers a Pull (merge base into the branch) and lists conflicted files when
// GitHub refuses the merge - in which case nothing was changed on GitHub.

import { useCallback, useEffect, useState } from "react";
import { GitMerge, Loader2, RefreshCw, TriangleAlert } from "lucide-react";
import { appBuilder } from "./lib";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import {
  conflictPaths,
  externalEditsLine,
  syncChips,
  type AppBuilderExternalEdits,
  type AppBuilderSyncStatus,
} from "./syncLogic";
import { useTranslations } from "../lib/i18n";

const TONE: Record<string, string> = {
  ok: "var(--state-success)",
  warn: "var(--state-warning, var(--state-queued))",
  info: "var(--state-queued)",
};

interface Props {
  project: { project_id: string; github?: { branch?: string | null } | null } | null;
  onError?: (message: string) => void;
}

export default function SyncPanel({ project, onError }: Props) {
    const tHC = useTranslations("hcBuildSyncPanel");
  const id = project?.project_id;
  const hasBranch = !!project?.github?.branch;
  const [status, setStatus] = useState<AppBuilderSyncStatus | null>(null);
  const [external, setExternal] = useState<AppBuilderExternalEdits | null>(null);
  const [loading, setLoading] = useState(false);
  const [pulling, setPulling] = useState(false);
  const [conflicts, setConflicts] = useState<string[] | null>(null);
  const [notice, setNotice] = useState<string>("");
  // Reading status can fail independently of a pull; keep that message apart
  // so a failed refresh never overwrites (or is overwritten by) a merge result.
  const [statusError, setStatusError] = useState<string>("");

  const refresh = useCallback(async () => {
    if (!id || !hasBranch) return;
    setLoading(true);
    setStatusError("");
    try {
      const [s, e] = await Promise.all([appBuilder.syncStatus(id), appBuilder.syncDetectExternal(id)]);
      setStatus(s);
      setExternal(e);
    } catch (err) {
      // Sync is an add-on: an unreachable/older backend must not break the tab.
      setStatus(null);
      setExternal(null);
      setStatusError(`Could not read sync status: ${(err as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [id, hasBranch]);

  useEffect(() => {
    setConflicts(null);
    setNotice("");
    void refresh();
  }, [refresh]);

  // The manual refresh button starts from a clean slate; the refresh that
  // follows a pull deliberately keeps the merge notice.
  const manualRefresh = () => {
    setNotice("");
    setConflicts(null);
    void refresh();
  };

  const pull = async () => {
    if (!id) return;
    setPulling(true);
    setConflicts(null);
    setNotice("");
    try {
      const res = await appBuilder.syncPull(id);
      setNotice(
        res.merged
          ? res.reindexed
            ? "Merged. The branch now includes the latest base changes."
            : "Merged. The code index is refreshing - AI answers may lag briefly."
          : "Already up to date."
      );
      await refresh();
    } catch (err) {
      const paths = conflictPaths(err);
      if (paths) {
        setConflicts(paths);
      } else {
        onError?.((err as Error).message);
        setNotice((err as Error).message);
      }
    } finally {
      setPulling(false);
    }
  };

  if (!id || !hasBranch) return null;
  const chips = syncChips(status);
  const extLine = externalEditsLine(external);

  return (
    <Card className="mt-4">
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center justify-between gap-2 text-sm">
          <span className="flex items-center gap-2">
            <GitMerge aria-hidden="true" size={14} /> {tHC("hcBuildSyncPanel.s7")} {status?.base_branch || "base"}
          </span>
          <Button variant="ghost" size="sm" onClick={manualRefresh} disabled={loading} aria-label={tHC("hcBuildSyncPanel.s6")}>
            {loading ? (
              <Loader2 aria-hidden="true" size={14} className="animate-spin" />
            ) : (
              <RefreshCw aria-hidden="true" size={14} />
            )}
          </Button>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-center gap-2" data-testid="sync-chips">
          {chips.map((c) => (
            <span key={c.key} className="state-pill" style={{ ["--state" as string]: TONE[c.tone] }}>
              <span className="state-dot" /> {c.label}
            </span>
          ))}
          {!status && !loading && <span className="text-xs text-muted-foreground">{tHC("hcBuildSyncPanel.s5")}</span>}
        </div>

        {extLine && (
          <p className="flex items-start gap-2 text-xs text-muted-foreground" data-testid="sync-external">
            <TriangleAlert aria-hidden="true" size={13} className="mt-0.5 shrink-0" /> {extLine}
          </p>
        )}

        <div className="flex items-center gap-2">
          <Button size="sm" onClick={pull} disabled={pulling || !status?.can_pull}>
            {pulling ? (
              <Loader2 aria-hidden="true" size={14} className="animate-spin" />
            ) : (
              <GitMerge aria-hidden="true" size={14} />
            )}{" "}
            Pull {status?.base_branch || "base"} {tHC("hcBuildSyncPanel.s4")}
          </Button>
          {status && !status.can_pull && <span className="text-xs text-muted-foreground">{tHC("hcBuildSyncPanel.s3")}</span>}
        </div>

        {conflicts && (
          <div
            className="rounded-lg border border-border bg-muted/30 p-3 text-xs"
            role="alert"
            data-testid="sync-conflicts"
          >
            <p className="mb-2 font-medium text-foreground">
              {tHC("hcBuildSyncPanel.s2")}
            </p>
            {conflicts.length ? (
              <ul className="list-disc space-y-0.5 ps-5 font-mono text-muted-foreground">
                {conflicts.map((p) => (
                  <li key={p}>{p}</li>
                ))}
              </ul>
            ) : (
              <p className="text-muted-foreground">{tHC("hcBuildSyncPanel.s1")}</p>
            )}
          </div>
        )}

        {notice && !conflicts && (
          <p role="status" className="text-xs text-muted-foreground">
            {notice}
          </p>
        )}
        {statusError && <p className="text-xs text-muted-foreground">{statusError}</p>}
      </CardContent>
    </Card>
  );
}
