"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslations } from "../../lib/i18n";
import { api } from "../../lib/api";
import type { PromptHistoryEntry, PromptScopeTarget } from "../../lib/api/promptRegistry";
import { diffLines } from "../../lib/promptDiff";
import { errorMessage } from "../../lib/errors";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Checkbox } from "../../components/ui/checkbox";
import { Label } from "../../components/ui/label";
import PaginationControls from "../../components/PaginationControls";
import { usePagination } from "../../lib/usePagination";

const DATE = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

function when(value: string | null): string {
  if (!value) return "";
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? value : DATE.format(d);
}

/**
 * The immutable version history of one override: who changed what and why, the line diff against the version
 * before, and one click to put an earlier wording back (which is saved as a NEW version, nothing is rewritten).
 */
export default function PromptHistory({
  promptKey,
  title,
  securityCritical,
  target,
  canEdit,
  reloadToken,
  onRestored,
}: {
  promptKey: string;
  title: string;
  securityCritical: boolean;
  target: PromptScopeTarget;
  canEdit: boolean;
  /** Changes whenever the override was saved or reverted elsewhere, so the list refetches. */
  reloadToken: number;
  onRestored: () => Promise<void> | void;
}) {
  const t = useTranslations("promptDirectives");
  const [entries, setEntries] = useState<PromptHistoryEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [diffOpen, setDiffOpen] = useState<Set<number>>(() => new Set());
  const [busy, setBusy] = useState<number | null>(null);
  const [ack, setAck] = useState(false);
  const { scope, agentId, locale } = target;
  const pg = usePagination(useMemo(() => entries ?? [], [entries]));

  const load = useCallback(async () => {
    try {
      const rows = await api.getPromptHistory(promptKey, { scope, agentId, locale });
      setEntries(rows);
      setError(null);
    } catch (e: unknown) {
      setError(errorMessage(e, t("regHistoryFailed")));
    }
  }, [promptKey, scope, agentId, locale, t]);

  useEffect(() => {
    void load();
  }, [load, reloadToken]);

  const toggleDiff = (id: number) =>
    setDiffOpen((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });

  async function restore(entry: PromptHistoryEntry) {
    setBusy(entry.version);
    setError(null);
    try {
      await api.restorePromptVersion(
        promptKey,
        { scope, agentId, locale },
        {
          toVersion: entry.version,
          acknowledgeRisk: ack,
          reason: t("regRestoreReason", { version: entry.version }),
        }
      );
      await onRestored();
      await load();
    } catch (e: unknown) {
      setError(errorMessage(e, t("regRestoreFailed")));
    } finally {
      setBusy(null);
    }
  }

  if (entries === null && !error) {
    return <p className="text-xs text-muted-foreground">{t("regHistoryLoading")}</p>;
  }

  return (
    <div className="flex flex-col gap-2" data-testid={`history-${promptKey}`}>
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {entries !== null && entries.length === 0 && (
        <p className="text-xs text-muted-foreground">{t("regHistoryEmpty")}</p>
      )}
      {securityCritical && canEdit && entries !== null && entries.length > 0 && (
        <div className="flex items-start gap-2">
          <Checkbox id={`${promptKey}-restore-ack`} checked={ack} onCheckedChange={(v) => setAck(v === true)} />
          <Label htmlFor={`${promptKey}-restore-ack`} className="text-xs leading-snug">
            {t("regRestoreAck", { title })}
          </Label>
        </div>
      )}
      <ol className="flex flex-col gap-2">
        {pg.pageItems.map((e, index) => {
          const open = diffOpen.has(e.id);
          // "Current" is the newest entry overall - only page 1 holds it.
          const isCurrent = pg.page === 1 && index === 0 && e.action !== "REVERT";
          const rows = open ? diffLines(e.previousText, e.text) : [];
          return (
            <li key={e.id} className="rounded-md border border-border bg-background/50 p-2 text-xs">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium text-foreground">{t("regVersion", { version: e.version })}</span>
                <Badge variant="secondary">{t(`regAction_${e.action}`)}</Badge>
                {isCurrent && <Badge variant="accent">{t("regCurrent")}</Badge>}
                {!e.enabled && <Badge variant="outline">{t("off")}</Badge>}
                <span className="text-muted-foreground">
                  {t("regBy", { who: e.createdBy ?? t("regUnknownAuthor"), when: when(e.createdAt) })}
                </span>
              </div>
              {e.reason && <p className="mt-1 text-muted-foreground">{t("regReason", { reason: e.reason })}</p>}
              <div className="mt-1.5 flex flex-wrap gap-2">
                <Button type="button" size="sm" variant="ghost" aria-expanded={open} onClick={() => toggleDiff(e.id)}>
                  {open ? t("regHideDiff", { version: e.version }) : t("regShowDiff", { version: e.version })}
                </Button>
                {canEdit && e.text && !isCurrent && (
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={busy !== null || (securityCritical && !ack)}
                    onClick={() => void restore(e)}
                  >
                    {t("regRestore", { version: e.version })}
                  </Button>
                )}
              </div>
              {open && (
                <pre
                  className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-words rounded border border-border bg-muted p-2 font-mono text-[11px]"
                  aria-label={t("regDiffLabel", { version: e.version })}
                >
                  {rows.length === 0 ? (
                    <span className="text-muted-foreground">{t("regNoDiff")}</span>
                  ) : (
                    rows.map((r, i) => (
                      <span
                        key={i}
                        data-diff={r.type}
                        className={
                          r.type === "add"
                            ? "block bg-[var(--state-success)]/15"
                            : r.type === "del"
                              ? "block bg-[var(--state-error)]/15 line-through"
                              : "block text-muted-foreground"
                        }
                      >
                        {r.type === "add" ? "+ " : r.type === "del" ? "- " : "  "}
                        {r.text}
                      </span>
                    ))
                  )}
                </pre>
              )}
            </li>
          );
        })}
      </ol>
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
      />
    </div>
  );
}
