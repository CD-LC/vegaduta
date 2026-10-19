"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Gauge, RefreshCw, Trash2 } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "../../components/ui/alert-dialog";
import { Badge } from "../../components/ui/badge";
import { Button, buttonVariants } from "../../components/ui/button";
import { Card, CardContent } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Textarea } from "../../components/ui/textarea";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../components/ui/table";
import PaginationControls from "../../components/PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { Section } from "../../components/ax/page-kit";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import {
  formatKappa,
  formatRatio,
  parseCalibrationRows,
  type CalibrationResultRow,
  type CalibrationRunResponse,
  type CalibrationSetSummary,
} from "./judgeCalibration";

/**
 * Judge calibration (wave E, core/quality JudgeCalibrationSetController): turn "do we trust the LLM that
 * grades our evals?" into a number you can watch. Paste human-labelled A/B comparisons as CSV (or paste from a
 * spreadsheet), run the tenant's judge over them, and see Cohen's kappa, order-swap consistency and how both move
 * over time.
 *
 * Self-hides when the core answers 404 (app.quality.eval.calibration.enabled is off, or an older core) or 403
 * (FREE plan) on the sets list: this is an expected state, not an error, and the rest of the Datasets tab
 * already carries the plan upsell.
 */
export default function JudgeCalibrationSection({ agentId }: { agentId: string | null }) {
  const t = useTranslations("judgeCalibration");
  const [sets, setSets] = useState<CalibrationSetSummary[] | null>(null);
  const [history, setHistory] = useState<CalibrationResultRow[]>([]);
  const historyPg = usePagination(history, 10);
  const [unavailable, setUnavailable] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  const [name, setName] = useState("");
  const [pasted, setPasted] = useState("");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const [runningId, setRunningId] = useState<string | null>(null);
  const [runError, setRunError] = useState<string | null>(null);
  const [lastRun, setLastRun] = useState<{ setName: string; response: CalibrationRunResponse } | null>(null);

  const parsed = useMemo(() => parseCalibrationRows(pasted), [pasted]);

  const refresh = useCallback(async () => {
    if (!agentId) return;
    try {
      const [setList, hist] = await Promise.all([
        api.listJudgeCalibrationSets(agentId),
        api.listJudgeCalibrationHistory(agentId, undefined, 20),
      ]);
      setSets(setList || []);
      setHistory(hist || []);
      setLoadFailed(false);
    } catch (e: any) {
      if (e?.status === 404 || e?.status === 403) setUnavailable(true);
      else setLoadFailed(true);
    }
  }, [agentId]);

  useEffect(() => {
    setSets(null);
    setHistory([]);
    setUnavailable(false);
    setLoadFailed(false);
    setLastRun(null);
    void refresh();
  }, [agentId, refresh]);

  const create = useCallback(async () => {
    if (!agentId) return;
    setCreating(true);
    setCreateError(null);
    try {
      await api.createJudgeCalibrationSet(agentId, { name: name.trim(), items: parsed.rows });
      setName("");
      setPasted("");
      await refresh();
    } catch (e: any) {
      setCreateError(e?.message || t("createFailed"));
    } finally {
      setCreating(false);
    }
  }, [agentId, name, parsed.rows, refresh, t]);

  const run = useCallback(
    async (set: CalibrationSetSummary) => {
      if (!agentId) return;
      setRunningId(set.id);
      setRunError(null);
      try {
        const response = (await api.runJudgeCalibration(agentId, set.id)) as CalibrationRunResponse;
        setLastRun({ setName: set.name, response });
        await refresh();
      } catch (e: any) {
        setRunError(e?.message || t("runFailed"));
      } finally {
        setRunningId(null);
      }
    },
    [agentId, refresh, t]
  );

  const remove = useCallback(
    async (set: CalibrationSetSummary) => {
      if (!agentId) return;
      try {
        await api.deleteJudgeCalibrationSet(agentId, set.id);
        setLastRun(null);
        await refresh();
      } catch {
        /* shared toJson already raised the toast */
      }
    },
    [agentId, refresh]
  );

  if (!agentId || unavailable) return null;

  const setNameById = (id: string) => sets?.find((s) => s.id === id)?.name ?? id.slice(0, 8);
  const canCreate = name.trim().length > 0 && parsed.rows.length > 0 && parsed.errors.length === 0 && !creating;

  return (
    <Section
      title={t("title")}
      description={t("description")}
      actions={
        <Button variant="outline" size="sm" onClick={() => void refresh()}>
          <RefreshCw size={14} aria-hidden="true" /> {t("refresh")}
        </Button>
      }
    >
      {loadFailed && (
        <Alert variant="destructive">
          <AlertTitle>{t("loadFailedTitle")}</AlertTitle>
          <AlertDescription className="flex items-center gap-3">
            {t("loadFailedBody")}
            <Button variant="outline" size="sm" onClick={() => void refresh()}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      <Card>
        <CardContent className="space-y-3 pt-4">
          <p className="text-sm font-medium text-foreground">{t("createTitle")}</p>
          <p className="text-xs text-muted-foreground">{t("createHelp")}</p>
          <Input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t("namePlaceholder")}
            aria-label={t("nameLabel")}
            maxLength={255}
          />
          <Textarea
            value={pasted}
            onChange={(e) => setPasted(e.target.value)}
            placeholder={t("rowsPlaceholder")}
            aria-label={t("rowsLabel")}
            rows={6}
            className="font-mono text-xs"
          />
          {pasted.trim() && (
            <p className="text-xs text-muted-foreground">
              {t("rowsParsed", { count: parsed.rows.length, problems: parsed.errors.length })}
            </p>
          )}
          {parsed.errors.length > 0 && (
            <ul className="list-disc space-y-0.5 pl-5 text-xs text-destructive">
              {parsed.errors.slice(0, 5).map((err) => (
                <li key={err}>{err}</li>
              ))}
              {parsed.errors.length > 5 && <li>{t("moreProblems", { count: parsed.errors.length - 5 })}</li>}
            </ul>
          )}
          {createError && (
            <Alert variant="destructive">
              <AlertDescription>{createError}</AlertDescription>
            </Alert>
          )}
          <Button size="sm" onClick={() => void create()} disabled={!canCreate}>
            {creating ? t("creating") : t("create")}
          </Button>
        </CardContent>
      </Card>

      {sets && sets.length === 0 && <p className="text-sm text-muted-foreground">{t("noSets")}</p>}

      {sets && sets.length > 0 && (
        <div className="space-y-2">
          {sets.map((set) => (
            <Card key={set.id}>
              <CardContent className="flex flex-wrap items-center gap-3 pt-4">
                <span className="text-sm font-medium text-foreground">{set.name}</span>
                <Badge variant="secondary">{t("itemCount", { count: set.itemCount })}</Badge>
                <span className="text-xs text-muted-foreground">{new Date(set.createdAt).toLocaleDateString()}</span>
                <div className="ml-auto flex items-center gap-2">
                  <Button size="sm" onClick={() => void run(set)} disabled={runningId !== null}>
                    <Gauge size={14} aria-hidden="true" /> {runningId === set.id ? t("running") : t("run")}
                  </Button>
                  <AlertDialog>
                    <AlertDialogTrigger asChild>
                      <Button variant="outline" size="sm" aria-label={t("deleteSet")}>
                        <Trash2 size={14} aria-hidden="true" />
                      </Button>
                    </AlertDialogTrigger>
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>{t("deleteTitle", { name: set.name })}</AlertDialogTitle>
                        <AlertDialogDescription>{t("deleteBody")}</AlertDialogDescription>
                      </AlertDialogHeader>
                      <AlertDialogFooter>
                        <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
                        <AlertDialogAction
                          className={buttonVariants({ variant: "destructive" })}
                          onClick={() => void remove(set)}
                        >
                          {t("delete")}
                        </AlertDialogAction>
                      </AlertDialogFooter>
                    </AlertDialogContent>
                  </AlertDialog>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {runError && (
        <Alert variant="destructive">
          <AlertTitle>{t("runFailedTitle")}</AlertTitle>
          <AlertDescription>{runError}</AlertDescription>
        </Alert>
      )}

      {lastRun && <RunResultCard setName={lastRun.setName} response={lastRun.response} />}

      {history.length > 0 && (
        <Card>
          <CardContent className="space-y-2 pt-4">
            <p className="text-sm font-medium text-foreground">{t("historyTitle")}</p>
            <p className="text-xs text-muted-foreground">{t("historyHelp")}</p>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("colWhen")}</TableHead>
                  <TableHead>{t("colSet")}</TableHead>
                  <TableHead>{t("colJudge")}</TableHead>
                  <TableHead>{t("colKappa")}</TableHead>
                  <TableHead>{t("colSwap")}</TableHead>
                  <TableHead>{t("colN")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {historyPg.pageItems.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>{new Date(r.createdAt).toLocaleString()}</TableCell>
                    <TableCell>{setNameById(r.setId)}</TableCell>
                    <TableCell>{r.judgeModel ?? "—"}</TableCell>
                    <TableCell className="font-medium">{formatKappa(r.kappa)}</TableCell>
                    <TableCell>{formatRatio(r.orderSwapConsistency)}</TableCell>
                    <TableCell>{r.n}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <PaginationControls
              page={historyPg.page}
              totalPages={historyPg.totalPages}
              totalItems={historyPg.totalItems}
              pageSize={historyPg.pageSize}
              onPageChange={historyPg.setPage}
              onPageSizeChange={historyPg.setPageSize}
              itemLabel="runs"
            />
          </CardContent>
        </Card>
      )}
    </Section>
  );
}

function RunResultCard({ setName, response }: { setName: string; response: CalibrationRunResponse }) {
  const t = useTranslations("judgeCalibration");
  const r = response.report;
  return (
    <Card>
      <CardContent className="space-y-2 pt-4">
        <p className="text-sm font-medium text-foreground">{t("resultTitle", { name: setName })}</p>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <Badge variant="accent">{t("kappaLabel", { kappa: formatKappa(r.cohenKappa) })}</Badge>
          <span className="text-muted-foreground">{r.interpretation}</span>
          <span className="text-muted-foreground">
            {t("swapLabel", { value: formatRatio(r.orderSwapConsistency) })}
          </span>
          <span className="text-muted-foreground">{t("usableLabel", { usable: r.usable, n: r.examples })}</span>
          {response.judgeModel && (
            <span className="text-muted-foreground">{t("judgeLabel", { model: response.judgeModel })}</span>
          )}
        </div>
        {r.cohenKappa == null && <p className="text-xs text-muted-foreground">{t("kappaUndefined")}</p>}
        {!response.resultId && <p className="text-xs text-muted-foreground">{t("notRecorded")}</p>}
        {response.selfPreferenceWarning && (
          <Alert>
            <AlertDescription>{response.selfPreferenceWarning}</AlertDescription>
          </Alert>
        )}
        {r.warnings.length > 0 && (
          <ul className="list-disc space-y-0.5 pl-5 text-xs text-muted-foreground">
            {r.warnings.map((w) => (
              <li key={w}>{w}</li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
