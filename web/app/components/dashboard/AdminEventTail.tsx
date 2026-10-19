"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";

// Admin's answer to the AXON Dashboard's illustrative `feed()` (LiveEventTail.js)
// - same exact markup/CSS/animation (.ax-feed / .ax-feed-row / axFadeIn), but
// this one is real: it polls the actual append-only audit trail
// (AuditLogController.java, the same api.listAuditLog() AuditLogPanel.js
// already uses) instead of replaying eight canned sample lines. A genuinely
// new row only appears here when a genuinely new audit entry lands - no
// synthetic per-second injection.
//
// Tag/color comes from the entry's real `outcome` field (SUCCESS -> green
// "OK", anything else -> red "ERR"); message is `actionType · targetType
// [/targetId] [· detail]`, all straight off the entry. A quiet workspace
// just shows fewer rows and refreshes less visibly - never padded with
// fabricated activity to look busier than it is.
const POLL_MS = 6000;

// TS mirror of AuditLogEntryResponse.java (core/audit dto) -
// api.listAuditLog() is untyped (.then(toJson)), so the fields this tail
// actually reads are declared here at the consumer, same approach as
// SystemStatusPanel's SystemStatusSummary. `outcome` is the AuditOutcome
// enum serialized as its name; plain `string` on purpose so a new outcome
// constant just renders as the red "ERR" bucket (the existing behavior for
// anything non-SUCCESS) instead of failing the build.
interface AuditLogEntry {
  id: string;
  actionType: string;
  targetType: string | null;
  targetId: string | null;
  outcome: string;
  detail: string | null;
  createdAt: string; // ISO-8601 instant
}

function timeOf(iso: string) {
  try {
    return new Date(iso).toTimeString().slice(0, 8);
  } catch {
    return "--:--:--";
  }
}

function rowFor(entry: AuditLogEntry) {
  const ok = entry.outcome === "SUCCESS";
  const tag = ok ? "OK   " : "ERR  ";
  const color = ok ? "var(--ax-green)" : "var(--ax-red)";
  const target = entry.targetType ? `${entry.targetType}${entry.targetId ? `/${entry.targetId}` : ""}` : null;
  const parts = [entry.actionType, target, entry.detail].filter(Boolean);
  return {
    id: entry.id,
    time: timeOf(entry.createdAt),
    color,
    tag,
    msg: parts.join(" · "),
  };
}

/**
 * @param limit how many of the most recent audit rows to keep in view -
 *     the small Admin Overview widget passes nothing (defaults to 12, its
 *     original behavior, unchanged), the dedicated /admin/audit-tail page
 *     (#553, "a separate page to view LIVE AUDIT TAIL completely full")
 *     passes a much higher number so it isn't artificially truncated the
 *     way the small dashboard card has to be.
 * @param full when true, renders with the scrollable/expanded layout the
 *     standalone page needs (ax-feed-full, see globals.css) instead of the
 *     small card's fixed-height `overflow: hidden`.
 */
export default function AdminEventTail({ limit = 12, full = false }) {
  const t = useTranslations("dashboardEventTail");
  // Rows are the already-formatted display shape rowFor() produces, not raw
  // audit entries - derive the state type from it rather than duplicating.
  const [rows, setRows] = useState<ReturnType<typeof rowFor>[]>([]);
  const [error, setError] = useState(null);
  const seenIds = useRef(new Set());

  // Efficient background processing (2026-08-12, modern-web-guidance):
  // this feed now lives inside a .defer-offscreen (content-visibility)
  // section well below the fold on both dashboards, so polling the audit
  // log every 6s for rows the browser isn't even rendering is pure waste.
  // Polling is application logic, so per the guidance it's gated on an
  // IntersectionObserver (with the guide's 200px pre-visibility margin)
  // plus tab visibility - NOT contentvisibilityautostatechange, which is
  // for rendering-heavy work and fires on the container that owns the
  // content-visibility rule, not on this component. When either signal
  // flips back to visible, `active` re-runs the poll effect, which fetches
  // immediately - so the feed is fresh by the time it's seen.
  const wellRef = useRef(null);
  const [active, setActive] = useState(true);
  useEffect(() => {
    const el = wellRef.current;
    if (!el || typeof IntersectionObserver === "undefined") return undefined;
    let inView = true;
    let tabVisible = !document.hidden;
    const update = () => setActive(inView && tabVisible);
    const io = new IntersectionObserver(
      ([entry]) => {
        inView = entry.isIntersecting;
        update();
      },
      { rootMargin: "200px" }
    );
    io.observe(el);
    const onVisibility = () => {
      tabVisible = !document.hidden;
      update();
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      io.disconnect();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, []);

  useEffect(() => {
    if (!active) return undefined;
    let cancelled = false;

    async function poll() {
      try {
        const entries = await api.listAuditLog();
        if (cancelled) return;
        setError(null);
        // Newest first from the API isn't guaranteed - sort by createdAt so
        // the tail always reads oldest-at-top, newest-at-bottom like a real
        // scrolling log, then keep the most recent `limit`.
        const sorted = [...entries].sort((a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime());
        const latest = sorted.slice(-limit);
        latest.forEach((e) => seenIds.current.add(e.id));
        setRows(latest.map(rowFor));
      } catch (err) {
        if (!cancelled) setError(errorMessage(err, t("unavailableFallback")));
      }
    }

    void poll();
    const id = setInterval(() => {
      void poll();
    }, POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [limit, active, t]);

  const className = full ? "ax-feed ax-feed-full" : "ax-feed";
  // Recessed one rung below whatever card frames this (--surface-sunken,
  // elev-sunken utility, see dashboard.css's .mc-feed-well) - a scrolling
  // log reads as "inside" the panel, not the same flat tone as it.
  const wellClassName = full ? "elev-sunken mc-feed-well mc-feed-well-full" : "elev-sunken mc-feed-well";

  if (rows.length === 0) {
    return (
      <div ref={wellRef} className={wellClassName}>
        <div className={className}>
          <p className="muted" style={{ margin: 0, fontSize: "var(--fs-label)" }}>
            {error ? t("unavailablePrefix", { error }) : t("empty")}
          </p>
        </div>
      </div>
    );
  }

  return (
    <div ref={wellRef} className={wellClassName}>
      <div className={className}>
        {rows.map((row) => (
          <div key={row.id} className="ax-feed-row">
            <span className="ax-feed-time">{row.time}</span>
            <span className="ax-feed-tag" style={{ color: row.color }}>
              {row.tag}
            </span>
            <span className="ax-feed-msg">{row.msg}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
