"use client";

import { useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import Link from "next/link";
import { ArrowUpRight, Compass, Loader2, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import { useAuth } from "../AuthProvider";
import TuneBox from "./TuneBox";
import { coachCards, type CoachCard } from "./coach";
import { pct } from "./ledger";
import { useBriefLedger, useEnvoyFor } from "./useBrief";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";

// Coach: a right drawer that reads the envoy's proof ledger and the planner's
// own "if results are wrong" notes and turns them into a few concrete places to
// look, each deep-linked to the real control. It is mounted (via CoachButton)
// on the Workbench, the Canvas and /tools; it is not a page of its own.

function CardView({ card, onNavigate }: { card: CoachCard; onNavigate: () => void }) {
  const t = useTranslations("brief");
  const title = card.knowledgeEmpty
    ? t("coach.card.knowledgeEmpty", { agent: card.agentName })
    : t(`coach.card.${card.target}`, { agent: card.agentName });
  return (
    <li className="space-y-2 rounded-xl border border-border bg-card p-3">
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="text-sm font-semibold text-foreground">{title}</h4>
        {card.dimension && card.score != null && (
          <span className="rounded-full border border-border px-2 py-0.5 text-[0.6875rem] tabular-nums text-muted-foreground">
            {t("coach.scored", { dimension: t(`coach.dimension.${card.dimension}`), score: pct(card.score) })}
          </span>
        )}
      </div>
      {card.hint && <p className="text-sm leading-relaxed text-muted-foreground">{card.hint}</p>}
      {card.href ? (
        <Button asChild size="sm" variant="outline">
          <Link href={card.href} onClick={onNavigate}>
            {t("coach.open")}
            <ArrowUpRight className="size-3.5" aria-hidden="true" />
          </Link>
        </Button>
      ) : (
        <p className="text-xs text-muted-foreground">{t("coach.notApplied")}</p>
      )}
    </li>
  );
}

export function CoachDrawer({
  entityId,
  onClose,
  onChanged,
}: {
  entityId?: string | null;
  onClose: () => void;
  onChanged?: () => void;
}) {
  const t = useTranslations("brief");
  const { hasPermission } = useAuth();
  const closeRef = useRef<HTMLButtonElement>(null);
  const { status, envoys, selectedId, select, detail } = useEnvoyFor(true, entityId);
  const { ledger, error: ledgerError, loading, reload } = useBriefLedger(detail ? selectedId : null);

  const panelRef = useRef<HTMLElement>(null);
  useDialogFocus(panelRef, closeRef, onClose);

  const cards = useMemo(() => (detail ? coachCards(detail.brief, ledger, detail.bindings) : []), [detail, ledger]);
  const locked = ledger != null && ledger.state !== "COLLECTING" && ledger.state !== "READY";

  // Portal to <body>: the drawer's mount points (workflow topbar, workbench)
  // live inside backdrop-filtered/isolated shells, which turn position:fixed
  // into "fixed to that box" - the backdrop and panel were clipped to the
  // topbar strip and painted under the canvas. Portalling escapes every
  // ancestor stacking context; z-[60]/z-[70] clear the nav rail (z-50) and
  // flyouts (z-60). Focus trap/Escape/restore are unchanged - useDialogFocus
  // works on refs, not DOM position.
  return createPortal(
    <>
      <div className="fixed inset-0 z-[60] bg-black/40" onClick={onClose} aria-hidden="true" />
      <aside
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={t("coach.title")}
        className="elev-raised fixed inset-y-0 right-0 z-[70] flex w-[min(26rem,100vw)] flex-col gap-4 overflow-y-auto border-l border-border p-5 shadow-xl"
      >
        <div className="flex items-start justify-between gap-3">
          <div>
            <h3 className="text-base font-semibold leading-snug text-foreground">{t("coach.title")}</h3>
            <p className="text-xs text-muted-foreground">{t("coach.subtitle")}</p>
          </div>
          <Button ref={closeRef} variant="ghost" size="icon" onClick={onClose} aria-label={t("narrative.close")}>
            <X className="size-4" aria-hidden="true" />
          </Button>
        </div>

        {(status === "loading" || status === "idle") && (
          <p className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" aria-hidden="true" />
            {t("coach.loading")}
          </p>
        )}
        {status === "error" && (
          <Alert variant="destructive">
            <AlertDescription>{t("coach.loadFailed")}</AlertDescription>
          </Alert>
        )}
        {status === "ready" && envoys.length === 0 && (
          <p className="text-sm leading-relaxed text-muted-foreground">
            {entityId ? t("coach.notInEnvoy") : t("coach.noEnvoys")}
          </p>
        )}

        {status === "ready" && envoys.length > 1 && (
          <label className="space-y-1 text-xs font-medium text-muted-foreground">
            {t("coach.pick")}
            <select
              className="block w-full rounded-md border border-border bg-card px-2 py-1.5 text-sm text-foreground"
              value={selectedId ?? ""}
              onChange={(e) => select(e.target.value)}
            >
              {envoys.map((r) => (
                <option key={r.item.id} value={r.item.id}>
                  {r.item.title}
                </option>
              ))}
            </select>
          </label>
        )}

        {detail && selectedId && (
          <>
            <p className="text-sm font-medium text-foreground">{detail.brief.title}</p>
            {loading && !ledger && (
              <p className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                {t("coach.loadingLedger")}
              </p>
            )}
            {ledgerError && (
              <Alert variant="destructive">
                <AlertDescription>{t("coach.loadFailed")}</AlertDescription>
              </Alert>
            )}
            {locked && (
              <Alert>
                <AlertDescription>{ledger?.reason?.trim() || t("ledger.lockedNoReason")}</AlertDescription>
              </Alert>
            )}
            {ledger && !locked && cards.length === 0 && (
              <p className="text-sm text-muted-foreground">{t("coach.noCards")}</p>
            )}
            {cards.length > 0 && (
              <ul className="space-y-3" aria-label={t("coach.cardsLabel")}>
                {cards.map((c) => (
                  <CardView key={c.id} card={c} onNavigate={onClose} />
                ))}
              </ul>
            )}
            <TuneBox
              briefId={selectedId}
              brief={detail.brief}
              disabled={!hasPermission("MANAGE_AGENTS")}
              disabledReason={t("tune.needsPermission")}
              onApplied={() => {
                void reload();
                // The page behind the drawer (ledger, trust ladder, graph) shows the same envoy: refresh it too.
                onChanged?.();
              }}
            />
          </>
        )}
      </aside>
    </>,
    document.body
  );
}

/**
 * The small "Coach" trigger dropped into Workbench / Canvas / Tools. Owns its
 * own open state so a mount point is one line: <CoachButton entityId={...} />.
 */
export default function CoachButton({
  entityId,
  className,
  onChanged,
}: {
  entityId?: string | null;
  className?: string;
  onChanged?: () => void;
}) {
  const t = useTranslations("brief");
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className={className}
        onClick={() => setOpen(true)}
        aria-haspopup="dialog"
        data-tour="coach"
      >
        <Compass size={13} aria-hidden="true" /> {t("coach.trigger")}
      </Button>
      {open && <CoachDrawer entityId={entityId} onClose={() => setOpen(false)} onChanged={onChanged} />}
    </>
  );
}
