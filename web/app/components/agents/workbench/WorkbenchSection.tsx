"use client";

import type { ComponentProps, ReactNode } from "react";
import { CollapsibleSection } from "../AgentWorkbench/CollapsibleSection";
import { useTranslations } from "../../../lib/i18n";

/**
 * Screen 3 - Agents + Playground (design_handoff_axon/design_files/
 * Agents.dc.html): a build-and-test screen for ONE agent at a time - left
 * config panel (identity, persona/system prompt, model & generation,
 * knowledge collections, tools/MCP), right 492px live Playground. Direct
 * user request: "exact same pixel to pixel replacement as per template
 * design."
 *
 * Honesty note (this project's standing "never fabricate data" rule):
 * AgentController exposes PATCH endpoints for an existing agent's
 * generation params, tool access, knowledge access, experience mode, and
 * (Unlimited-plan capability) provider/model - but NOT for its name or
 * persona/system prompt (see api.js - there is no PUT/PATCH for those two
 * fields after creation). The mockup's single "Save draft" button implies
 * the whole form is one editable unit; this build is honest about the real
 * seam instead: for a brand-new, not-yet-created agent, every field is
 * editable and "Save draft" creates it in one call (createAgent). Once an
 * agent exists, its identity/persona stay read-only display (with a small
 * note explaining why), its model gets its own inline "Change model"
 * editor (provider-scoped catalog dropdown, same pattern as AgentsPanel.js's
 * pre-AXON editor - see the `locked`/modelEditing state below), and
 * generation/tools/knowledge/mode stay live-editable through their own real
 * endpoints, applying immediately per toggle. Delete and Duplicate live in
 * the top action bar - Duplicate is any plan's way around a locked (24h+)
 * FREE-tier agent; Delete ignores that 24h edit-lock but is a PAID-plan
 * capability (AgentService#delete rejects FREE outright, WB-001), so FREE
 * gets an upgrade-locked button, never an armed confirm dialog.
 */
// One-line "what is set" echo in a collapsed section's header (QA-05). Lives
// in the CollapsibleSection `actions` slot so it never toggles the section;
// console.css hides it while the section is open, and it stays hidden
// below the sm breakpoint so the label + chevron keep the row on a phone.
function SectionSummary({ children }: { children: ReactNode }) {
  if (!children) return null;
  return (
    <span
      className="agent-section-summary hidden truncate font-mono text-[11px] text-muted-foreground sm:inline-block sm:max-w-[14rem] lg:max-w-[22rem]"
      title={typeof children === "string" ? children : undefined}
    >
      {children}
    </span>
  );
}

// Placeholder for a saved-agent-only section on an unsaved draft: the header
// always renders (nothing is silently omitted), the body says why it is inert.
function SavedOnlyStub({ icon, label, cat, what }: { icon: ReactNode; label: string; cat: string; what: string }) {
    const tHC = useTranslations("hcComponentsAgentsWorkbenchWorkbenchSection");
  return (
    <CollapsibleSection icon={icon} label={label} cat={cat} actions={<SectionSummary>{tHC("hcComponentsAgentsWorkbenchWorkbenchSection.s2")}</SectionSummary>}>
      <p
        aria-disabled="true"
        className="m-0 rounded-md border border-dashed border-border px-3 py-2 text-[12.5px] text-muted-foreground opacity-80"
      >
        {tHC("hcComponentsAgentsWorkbenchWorkbenchSection.s1")} {what}
      </p>
    </CollapsibleSection>
  );
}

type SectionProps = ComponentProps<typeof CollapsibleSection>;

/**
 * Compound config section: <WorkbenchSection> is the collapsible card,
 * <WorkbenchSection.Summary> the header echo, <WorkbenchSection.SavedOnly> the
 * placeholder for saved-agent-only sections on an unsaved draft.
 */
function WorkbenchSectionRoot(props: SectionProps) {
  return <CollapsibleSection {...props} />;
}
export const WorkbenchSection = Object.assign(WorkbenchSectionRoot, {
  Summary: SectionSummary,
  SavedOnly: SavedOnlyStub,
});
