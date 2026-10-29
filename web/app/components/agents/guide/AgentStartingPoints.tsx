"use client";

import { useState } from "react";
import Link from "next/link";
import { ExternalLink, FileSpreadsheet, LayoutTemplate, PenLine, PlayCircle } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import { STARTER_TEMPLATES, type StarterTemplate } from "../starterTemplates";
import { templateNameKey } from "../../agentTemplates";
import { QUICK_START_TEMPLATE_KEYS } from "../templateAudience";

/**
 * The empty state for someone with zero agents (2026-09-19).
 *
 * What a brand-new tenant used to see on /agents: a blank name box, a prompt
 * pre-filled with "You are a concise, helpful assistant." and a dozen
 * collapsed sections. The one-click starter path existed, but behind a link
 * inside the agent-switcher dropdown and a lock-badged "Templates" button -
 * nobody who was lost found it. This puts the three real ways to begin in
 * front of them, in order of effort:
 *
 *   1. Start from a template - the 16 starter personas (starterTemplates.ts),
 *      open on every plan. Picking one fills the name and instructions in
 *      place via the workbench's own applyStarterTemplate; nothing is saved
 *      until they press Save draft.
 *   2. Describe it in a sentence - jumps to the checklist's describe box.
 *   3. Open the sandbox - the existing no-signup /try page, a separate
 *      practice space. Opens in a new tab so the draft here is not lost.
 *
 * Rendered by CreateAgentChecklist only while the draft is pristine AND the
 * agent list it was given is empty; it disappears the moment they type or
 * pick. An empty list can also mean the list failed to load, so nothing here
 * says how many agents the person has.
 *
 * Starter labels reuse the sandbox's existing `try` message keys
 * (starter.labelKey) - the same strings /agents/templates already renders, so
 * no new catalogue key is introduced.
 */

const FIRST_ROW = 6;

