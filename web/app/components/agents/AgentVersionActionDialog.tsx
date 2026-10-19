"use client";

import { useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Label } from "../ui/label";
import { Textarea } from "../ui/textarea";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";

/*
 * One dialog for the three version actions that need a look at what changes
 * first: "compare" (diff only), "promote" (diff vs the current PRODUCTION,
 * optional reason, force override once the eval gate refuses) and "rollback"
 * (moves PRODUCTION back one version; a label move, never a new snapshot).
 * The diff comes from GET /versions/diff?from=&to= (version ordinals).
 */
export type VersionDialogMode = "compare" | "promote" | "rollback";

interface DiffField {
  field: string;
  from: unknown;
  to: unknown;
}
interface DiffResult {
  fields: DiffField[];
  unchanged: boolean;
}
export interface GateReport {
  reason?: string | null;
  qualifyingRunId?: string | null;
  verdict?: string | null;
  overallScore?: number | null;
}

function show(v: unknown): string {
  if (v === null || v === undefined) return "—";
  const s = typeof v === "string" ? v : JSON.stringify(v);
  return s.length > 240 ? `${s.slice(0, 240)}…` : s;
}

export default function AgentVersionActionDialog({
  agentId,
  mode,
  fromNumber,
  toNumber,
  open,
  onOpenChange,
  busy,
  gate,
  error,
  onConfirm,
  locked,
}: {
  agentId: string;
  mode: VersionDialogMode;
  /** Version ordinal the diff starts from (null = nothing to compare against). */
  fromNumber: number | null;
  toNumber: number;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  busy?: boolean;
  /** Set when the eval gate refused the promote; reveals the force override. */
  gate?: GateReport | null;
  error?: string | null;
  onConfirm?: (opts: { force: boolean; reason: string }) => void;
  locked?: boolean;
}) {
  const t = useTranslations("agentsUi");
  const [diff, setDiff] = useState<DiffResult | null>(null);
  const [state, setState] = useState<"idle" | "loading" | "error">("idle");
  const [reason, setReason] = useState("");
  const [force, setForce] = useState(false);

  useEffect(() => {
    if (!open) return undefined;
    setReason("");
    setForce(false);
    setDiff(null);
    if (fromNumber == null) {
      setState("idle");
      return undefined;
    }
    let cancelled = false;
    setState("loading");
    api
      .diffAgentVersions(agentId, fromNumber, toNumber)
      .then((d: DiffResult) => {
        if (cancelled) return;
        setDiff(d);
        setState("idle");
      })
      .catch(() => {
        if (!cancelled) setState("error");
      });
    return () => {
      cancelled = true;
    };
  }, [open, agentId, fromNumber, toNumber]);

  const title =
    mode === "compare"
      ? t("diffTitle", { from: fromNumber ?? "—", to: toNumber })
      : mode === "promote"
        ? t("promoteTitle", { version: toNumber })
        : t("rollbackTitle");
  const needsReason = force;
  const canConfirm = !locked && !busy && (!needsReason || reason.trim().length > 0);

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>
            {mode === "compare" ? t("diffBody") : mode === "promote" ? t("promoteBody") : t("rollbackBody")}
          </AlertDialogDescription>
        </AlertDialogHeader>

        {state === "loading" && (
          <div className="flex items-center gap-2 text-xs text-muted-foreground" role="status">
            <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
            {t("diffLoading")}
          </div>
        )}
        {state === "error" && <p className="text-xs text-destructive">{t("diffFailed")}</p>}
        {fromNumber == null && mode !== "compare" && (
          <p className="text-xs text-muted-foreground">{t("diffNoBaseline")}</p>
        )}
        {diff && diff.unchanged && <p className="text-xs text-muted-foreground">{t("diffUnchanged")}</p>}
        {diff && !diff.unchanged && (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("diffField")}</TableHead>
                <TableHead>{t("diffBefore", { version: fromNumber ?? "—" })}</TableHead>
                <TableHead>{t("diffAfter", { version: toNumber })}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {diff.fields.map((f) => (
                <TableRow key={f.field}>
                  <TableCell className="align-top font-mono text-xs">{f.field}</TableCell>
                  <TableCell className="max-w-[16rem] break-words align-top text-xs text-muted-foreground">
                    {show(f.from)}
                  </TableCell>
                  <TableCell className="max-w-[16rem] break-words align-top text-xs text-foreground">
                    {show(f.to)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}

        {mode === "promote" && gate && (
          <div className="rounded-md border border-border bg-muted/50 px-3 py-2 text-xs" role="alert">
            <div className="font-medium text-foreground">{t("gateBlockedTitle")}</div>
            {gate.reason && <div className="mt-1 text-muted-foreground">{gate.reason}</div>}
            {gate.verdict && (
              <div className="mt-1 text-muted-foreground">
                {gate.verdict}
                {gate.overallScore != null ? ` · ${gate.overallScore}` : ""}
              </div>
            )}
            <label className="mt-2 flex items-center gap-2 text-foreground">
              <input type="checkbox" checked={force} onChange={(e) => setForce(e.target.checked)} />
              {t("forceLabel")}
            </label>
          </div>
        )}

        {mode === "promote" && (
          <div className="space-y-1">
            <Label htmlFor="promote-reason">{force ? t("reasonRequired") : t("reasonOptional")}</Label>
            <Textarea
              id="promote-reason"
              rows={2}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={t("reasonPlaceholder")}
            />
          </div>
        )}

        {error && (
          <p className="text-xs text-destructive" role="alert">
            {error}
          </p>
        )}

        <AlertDialogFooter>
          <AlertDialogCancel>{mode === "compare" ? t("close") : t("cancel")}</AlertDialogCancel>
          {mode !== "compare" && (
            <Button
              type="button"
              disabled={!canConfirm}
              onClick={() => onConfirm?.({ force, reason: reason.trim() })}
            >
              {busy && <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />}
              {mode === "promote" ? (force ? t("forcePromote") : t("promote")) : t("rollbackConfirm")}
            </Button>
          )}
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
