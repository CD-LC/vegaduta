"use client";

import { Check } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { FREE_MODE } from "../lib/freeMode";
import { AGENT_LIMIT, WORKFLOW_LIMIT } from "../lib/planLimits";
import { cn } from "../lib/utils";

// "What you get out of the box" - the ONE list of what a Free workspace
// already has switched on, rendered on /signup (IntroStep), on the first
// post-login screen (/admin and / when the tenant has zero agents) and
// anywhere else that needs to say it. Funnel audit, 2026-09: the only
// concrete out-of-box list lived in the landing page's PricingBand Free row;
// /signup showed three abstract proof cards and nothing post-login said what
// was already included, so a brand-new tenant-admin's first instinct was to
// go hunting for an API key the Free plan never needs.
//
// The copy lives in en.json under landing.freeIncludes so the 30-locale
// translation pipeline covers it, and every number is interpolated from
// FREE_PLAN_FACTS below rather than typed into the string - the same
// "mirrored, documented constant" idiom lib/planLimits.ts uses - so the
// landing pricing row, /signup and the console can't quietly drift apart.
//
// Sources for each fact (all real, enforced caps - never a marketing claim):
//   agents / workflows -> lib/planLimits.ts (TenantLimits#maxAgents /
//                        #maxWorkflows, FREE row of V101__plan_limits.sql)
//   seats              -> TenantLimitsServiceImpl's FREE seat cap (the same 2
//                        signup/page.tsx's VESSEL and admin/page.tsx's
//                        TIER_SEATS mirror)
//   hostedMsgsPerDay   -> FreeTierUsageLimiterService (30/day - see the header
//                        comment on components/UsageQuota.tsx)
//   knowledgeCollections -> PricingBand Free row f4 ("1 knowledge base
//                        collection"), the tenant's FREE collection cap
//   indianLanguages    -> the landing hero's own "22+" figure (i18n-config's
//                        Indian-language locale count)
export const FREE_PLAN_FACTS = {
  agents: AGENT_LIMIT,
  workflows: WORKFLOW_LIMIT,
  seats: 2,
  hostedMsgsPerDay: 30,
  knowledgeCollections: 1,
  indianLanguages: 22,
} as const;

// Free mode (lib/freeMode.ts): every tenant resolves UNLIMITED server-side,
// so the FREE-tier counts above would be a lie on /signup. The hosted-pool
// number stays a NUMBER rather than "Unlimited" because the fair-use cap
// genuinely still exists - FreeTierUsageLimiterService is untouched by free
// mode, it just reads the UNLIMITED multiplier (3x the 30/day base = 90).
const FREE_MODE_FACTS = {
  ...FREE_PLAN_FACTS,
  agents: "Unlimited",
  workflows: "Unlimited",
  seats: "Unlimited",
  knowledgeCollections: "Unlimited",
  hostedMsgsPerDay: 90,
} as const;

// Render order. Each entry is a key under en.json landing.freeIncludes; a
// keyed object rather than a JSON array on purpose - scripts/translate-
// messages.mjs flattens nested objects to dotted leaf keys and hashes each
// leaf as a string, so an array leaf would break the weekly translation cron.
const FREE_INCLUDE_KEYS = [
  "hostedModel",
  "agents",
  "workflows",
  "seats",
  "channels",
  "knowledge",
  "approvals",
  "languages",
] as const;

export default function FreePlanIncludes({
  heading = true,
  compact = false,
  className,
}: {
  /** Show the "Included free, already on" caption above the list. */
  heading?: boolean;
  /** Tighter type/spacing for inside a Card; default is the /signup size. */
  compact?: boolean;
  className?: string;
}) {
  const t = useTranslations("landing");
  return (
    <div className={cn("space-y-2", className)}>
      {heading && (
        <p className="font-mono text-[length:var(--fs-label)] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
          {t("freeIncludes.heading")}
        </p>
      )}
      <ul className={cn("grid grid-cols-1 gap-x-5 sm:grid-cols-2", compact ? "gap-y-1" : "gap-y-1.5")}>
        {FREE_INCLUDE_KEYS.map((key) => (
          <li
            key={key}
            className={cn("flex items-start gap-2 text-foreground", compact ? "text-xs" : "text-sm")}
          >
            <Check className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
            <span>{t(`freeIncludes.${key}`, FREE_MODE ? FREE_MODE_FACTS : FREE_PLAN_FACTS)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
