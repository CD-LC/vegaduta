"use client";

import { useCallback, useEffect, useState } from "react";
import { Check, ChevronRight, Loader2, ShieldAlert, X } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import { useAuth } from "../AuthProvider";
import RungBadge from "./RungBadge";
import { demoteView, eventViews, ladderSteps, proposalView, type EventView } from "./trust";
import type { Rung, TrustEvent, TrustEvidenceLine, TrustProposal } from "./types";

// The three rungs, the evidence for the next one, the exact change it would
// make, and the history of every move. Promotion is a proposal a person
// accepts, never something that happens on a timer. Demotion is one click.
// Anything the person cannot do (not a tenant admin, nothing to promote to) is
// shown with the reason rather than hidden.

function Evidence({ line }: { line: TrustEvidenceLine }) {
  const t = useTranslations("brief");
  const Icon = line.met ? Check : X;
  return (
    <li className="flex items-start gap-2 text-sm">
      <span
        className="mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-full border border-border"
        data-ok={line.met}
      >
        <Icon className="size-3" aria-hidden="true" />
        <span className="sr-only">{line.met ? t("trust.met") : t("trust.notMet")}</span>
      </span>
      <span className={line.met ? "text-foreground" : "text-muted-foreground"}>
        {line.label}
        {line.detail && <span className="text-muted-foreground">{` ${line.detail}`}</span>}
      </span>
    </li>
  );
}

function EventRow({ e }: { e: EventView }) {
  const t = useTranslations("brief");
  const to = e.to ? t(`rung.${e.to}`) : "";
  const title =
    e.kind === "PROMOTED"
      ? t("trust.event.PROMOTED", { rung: to })
      : e.kind === "DEMOTED"
        ? t(e.auto ? "trust.event.DEMOTED_AUTO" : "trust.event.DEMOTED", { rung: to })
        : t(`trust.event.${e.kind}`);
  return (
    <li className="space-y-1 border-b border-border py-2 last:border-b-0">
      <div className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
        <span className="font-medium text-foreground">{title}</span>
        <time className="text-xs text-muted-foreground" dateTime={e.at}>
          {new Date(e.at).toLocaleString()}
        </time>
      </div>
      {e.reason && <p className="text-sm text-muted-foreground">{e.reason}</p>}
      {e.evidence.length > 0 && (
        <details className="text-xs text-muted-foreground">
          <summary className="cursor-pointer">{t("trust.showEvidence")}</summary>
          <ul className="mt-1 space-y-1">
            {e.evidence.map((l) => (
              <Evidence key={l.key} line={l} />
            ))}
          </ul>
        </details>
      )}
    </li>
  );
}