export default function AgentStartingPoints({
  onPickStarter,
  onDescribe,
  onPickTemplate,
}: {
  onPickStarter: (starter: StarterTemplate) => void;
  onDescribe: () => void;
  /** Fills the draft with a console-gallery template by key. Absent = the data-analysis card is not shown. */
  onPickTemplate?: (templateKey: string) => void;
}) {
    const tHC = useTranslations("hcComponentsAgentsGuideAgentStartingPoints");
  const tTry = useTranslations("try");
  const tTemplates = useTranslations("agentTemplates");
  const [showAll, setShowAll] = useState(false);
  const starters = showAll ? STARTER_TEMPLATES : STARTER_TEMPLATES.slice(0, FIRST_ROW);

  return (
    <div className="mb-4 rounded-lg border border-border bg-background p-4" data-testid="agent-starting-points">
      {/* No "you have no agents" claim: an empty list can also be a list that
          failed to load (see CreateAgentChecklist's header). */}
      <h3 className="m-0 text-sm font-semibold text-foreground">{tHC("hcComponentsAgentsGuideAgentStartingPoints.s15")}</h3>
      <p className="m-0 mt-1 text-xs leading-relaxed text-muted-foreground">
        {tHC("hcComponentsAgentsGuideAgentStartingPoints.s14")}
      </p>

      {/* "Start in 1 minute" (wave 3): one-click drafts that need no documents,
          connector or channel - just a conversation. Shown only when the
          workbench can apply a console template (same condition as the data
          analyst card below). The five keys are held to "no setup needed" by
          templateAudience.test.ts, and stay open on every plan
          (OPEN_CONSOLE_TEMPLATE_KEYS). */}
      {onPickTemplate && (
        <div className="mt-3" data-testid="starting-point-quick-start">
          <div className="text-xs font-semibold text-foreground">{tHC("hcComponentsAgentsGuideAgentStartingPoints.s13")}</div>
          <p className="m-0 mt-0.5 text-xs leading-relaxed text-muted-foreground">
            {tHC("hcComponentsAgentsGuideAgentStartingPoints.s12")}
          </p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {QUICK_START_TEMPLATE_KEYS.map((key) => (
              <button
                key={key}
                type="button"
                onClick={() => onPickTemplate(key)}
                className="rounded-full border border-primary/30 bg-primary/5 px-2.5 py-1 text-xs font-medium text-foreground transition-colors hover:border-primary/60 hover:bg-primary/10"
              >
                {tTemplates(templateNameKey(key))}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* auto-fit, not viewport breakpoints: this sits in the workbench's left
          column beside the playground pane, so the viewport says nothing
          about the room available. Cards never go under 13rem; they wrap. */}
      <div className="mt-3 grid grid-cols-[repeat(auto-fit,minmax(min(100%,13rem),1fr))] gap-3">
        <div className="rounded-lg border border-border bg-card p-3">
          <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <LayoutTemplate size={15} className="text-primary" aria-hidden="true" />
            {tHC("hcComponentsAgentsGuideAgentStartingPoints.s11")}
          </div>
          <p className="m-0 mt-1 text-xs leading-relaxed text-muted-foreground">
            {tHC("hcComponentsAgentsGuideAgentStartingPoints.s10")}
          </p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {starters.map((starter) => (
              <button
                key={starter.key}
                type="button"
                onClick={() => onPickStarter(starter)}
                className="rounded-full border border-border bg-secondary/40 px-2.5 py-1 text-xs text-foreground transition-colors hover:border-primary/50 hover:bg-primary/10"
              >
                {tTry(starter.labelKey)}
              </button>
            ))}
            {STARTER_TEMPLATES.length > FIRST_ROW && (
              <button
                type="button"
                onClick={() => setShowAll((v) => !v)}
                aria-expanded={showAll}
                className="rounded-full border border-dashed border-border bg-transparent px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
              >
                {showAll ? "Show fewer" : `Show all ${STARTER_TEMPLATES.length}`}
              </button>
            )}
          </div>
        </div>

        <div className="flex flex-col rounded-lg border border-border bg-card p-3">
          <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <PenLine size={15} className="text-primary" aria-hidden="true" />
            {tHC("hcComponentsAgentsGuideAgentStartingPoints.s9")}
          </div>
          <p className="m-0 mt-1 flex-1 text-xs leading-relaxed text-muted-foreground">
            {tHC("hcComponentsAgentsGuideAgentStartingPoints.s8")}
          </p>
          <div className="mt-2">
            <Button type="button" size="sm" onClick={onDescribe}>
              {tHC("hcComponentsAgentsGuideAgentStartingPoints.s7")}
            </Button>
          </div>
        </div>

        {/* Data analysis starting point (D3). A button, not a ?template= link:
            the workbench fills this very draft with the "data-analyst"
            template directly, so a second click after "New agent" works too
            (a same-URL link would not re-fire). Nothing is saved until Save
            draft, at which point the workbench switches Code execution on and
            reports honestly what is missing.
            The copy promises only what a default deployment does: numbers
            worked out by running code. Attaching CSV/Excel files and getting
            charts or workbooks back needs core's default-OFF data-analysis
            option, and this card shows only while the agent list is empty,
            so there is no agent here to tell us whether that option is on. */}
        {onPickTemplate && (
          <div
            className="flex flex-col rounded-lg border border-border bg-card p-3"
            data-testid="starting-point-data-analyst"
          >
            <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
              <FileSpreadsheet size={15} className="text-primary" aria-hidden="true" />
              {tHC("hcComponentsAgentsGuideAgentStartingPoints.s6")}
            </div>
            <p className="m-0 mt-1 flex-1 text-xs leading-relaxed text-muted-foreground">
              {tHC("hcComponentsAgentsGuideAgentStartingPoints.s5")}
            </p>
            <div className="mt-2">
              <Button type="button" variant="outline" size="sm" onClick={() => onPickTemplate("data-analyst")}>
                {tHC("hcComponentsAgentsGuideAgentStartingPoints.s4")}
              </Button>
            </div>
          </div>
        )}

        <div className="flex flex-col rounded-lg border border-border bg-card p-3">
          <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <PlayCircle size={15} className="text-primary" aria-hidden="true" />
            {tHC("hcComponentsAgentsGuideAgentStartingPoints.s3")}
          </div>
          <p className="m-0 mt-1 flex-1 text-xs leading-relaxed text-muted-foreground">
            {tHC("hcComponentsAgentsGuideAgentStartingPoints.s2")}
          </p>
          <div className="mt-2">
            {/* New tab on purpose: leaving /agents would discard the draft. */}
            <Button asChild variant="outline" size="sm">
              <Link href="/try" target="_blank" rel="noopener noreferrer">
                {tHC("hcComponentsAgentsGuideAgentStartingPoints.s1")}
                <ExternalLink size={12} aria-hidden="true" />
              </Link>
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
