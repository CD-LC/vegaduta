"use client";

// UseCasesBand — the page's "Use cases" section (direct instruction,
// 2026-09-24: "this needs proper use cases and ensure we are standard").
//
// Standard landing anatomy is hero → outcomes → use cases → proof → CTA.
// This page had outcomes (OutcomesBand), breadth (CapabilityAtlas) and the
// industry case-file (BusinessCaseBand, mounted near the bottom as a free
// case-study OFFER rather than a use-cases grid), but nothing that answered
// "what would I actually build with it?" in the conventional place — after
// the channel wall, before the live studio. This band is that answer.
//
// Six cards, one sentence each — the literal "One sentence in. A running
// workflow out." promise (magic_title) made concrete. Every card:
//   * the brief — a real sentence a visitor could type verbatim;
//   * the shape it compiles to — trigger/agent/approval/channel chips, the
//     same node vocabulary the workflow-recipes guide uses (NODE_META in
//     recipes-types.ts);
//   * "Run this spec" — hands the sentence to the on-page Magic Studio via
//     queueMagicPrompt + the #live-studio anchor, the identical handoff the
//     hero console uses (see magicPrefill.ts);
//   * "Full recipe" — a real /help/workflow-recipes/<industry> page, so the
//     card is a doorway to a shipped guide, never a dead end.
//
// NO-FICTION LEDGER — every card maps to a shipped mechanism AND a real
// recipe page in the guide's 89-industry corpus (recipes-index.ts):
//   support  -> WhatsApp/Email channels + KB grounding + handoff; recipes:
//               /help/workflow-recipes/ecommerce ("Self-serve order tracking
//               on WhatsApp").
//   oncall   -> webhook trigger + dedupe + human-confirmed paging; recipes:
//               /help/workflow-recipes/security-ops ("On-call escalation
//               with a written incident summary").
//   code     -> code-intel call graph + sandboxed workspace + merge gate;
//               recipes: /help/workflow-recipes/software-teams ("PR review
//               routed to specialist reviewers").
//   invoice  -> schedule trigger + channel send + approval on disputes;
//               recipes: /help/workflow-recipes/payments ("Refund request
//               with mandatory human approval"). The agent sends a payment
//               LINK — no agent-collected money is claimed, same rule as
//               HeroOutcome's honesty ledger.
//   research -> fan_out/map-reduce over sub-questions; recipes:
//               /help/workflow-recipes/multi-agent-patterns; the same shape
//               as workflow-templates/deep-research-plan-search-synthesize.json.
//   voice    -> ChannelType VOICE + calendar tooling; recipes:
//               /help/workflow-recipes/call-center ("Live voice agent that
//               listens and replies out loud"). Agents ANSWER calls — no
//               outbound dialling is claimed anywhere.
//
// Beacons: usecases_try_click / usecases_recipe_click are new names — they
// need adding to core's LandingEventController.ALLOWED_EVENTS(_ORDERED)
// before they count (core-3 scope; flagged in AGENT-COORDINATION). Until
// then they 204-drop by design — same convention as oss_try_click.

import { Fragment } from "react";
import Link from "next/link";
import {
  ArrowRight,
  ArrowUpRight,
  GitPullRequest,
  Headset,
  IndianRupee,
  Network,
  Phone,
  Siren,
  type LucideIcon,
} from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { queueMagicPrompt } from "../try/magicPrefill";
import { sendLandingEvent } from "./landingBeacon";

// Flow-node labels are generic on purpose — one small key set (uc_n_*) covers
// every card's chain, and the labels match the recipes guide's own node
// vocabulary (Trigger / Agent / Approval / Output …).
type NodeKey =
  | "channel"
  | "voice"
  | "webhook"
  | "cron"
  | "agent"
  | "parallel"
  | "kb"
  | "tools"
  | "approval"
  | "reply"
  | "page"
  | "output";

type UseCase = {
  id: string;
  icon: LucideIcon;
  flow: NodeKey[];
  /** Real industry page in the workflow-recipes guide. */
  recipe: string;
};