export default function TrustLadder({
  briefId,
  rung,
  events,
  onChanged,
  className,
}: {
  briefId: string;
  rung: Rung;
  /** trustEvents from the ledger. */
  events: readonly TrustEvent[];
  /** Called after a promotion or demotion so the parent reloads detail and ledger. */
  onChanged: () => void;
  className?: string;
}) {
  const t = useTranslations("brief");
  const { isAdmin } = useAuth();
  const [proposal, setProposal] = useState<TrustProposal | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"promote" | "demote" | null>(null);
  const [dismissed, setDismissed] = useState<Rung | null>(null);
  // Demote used to fire on a single click with no confirmation, unlike
  // promote (gated behind the proposal panel's preview above). This adds a
  // lightweight, in-place confirm step naming the target rung.
  const [confirmingDemote, setConfirmingDemote] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setProposal(await api.trustProposal(briefId));
    } catch (e: any) {
      setError(e?.message || t("trust.loadFailed"));
    } finally {
      setLoading(false);
    }
  }, [briefId, t]);

  // The proposal endpoint is tenant-admin only: for anyone else the explanation
  // below stands in for it instead of a request that can only 403.
  useEffect(() => {
    setProposal(null);
    if (isAdmin) void load();
  }, [isAdmin, load, rung]);

  async function move(to: Rung, kind: "promote" | "demote") {
    setBusy(kind);
    setError(null);
    try {
      await api.setTrustRung(briefId, to);
      onChanged();
    } catch (e: any) {
      setError(e?.message || t("trust.moveFailed"));
    } finally {
      setBusy(null);
    }
  }

  const view = proposalView(proposal, rung, isAdmin);
  const demote = demoteView(rung, isAdmin);
  const history = eventViews(events);
  // proposal === null is "not loaded (yet)" or "failed to load": never "core proposed nothing", which is a real answer.
  const showProposal = isAdmin && !loading && proposal !== null && (dismissed == null || dismissed !== view.target);

  return (
    <section
      className={cn("space-y-5 rounded-xl border border-border bg-card p-5", className)}
      aria-label={t("trust.title")}
    >
      <div>
        <h3 className="text-base font-semibold text-foreground">{t("trust.title")}</h3>
        <p className="text-sm text-muted-foreground">{t("trust.subtitle")}</p>
      </div>

      <ol className="flex flex-wrap items-center gap-2" aria-label={t("trust.ladderLabel")}>
        {ladderSteps(rung).map((s, i) => (
          <li
            key={s.rung}
            aria-current={s.state === "current" ? "step" : undefined}
            className="flex items-center gap-2"
          >
            {i > 0 && <ChevronRight className="size-4 text-muted-foreground" aria-hidden="true" />}
            <span
              className={cn("rounded-full", s.state === "current" ? "ring-2 ring-primary" : "")}
              data-state={s.state}
            >
              <RungBadge rung={s.rung} />
            </span>
          </li>
        ))}
      </ol>
      <p className="text-sm text-muted-foreground">{t(`trust.meaning.${rung}`)}</p>

      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {!isAdmin && (
        <div className="flex items-start gap-3 rounded-lg border border-dashed border-border p-4">
          <ShieldAlert className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <p className="text-sm text-muted-foreground">{t("trust.needsAdmin")}</p>
        </div>
      )}

      {isAdmin && loading && (
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          {t("trust.loading")}
        </p>
      )}

      {showProposal && view.state === "none" && (
        <div className="space-y-2 rounded-lg border border-dashed border-border p-4">
          <p className="text-sm font-medium text-foreground">
            {rung === "AUTONOMOUS" ? t("trust.topRung") : t("trust.noProposal")}
          </p>
          {view.reason && <p className="text-sm text-muted-foreground">{view.reason}</p>}
          {view.evidence.length > 0 && (
            <ul className="space-y-1">
              {view.evidence.map((l) => (
                <Evidence key={l.key} line={l} />
              ))}
            </ul>
          )}
        </div>
      )}

      {showProposal && view.target && (
        <div className="space-y-3 rounded-lg border border-border p-4" aria-label={t("trust.proposalLabel")}>
          <div className="flex flex-wrap items-center gap-2">
            <h4 className="text-sm font-semibold text-foreground">
              {t("trust.proposalTitle", { rung: t(`rung.${view.target}`) })}
            </h4>
            <span className="text-xs tabular-nums text-muted-foreground">
              {t("trust.evidenceCount", { met: view.metCount, total: view.evidence.length })}
            </span>
          </div>
          {view.reason && <p className="text-sm text-muted-foreground">{view.reason}</p>}
          <ul className="space-y-1.5">
            {view.evidence.map((l) => (
              <Evidence key={l.key} line={l} />
            ))}
          </ul>
          {view.effects.length > 0 && (
            <div className="space-y-1">
              <h5 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {t("trust.willChange")}
              </h5>
              <ul className="list-disc space-y-1 pl-4">
                {view.effects.map((d, i) => (
                  <li key={i} className="text-sm text-foreground">
                    {d}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {view.gate === "wrongTarget" && <p className="text-xs text-muted-foreground">{t("trust.wrongTarget")}</p>}
          {view.state === "pending" && view.gate !== "wrongTarget" && (
            <p className="text-xs text-muted-foreground">{t("trust.notEnough")}</p>
          )}
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              disabled={view.gate !== null || busy !== null}
              onClick={() => view.target && move(view.target, "promote")}
            >
              {busy === "promote" && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
              {t("trust.promote", { rung: t(`rung.${view.target}`) })}
            </Button>
            <Button size="sm" variant="outline" disabled={busy !== null} onClick={() => setDismissed(view.target)}>
              {t("trust.notYet")}
            </Button>
          </div>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3">
        {!confirmingDemote ? (
          <Button
            size="sm"
            variant="outline"
            disabled={demote.gate !== null || busy !== null}
            onClick={() => setConfirmingDemote(true)}
          >
            {demote.target ? t("trust.demote", { rung: t(`rung.${demote.target}`) }) : t("trust.demoteNone")}
          </Button>
        ) : (
          <>
            <span className="text-xs text-muted-foreground">
              {t("trust.demoteConfirm", { rung: demote.target ? t(`rung.${demote.target}`) : "" })}
            </span>
            <Button
              size="sm"
              variant="destructive"
              disabled={busy !== null}
              onClick={() => {
                setConfirmingDemote(false);
                if (demote.target) void move(demote.target, "demote");
              }}
            >
              {busy === "demote" && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
              {t("trust.demoteConfirmYes")}
            </Button>
            <Button size="sm" variant="ghost" disabled={busy !== null} onClick={() => setConfirmingDemote(false)}>
              {t("trust.demoteConfirmCancel")}
            </Button>
          </>
        )}
        {!confirmingDemote && demote.gate === "needsAdmin" && (
          <span className="text-xs text-muted-foreground">{t("trust.demoteNeedsAdmin")}</span>
        )}
        {!confirmingDemote && demote.gate === "atBottom" && (
          <span className="text-xs text-muted-foreground">{t("trust.atBottom")}</span>
        )}
        {!confirmingDemote && demote.gate === null && (
          <span className="text-xs text-muted-foreground">{t("trust.demoteHint")}</span>
        )}
      </div>

      <div className="space-y-1">
        <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{t("trust.history")}</h4>
        {history.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("trust.noHistory")}</p>
        ) : (
          <ul>
            {history.map((e) => (
              <EventRow key={e.id} e={e} />
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
