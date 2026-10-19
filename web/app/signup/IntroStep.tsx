"use client";

// Signup step 0: what the workspace includes, before any form.

import { useState } from "react";
import { Check, CheckCircle2, ChevronDown, Lock, Zap } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import SocialLoginRow from "../components/signup/SocialLoginRow";
import { Button } from "../components/ui/button";
import FreePlanIncludes from "../components/FreePlanIncludes";
import { FREE_MODE } from "../lib/freeMode";
import { VESSEL } from "./signupModel";

// Intro step, simplified (auth-simplify pass, 2026-09-24). The step title and
// one-sentence value prop now live in the page strip (AuthBrandPanel); the
// card carries three short check lines, the primary CTA, the realm-driven
// social row, and the full out-of-the-box list + the 24h edit-window note +
// the three "under the hood" proofs folded behind a "What's included"
// disclosure - every line of copy is still here, none of it is on screen
// before the CTA any more. Maintainer feedback (prod screenshot at 1800px): "so much gap
// in register page, research and simplify".
export function IntroStep({ onContinue }: { onContinue: () => void }) {
  const t = useTranslations("signup");
  const [open, setOpen] = useState(false);

  // The three lines that used to be the brand panel's checklist. Free mode
  // (lib/freeMode.ts): no tiers exist to describe, so the {agents}-counted
  // Free-tier line swaps for the unlocked variant.
  const points = [
    t("checklistItem1"),
    FREE_MODE ? t("checklistItem2FreeMode") : t("checklistItem2", { agents: VESSEL.agents }),
    t("checklistItem3"),
  ];

  const proofs = [
    { Icon: CheckCircle2, title: t("proof1Title"), body: t("proof1Body") },
    { Icon: Zap, title: t("proof2Title"), body: t("proof2Body") },
    { Icon: Lock, title: t("proof3Title"), body: t("proof3Body") },
  ];

  return (
    <div className="vd-auth__stack">
      <ul className="vd-auth__points" aria-label={t("stepLabel")}>
        {points.map((item) => (
          <li key={item}>
            <Check className="size-4 shrink-0" aria-hidden="true" />
            <span>{item}</span>
          </li>
        ))}
      </ul>

      <Button size="lg" className="w-full" onClick={onContinue} autoFocus>
        {t("cta")}
      </Button>

      <p className="vd-auth__summary">
        {t(
          "summaryPrefix",
          FREE_MODE ? { seats: "Unlimited", agents: "Unlimited" } : { seats: VESSEL.seats, agents: VESSEL.agents }
        )}{" "}
        <span className="vd-auth__summary-hl">{t("summaryHighlight")}</span>{" "}
        {t(FREE_MODE ? "summarySuffixFreeMode" : "summarySuffix")}
      </p>

      <SocialLoginRow />

      {/* Native <details>: no JS needed to open, keyboard/AT get the
          expand/collapse for free. `open` is mirrored into state only so the
          chevron can turn. */}
      <details
        className="vd-auth__more"
        open={open}
        onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}
      >
        <summary>
          <span>{t("stepLabel")}</span>
          <ChevronDown className="size-4" aria-hidden="true" />
        </summary>
        <div className="vd-auth__more-body">
          {/* Numbers come from FREE_PLAN_FACTS (the same constants the
              landing pricing row and the post-login first-run card use) so
              this list can't drift from what the plan actually enforces. */}
          <FreePlanIncludes heading={false} compact />

          {/* The one Free-plan rule that used to be a post-signup surprise:
              agents lock to read-only 24h after creation (duplicate for a
              fresh editable copy). Same fact the landing FAQ states - said
              here so nobody meets it for the first time inside the workbench. */}
          <p className="vd-auth__note">{t("editWindowNote")}</p>

          <p className="vd-auth__label">{t("proofsLabel")}</p>
          <ul className="vd-auth__proofs">
            {proofs.map(({ Icon, title, body }) => (
              <li key={title}>
                <Icon className="size-4 shrink-0" aria-hidden="true" />
                <span>
                  <strong>{title}</strong> {body}
                </span>
              </li>
            ))}
          </ul>
        </div>
      </details>
    </div>
  );
}