const USE_CASES: UseCase[] = [
  { id: "support", icon: Headset, flow: ["channel", "kb", "approval", "reply"], recipe: "ecommerce" },
  { id: "oncall", icon: Siren, flow: ["webhook", "agent", "approval", "page"], recipe: "security-ops" },
  { id: "code", icon: GitPullRequest, flow: ["webhook", "tools", "approval", "reply"], recipe: "software-teams" },
  { id: "invoice", icon: IndianRupee, flow: ["cron", "agent", "approval", "channel"], recipe: "payments" },
  { id: "research", icon: Network, flow: ["agent", "parallel", "tools", "output"], recipe: "multi-agent-patterns" },
  { id: "voice", icon: Phone, flow: ["voice", "agent", "tools", "reply"], recipe: "call-center" },
];

export default function UseCasesBand() {
  const t = useTranslations("landing");

  return (
    <section id="use-cases" className="mkt-band mkt-band--ruled scroll-mt-16">
      <div className="mkt-band__inner">
        {/* `.mkt-reveal` (CSS view-timeline), not framer-motion — same swap
            as every other band on this page; degrades to already-visible. */}
        <div className="mkt-reveal mkt-head mkt-head--center">
          <p className="mkt-pill mkt-pill--accent mkt-badge">{t("uc_eyebrow")}</p>
          <h2 className="mkt-h2">{t("uc_title")}</h2>
          <p className="mkt-section-sub mkt-ink-2 text-pretty">{t("uc_sub")}</p>
        </div>

        <div className="mkt-reveal-group mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {USE_CASES.map((uc) => {
            const Icon = uc.icon;
            const brief = t(`uc_${uc.id}_brief`);
            return (
              <article key={uc.id} className="mkt-card mkt-card--interactive flex flex-col p-5">
                <div className="flex items-center gap-3">
                  <div className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/12 text-primary">
                    <Icon className="size-5" aria-hidden="true" />
                  </div>
                  <h3 className="mkt-card-title">{t(`uc_${uc.id}_title`)}</h3>
                </div>

                {/* The sentence itself — the visitor's input, quoted in the
                    same pinned-note register the case file uses for its
                    brief. */}
                <p className="mkt-small mkt-ink-2 mt-3 italic">“{brief}”</p>

                {/* What it compiles to — the node chain, not a feature list. */}
                <div className="mt-4 flex flex-wrap items-center gap-x-1.5 gap-y-2">
                  {uc.flow.map((node, i) => (
                    <Fragment key={`${node}-${i}`}>
                      {i > 0 && <ArrowRight className="size-3 shrink-0" style={{ color: "var(--mkt-ink-3)" }} aria-hidden="true" />}
                      <span className="mkt-badge mkt-ink-3 whitespace-nowrap rounded-full border px-2 py-0.5" style={{ borderColor: "var(--mkt-line)" }}>
                        {t(`uc_n_${node}`)}
                      </span>
                    </Fragment>
                  ))}
                </div>

                <div className="mt-auto flex items-center justify-between gap-3 pt-5">
                  {/* Anchor, not router.push: #live-studio scrolls to the
                      studio on this page, and queueMagicPrompt lands the
                      sentence in its prompt box even while the lazy chunk
                      is still loading (magicPrefill.ts). */}
                  <a
                    href="#live-studio"
                    onClick={() => {
                      queueMagicPrompt(brief);
                      sendLandingEvent("usecases_try_click");
                    }}
                    className="mkt-link inline-flex items-center gap-1 font-medium"
                  >
                    {t("uc_run")} <ArrowRight className="size-3.5" aria-hidden="true" />
                  </a>
                  <Link
                    href={`/help/workflow-recipes/${uc.recipe}`}
                    onClick={() => sendLandingEvent("usecases_recipe_click")}
                    className="mkt-caption inline-flex items-center gap-1 text-muted-foreground transition-colors hover:text-foreground"
                  >
                    {t("uc_recipe")} <ArrowUpRight className="size-3" aria-hidden="true" />
                  </Link>
                </div>
              </article>
            );
          })}
        </div>

        {/* The catalogue exit — six cards are the sample, not the set. */}
        <p className="mkt-caption mkt-ink-3 mt-8 text-center">
          <Link href="/help/workflow-recipes" className="mkt-link font-medium">
            {t("uc_all")} <ArrowRight className="inline size-3.5" aria-hidden="true" />
          </Link>
        </p>
      </div>
    </section>
  );
}
