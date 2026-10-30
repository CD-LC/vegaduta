"use client";

import { useState } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { cn } from "../../lib/utils";
import { TierPill, UnclassifiedPill } from "./ToolClassificationBadges";
import {
  AXIS_QUESTIONS,
  DETERMINISM_META,
  SENSITIVITY_META,
  TRUST_META,
  UNCLASSIFIED_META,
  type TierMeta,
} from "./toolClassification";
import { useTranslations } from "../../lib/i18n";

/**
 * The legend for the tool-classification table: every tier of every axis, with
 * the one plain sentence that says what it means for the person reading.
 *
 * WHY this is a first-class panel and not a tooltip: the three axes are not
 * self-explanatory from their labels, and the consequence of misreading one is
 * that someone leaves an agent holding a tool that can email their customer
 * list to a stranger. A tooltip is for a detail you can afford to miss. This
 * is the key to the whole screen, so it is on the screen.
 *
 * Order within each axis is lowest-consequence first, so the columns read as a
 * ramp. The blocked state sits at the bottom, full width, because it is not a
 * fourth axis — it is what the registry does when any axis is missing.
 */

function AxisColumn({ heading, question, tiers }: { heading: string; question: string; tiers: TierMeta[] }) {
  return (
    <div className="space-y-3">
      <div className="space-y-0.5">
        <h4 className="font-mono text-xs font-semibold uppercase tracking-wider text-muted-foreground">{heading}</h4>
        <p className="text-sm font-medium text-foreground">{question}</p>
      </div>
      <ul className="space-y-3">
        {tiers.map((tier) => (
          <li key={tier.code} className="space-y-1">
            <TierPill meta={tier} />
            <p className="text-xs leading-relaxed text-muted-foreground">{tier.meaning}</p>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function ToolClassificationLegend({ className }: { className?: string }) {
    const tHC = useTranslations("hcComponentsToolsToolClassificationLegend");
  // Open by default: a non-expert landing here for the first time needs it,
  // and an expert collapsing it once is cheaper than an admin never finding
  // it. Collapse state is intentionally not persisted — this panel is
  // reference material, not a preference.
  const [open, setOpen] = useState(true);

  return (
    <Card className={cn("gap-0", className)}>
      <CardContent className="space-y-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="space-y-0.5">
            <h3 className="text-sm font-semibold text-foreground">{tHC("s4")}</h3>
            <p className="text-sm text-muted-foreground">
              {tHC("s3")}
            </p>
          </div>
          <Button variant="ghost" size="sm" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
            {open ? <ChevronUp size={13} aria-hidden="true" /> : <ChevronDown size={13} aria-hidden="true" />}
            {open ? "Hide" : "Show"}
          </Button>
        </div>

        {open && (
          <>
            <div className="grid gap-6 lg:grid-cols-3">
              <AxisColumn
                heading="Trust"
                question={AXIS_QUESTIONS.trust}
                tiers={[TRUST_META.TRUSTED_INTERNAL, TRUST_META.TENANT_SCOPED, TRUST_META.UNTRUSTED_EXTERNAL]}
              />
              <AxisColumn
                heading="Sensitivity"
                question={AXIS_QUESTIONS.sensitivity}
                tiers={[
                  SENSITIVITY_META.READ_PUBLIC,
                  SENSITIVITY_META.READ_TENANT,
                  SENSITIVITY_META.WRITE_TENANT,
                  SENSITIVITY_META.CREDENTIALED,
                  SENSITIVITY_META.EGRESS,
                ]}
              />
              {/* Lowest-consequence first, like the two columns above:
               * PURE -> SIDE_EFFECT -> DURABLE is the real order of this axis
               * per core/agents' ToolDeterminism (a SIDE_EFFECT re-call is
               * merely wasteful; a DURABLE re-call is a second real-world
               * action). */}
              <AxisColumn
                heading="Retry safety"
                question={AXIS_QUESTIONS.determinism}
                tiers={[DETERMINISM_META.PURE, DETERMINISM_META.SIDE_EFFECT, DETERMINISM_META.DURABLE]}
              />
            </div>

            {/* The fail-closed rule, stated once, plainly, next to the mark
             * that represents it - so the blocked pill in the table above is
             * never mistaken for "not filled in yet, no big deal". */}
            <div className="space-y-2 rounded-lg border border-destructive/30 bg-destructive/5 p-4">
              <div className="flex flex-wrap items-center gap-2">
                <UnclassifiedPill />
                <span className="text-sm font-medium text-foreground">{tHC("s2")}</span>
              </div>
              <p className="text-xs leading-relaxed text-muted-foreground">{UNCLASSIFIED_META.meaning}</p>
              <p className="text-xs leading-relaxed text-muted-foreground">
                {tHC("s1")}
              </p>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
