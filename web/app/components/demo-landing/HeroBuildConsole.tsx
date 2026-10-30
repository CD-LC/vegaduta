"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "../../lib/i18n";

// The landing hero's build console (split out of HeroOutcome.tsx): the
// per-workload build logs and the component that types and streams them.

// The console's per-workload build logs. QUOTED PRODUCT OUTPUT, not prose —
// the same shape Magic Studio's real planner emits (intent parse, live MCP
// registry hits, pool model pick with failover, guardrails, channel binds) —
// so like any CLI transcript they are not routed through i18n. The frame is
// labelled "simulated": steps are representative, the live studio on the
// page runs the real thing.
const BUILD_LOGS: Record<string, { steps: string[]; ship: string }> = {
  support: {
    steps: [
      "parse      → intent: docs.qa · handoff:human(conf<0.7)",
      "tools      → kb.search · ticket.create · slack.handoff",
      "registry   → +2 servers live from the MCP registry",
      "model      → auto from pool · failover chain ×3",
      "guardrails → cite.sources · approval[refund]",
      "channels   → web.chat · whatsapp · email",
    ],
    ship: '"support-copilot" live — 6 tools bound · 3 channels · guardrails on',
  },
  oncall: {
    steps: [
      "parse      → trigger: webhook · intent: incident.triage",
      "tools      → http.retry · incident.open · pager.page",
      "registry   → +3 servers live from the MCP registry",
      "model      → auto from pool · failover chain ×3",
      "guardrails → human.confirm[page] · audit.log:full",
      "runtime    → workflow engine · retries · run history",
    ],
    ship: '"oncall-triage" live — webhook armed · dedupe on · audit trail',
  },
  code: {
    steps: [
      "parse      → trigger: github.pr · intent: code.review",
      "tools      → repo.callgraph · sandbox.exec · slack.post",
      "registry   → +2 servers live from the MCP registry",
      "model      → auto from pool · failover chain ×3",
      "guardrails → diff.before.merge · approval[merge]",
      "channels   → github · slack",
    ],
    ship: '"pe-reviewer" live — call graph indexed · merge gated on you',
  },
  digest: {
    steps: [
      "parse      → trigger: cron(08:00 mon-fri) · intent: metrics.digest",
      "tools      → metrics.query · stats.anomaly(2σ) · telegram.send",
      "registry   → +1 server live from the MCP registry",
      "model      → auto from pool · failover chain ×3",
      "guardrails → read-only · audit.log:full",
      "channels   → telegram",
    ],
    ship: '"anomaly-digest" live — cron armed · read-only · audit trail',
  },
  voice: {
    steps: [
      "parse      → intent: voice.receptionist · lang:auto(22)",
      "tools      → call.answer · calendar.book · sms.send",
      "registry   → +2 servers live from the MCP registry",
      "model      → auto from pool · failover chain ×3",
      "guardrails → approval[refund] · transcript:full",
      "channels   → voice · whatsapp · sms",
    ],
    ship: '"front-desk" live — 22 languages · calendar wired · transcripts on',
  },
  sales: {
    steps: [
      "parse      → trigger: webhook(new lead) · intent: sales.followup",
      "tools      → crm.lead · bulk.email · reply.detect",
      "schedule   → interval(3d) · run history on",
      "model      → auto from pool · failover chain ×3",
      "guardrails → approval[discount] · audit.log:full",
      "channels   → email · whatsapp",
    ],
    ship: '"sales-followup" live — CRM wired · replies & bounces tracked',
  },
};

// BuildConsole — the hero's product quotation. Types the selected workload's
// spec on a prompt line, then streams the build steps one every ~200ms —
// the same pipeline Magic Studio runs for real (parse → tools → registry →
// model → guardrails → channels). Everything about the motion is a wall-
// clock illusion over static strings; under prefers-reduced-motion the whole
// transcript renders at once. The animated block is aria-hidden and the
// figure carries an sr-only summary — a screen reader gets the fact, not the
// theatre.
export default function BuildConsole({
  workloadId,
  brief,
  reduced,
  runLabel,
  onRunSpec,
}: {
  workloadId: string;
  brief: string;
  reduced: boolean;
  runLabel: string;
  onRunSpec: () => void;
}) {
  const tHC = useTranslations("hcComponentsDemolandingHeroBuildConsole");
  const spec = BUILD_LOGS[workloadId] ?? BUILD_LOGS.support;
  const total = spec.steps.length + 1; // +1 lands the ship line
  // Start empty even under reduced motion — the effect below fills the whole
  // transcript in one pass on mount, so SSR and first paint never flash a
  // complete console that then empties to replay.
  const [typed, setTyped] = useState(0);
  const [shown, setShown] = useState(0);

  useEffect(() => {
    if (reduced) {
      setTyped(brief.length);
      setShown(total);
      return undefined;
    }
    setTyped(0);
    setShown(0);
    const timers: number[] = [];
    let pos = 0;
    const typeId = window.setInterval(() => {
      pos = Math.min(pos + 2, brief.length);
      setTyped(pos);
      if (pos >= brief.length) {
        window.clearInterval(typeId);
        let n = 0;
        const stepId = window.setInterval(() => {
          n += 1;
          setShown(n);
          if (n >= total) window.clearInterval(stepId);
        }, 210);
        timers.push(stepId);
      }
    }, 22);
    return () => {
      window.clearInterval(typeId);
      for (const id of timers) window.clearInterval(id);
    };
  }, [workloadId, brief, reduced, total]);

  const done = shown > spec.steps.length;

  return (
    <div className="mkt-term px-4 py-3">
      {/* The transcript is theatre — a screen reader gets the figure's
          sr-only summary instead of a half-typed sentence. The run link is
          deliberately OUTSIDE the hidden block: it is a real control, and
          hiding an interactive element from AT is the bug aria-hidden exists
          to prevent. */}
      <div aria-hidden="true">
        <p className="mkt-term__line">
          <span className="mkt-term__ps">❯</span> <span className="mkt-term__cmd">{tHC("s1")}</span>{" "}
          <span className="mkt-term__arg">&ldquo;{brief.slice(0, typed)}&rdquo;</span>
          {!done && <span className="mkt-term__cursor" />}
        </p>
        {spec.steps.slice(0, shown).map((line) => {
          const arrow = line.indexOf("→");
          return (
            <p key={line} className="mkt-term__line mkt-term__in">
              {arrow > 0 ? (
                <>
                  <span className="mkt-term__label">{line.slice(0, arrow)}</span>
                  {line.slice(arrow)}
                </>
              ) : (
                line
              )}
            </p>
          );
        })}
        {done && <p className="mkt-term__line mkt-term__in mkt-term__ok">✓ {spec.ship}</p>}
      </div>
      {done && (
        <p className="mkt-term__line mkt-term__in">
          <a href="#live-studio" className="mkt-term__link" onClick={onRunSpec}>
            ▸ {runLabel} →
          </a>
        </p>
      )}
      <div aria-hidden="true">
        {done && (
          <p className="mkt-term__line">
            <span className="mkt-term__ps">❯</span> <span className="mkt-term__cursor" />
          </p>
        )}
      </div>
    </div>
  );
}
