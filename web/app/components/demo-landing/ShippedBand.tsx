"use client";

// ShippedBand (2026-09-28) — "Just shipped", directly under OutcomesBand.
//
// Why it exists: the landing was last rebuilt 2026-09-17, and everything the
// platform gained since then was invisible on it — App Studio + published
// apps, the sales-automation bundle, versioning, specialist handoff, the
// "Powered by" chip, SSO/SCIM. A returning visitor saw the same page as two
// weeks ago; a new one never learned the platform now builds the APP as well
// as the agent. One band of six dated cards fixes both, and a visible
// "shipped" date is itself a signal of momentum that no adjective carries.
//
// Honesty ledger — every card is LIVE in the hosted production deployment, checked against
// the deploy entries in docs/AGENT-COORDINATION.md on 2026-09-28:
//   app        -> web/app/build/studio/* (multi-page + device APIs 46f6cc86,
//                 Connect panel 9867718a), published apps with their own
//                 end-user sign-in 0c16029c (tenant V359); in prod since the
//                 f68cd8cd / web 1be3e2c3 deploy.
//   sales      -> WorkflowNodeType.CRM_LEAD + BULK_EMAIL, reply/bounce
//                 detection, AgentSchedule interval + run history (bb2fafcb);
//                 shipped in the a6ab887b deploy ("sales-automation gaps").
//   versions   -> agent/workflow versioning V342 (5cdf7cf5), fa41ffbc deploy.
//   team       -> SpecialistHandoffService (91abca0f, audited via
//                 AgentHandoffAudit), signed A2A agent cards (1b3adfc2).
//                 NOT claimed: the SUPERVISOR workflow node (prod status
//                 unverified).
//   powered    -> PoweredByChip.tsx (6c2d0271), f68cd8cd deploy.
//   enterprise -> core/admin sso/TenantSsoController + ScimProvisioning
//                 Controller; FraudDetectionInterceptor (hCaptcha + IP/user
//                 blocklist) in the fa41ffbc deploy.
// The footnote names what is deliberately NOT claimed. Agent UPI payments
// exist in code (07013857) but settle through the PLATFORM's Razorpay keys,
// not the tenant's own account — so "your agent collects your payments" is
// still false and stays off this page. Re-verify before promoting any of
// these into the ledger or FAQ.
//
// 2026-10-03 — second edition, new copy keys (landing.sx_*). The September
// six moved into CapabilityAtlas as tiles (appstudio, sales, versions, teams,
// sso; "powered by" stays in the hero marquee) and this band now carries what
// shipped after them. Every card is in the prod image c35658f3 (deploy entry
// 2026-10-03), checked with `git cat-file -e c35658f3:<path>`:
//   lab    -> web/app/components/agents/lab/*, /api/agents/{id}/lab/** (live
//             since the 5766f175 deploy, 2026-09-30).
//   skills -> core/agents-turn/src/main/resources/skills (14 folders), /skills,
//             "Distill skills" (staged drafts stay off until approved).
//   teams  -> AgentTeamSwarmCard.tsx + delegate_parallel; limits wording
//             mirrors agentWorkbench.teamSwarm* (depth 1, per-branch timeout,
//             one shared per-run budget).
//   flow   -> WorkflowExpressionService, V374 workflow_node_pin, draft-mode
//             canvas runs, WorkflowBackgroundRunService.
//   evals  -> web/app/agents/quality/* (datasets, six judges, statistics with
//             intervals, promotion gate). Simulation is API-first and is NOT
//             claimed on the card.
//   memory -> web/app/memory/page.tsx (/api/memory).
// None of these has been click-tested signed-in on prod by the session that
// wrote this copy; the claim is "deployed", which is what the cards say.

import Link from "next/link";
import { ArrowRight, BookOpen, Braces, Brain, FlaskConical, Gauge, Users } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { useJourney } from "../../lib/journey";
import { sendLandingEvent } from "./landingBeacon";

const ITEMS = [
  { id: "lab", icon: FlaskConical },
  { id: "skills", icon: BookOpen },
  { id: "teams", icon: Users },
  { id: "flow", icon: Braces },
  { id: "evals", icon: Gauge },
  { id: "memory", icon: Brain },
] as const;

export default function ShippedBand() {
  const t = useTranslations("landing");
  const journey = useJourney();
  const tried = journey.stage === "keep";

  return (
    <section id="shipped" className="mkt-band scroll-mt-16" data-testid="shipped-band">
      <div className="mkt-band__inner">
        <div className="mkt-head mkt-reveal">
          <p className="mkt-eyebrow">{t("sx_eyebrow")}</p>
          <h2 className="mkt-h2">{t("sx_title")}</h2>
          <p className="mkt-section-sub mkt-ink-2">{t("sx_sub")}</p>
        </div>

        <div className="mkt-reveal-group mt-10 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {ITEMS.map(({ id, icon: Icon }, i) => (
            <article
              key={id}
              className="mkt-card mkt-card--interactive mkt-reveal flex flex-col p-5"
              // The first card is the headline act (see why an agent answered
              // the way it did) — give it the accent edge.
              style={i === 0 ? { borderColor: "var(--mkt-accent)" } : undefined}
            >
              <div className="flex items-center justify-between gap-3">
                <span
                  className="inline-flex size-10 items-center justify-center rounded-xl"
                  style={{ background: "color-mix(in oklab, var(--mkt-accent) 12%, transparent)", color: "var(--mkt-accent)" }}
                  aria-hidden="true"
                >
                  <Icon className="size-5" />
                </span>
                <span className="mkt-pill mkt-pill--accent">{t("sh_newChip")}</span>
              </div>
              <h3 className="mkt-h3 mt-4">{t(`sx_${id}_title`)}</h3>
              <p className="mkt-body mkt-ink-2 mt-2 flex-1">{t(`sx_${id}_body`)}</p>
              <p className="mkt-caption mkt-ink-3 mt-4">
                <span className="mkt-ui-label">{t("sh_whereLabel")}</span> {t(`sx_${id}_where`)}
              </p>
            </article>
          ))}
        </div>

        <div className="mt-10 flex flex-col items-center gap-3 sm:flex-row sm:justify-center">
          <Link
            href={journey.signupHref}
            onClick={() => sendLandingEvent("shipped_signup_click")}
            className="mkt-btn mkt-btn-primary mkt-btn-lg w-full sm:w-auto"
          >
            {tried ? t("hero_ctaKeepAgent") : t("sx_ctaSignup")} <ArrowRight className="size-4" aria-hidden="true" />
          </Link>
          <Link
            href={journey.tryHref}
            onClick={() => sendLandingEvent("shipped_try_click")}
            className="mkt-btn mkt-btn-secondary mkt-btn-lg w-full sm:w-auto"
          >
            {tried ? t("hero_ctaReopenSandbox") : t("oc_ctaTry")}
          </Link>
        </div>
        <p className="mkt-caption mkt-ink-3 mx-auto mt-4 max-w-2xl text-center">{t("sx_foot")}</p>
      </div>
    </section>
  );
}
