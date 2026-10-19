"use client";

import { ChevronDown, Copy, LayoutTemplate, Lock, Plus, Rocket, Trash2 } from "lucide-react";
import Link from "next/link";
import CoachButton from "../../brief/CoachDrawer";
import ShareAsTemplateButton from "../../templates/ShareAsTemplateButton";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "../../ui/alert-dialog";
import { Badge } from "../../ui/badge";
import { Button, buttonVariants } from "../../ui/button";
import { Card } from "../../ui/card";
import { AgentMark, AgentStateDot } from "../agentIdentity";
import AgentsGuide from "../AgentsGuide";
import AgentSwitcherList from "./AgentSwitcherList";
import { useWorkbench } from "./context";

export default function WorkbenchToolbar() {
  const {
    switcherTriggerRef,
    setSwitcherOpen,
    switcherOpen,
    isDraft,
    identityAgent,
    headName,
    headState,
    mutating,
    t,
    headCategory,
    startDraft,
    busy,
    agents,
    tTry,
    selectedAgentId,
    draftRequestIdRef,
    onSelect,
    agentLimit,
    isFreePlan,
    handleCreate,
    handleDuplicateAgent,
    deleteDuplicateBusy,
    selectedAgent,
    handleDeleteAgent,
  } = useWorkbench();
  return (
    <>
      {/* Screen-specific action bar - agent switcher, tier count, Templates,
       * Save/Create, Deploy. Restyled from the bespoke .ax-agent chrome to a
       * token-driven toolbar Card; all handlers/state preserved. */}
      <Card className="flex flex-row flex-wrap items-center gap-3 gap-y-2 p-3">
        {/* Agent switcher. Was a mono breadcrumb ending in the literal word
            "live" in muted grey — no identity, no scannable status. Now the
            trigger carries the agent's own mark (category hue + icon +
            deterministic sigil) and a real state pill, and the dropdown is a
            list of identity rows rather than bare names. */}
        <div className="relative">
          <button
            type="button"
            ref={switcherTriggerRef}
            onClick={() => setSwitcherOpen((v) => !v)}
            aria-haspopup="listbox"
            aria-expanded={switcherOpen}
            // Product-tour target (TourProvider's data-tour note): when no
            // draft is open, "create" starts here - this trigger's first row
            // is "New agent". While a draft IS open the Save-draft button
            // below carries the marker instead, so the spotlight always sits
            // on the control the step's copy tells the person to click.
            data-tour={isDraft ? undefined : "create-agent"}
            className="flex max-w-full items-center gap-2.5 rounded-lg px-1.5 py-1 text-left transition-colors hover:bg-accent/60"
          >
            <AgentMark agent={identityAgent} size="md" />
            <span className="flex min-w-0 flex-col">
              <span className="flex items-center gap-1.5">
                <span className="truncate text-sm font-semibold text-foreground">{headName}</span>
                <ChevronDown size={13} className="shrink-0 text-muted-foreground" />
              </span>
              <span className="flex items-center gap-1.5 text-[0.6875rem] text-muted-foreground">
                <AgentStateDot state={headState} />
                {isDraft && !mutating ? t("draftStatus") : mutating ? headState.label : t("liveStatus")}
                <span className="text-muted-foreground">·</span>
                <span className="truncate">{headCategory.label}</span>
              </span>
            </span>
          </button>
          {switcherOpen && (
            <ul
              role="listbox"
              // Safe partial keyboard fix (batch scope): Escape closes the
              // popup and returns focus to the trigger. Full arrow-key
              // roving-focus listbox semantics are a larger follow-up change,
              // deliberately out of scope here.
              onKeyDown={(e) => {
                if (e.key === "Escape") {
                  e.preventDefault();
                  setSwitcherOpen(false);
                  switcherTriggerRef.current?.focus();
                }
              }}
              className="elev-raised absolute left-0 top-full z-[1000] mt-2 max-h-80 w-[19rem] overflow-y-auto rounded-xl p-1.5 shadow-lg"
            >
              <li>
                <button
                  type="button"
                  className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-sm text-foreground transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-50"
                  onClick={startDraft}
                  disabled={busy}
                >
                  <span className="cat-chip cat-chip-sm" style={{ ["--cat" as any]: "var(--cat-assistant)" } as any}>
                    <Plus size={12} />
                  </span>
                  {t("newAgent")}
                </button>
              </li>
              {agents.length === 0 && (
                <li className="px-2.5 py-3 text-xs text-muted-foreground">
                  {t("draftHint")}
                  {/* The other first-agent path - a one-click starter (open
                      on every plan) instead of a blank draft. */}
                  <Link
                    href="/agents/templates"
                    className="mt-1.5 flex items-center gap-1.5 font-medium text-primary underline-offset-4 hover:underline"
                    onClick={() => setSwitcherOpen(false)}
                  >
                    <LayoutTemplate size={12} />
                    {tTry("starter_switcherLink")}
                  </Link>
                </li>
              )}
              <AgentSwitcherList
                agents={agents}
                selectedAgentId={selectedAgentId}
                disabled={busy}
                onPick={(id) => {
                  draftRequestIdRef.current += 1;
                  onSelect(id);
                  setSwitcherOpen(false);
                }}
              />
            </ul>
          )}
        </div>

        <div className="flex-1" />

        <Badge variant="secondary">
          {t("agentsUsedCount", { count: agents.length, limit: Number.isFinite(agentLimit) ? agentLimit : "∞" })}
        </Badge>
        <AgentsGuide />
        {/* Templates: the FULL gallery is a paid capability (direct feedback:
            "free users cant select the template, it should be disabled"),
            but this button used to be lock-badged for FREE outright - so a
            brand-new free tenant's only path was a blank draft. It now
            always links through; /agents/templates itself keeps the paid
            gallery lock-badged with the Upgrade nudge for FREE and shows the
            16-persona starter allow-list (open on every plan) above it - see
            starterTemplates.ts. The lock icon stays as the FREE-tier hint. */}
        <Button asChild variant="outline" size="sm">
          <Link href="/agents/templates">
            {isFreePlan ? <Lock size={13} /> : <LayoutTemplate size={14} />}
            {t("templatesLabel")}
          </Link>
        </Button>
        {isDraft ? (
          // data-tour: the product tour's step 1 target while a draft is
          // open - the last click of "name it, then Save draft".
          <Button onClick={handleCreate} disabled={busy} data-tour="create-agent">
            {busy ? t("creating") : t("saveDraft")}
          </Button>
        ) : (
          <>
            {/* Duplicate is available on every plan and at any lock state - it
             * is a FREE tenant's documented way around the 24h edit-lock, so
             * it must never grow a plan gate. (Delete is the opposite: see the
             * paid-plan branch below.) */}
            <Button variant="ghost" size="sm" onClick={handleDuplicateAgent} disabled={deleteDuplicateBusy}>
              <Copy size={14} />
              {t("duplicate")}
            </Button>
            {selectedAgent && (
              <ShareAsTemplateButton
                sourceId={selectedAgent.id}
                sourceName={selectedAgent.name}
                sourceKind="AGENT"
                variant="ghost"
                size="sm"
              />
            )}
            {selectedAgent && <CoachButton entityId={selectedAgent.id} />}
            {/* Deleting an agent is a paid-plan capability — a FREE tenant sees
                a locked Delete that reveals the Upgrade affordance on hover/focus
                (shared Button `upgradeLocked`) instead of an armed confirm dialog. */}
            {isFreePlan ? (
              <Button
                variant="ghost"
                size="sm"
                className="text-destructive hover:text-destructive"
                upgradeLocked
                upgradeReason={t("deleteAgentFreePlanTooltip")}
                upgradeLabel={t("freeTierUpgradeButton")}
              >
                <Trash2 size={14} />
                {t("delete")}
              </Button>
            ) : (
              <AlertDialog>
                <AlertDialogTrigger asChild>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="text-destructive hover:text-destructive"
                    disabled={deleteDuplicateBusy}
                  >
                    <Trash2 size={14} />
                    {t("delete")}
                  </Button>
                </AlertDialogTrigger>
                <AlertDialogContent>
                  <AlertDialogHeader>
                    <AlertDialogTitle>{t("deleteDialogTitle")}</AlertDialogTitle>
                    <AlertDialogDescription>
                      {t("deleteConfirm", { name: selectedAgent?.name ?? "" })}
                    </AlertDialogDescription>
                  </AlertDialogHeader>
                  <AlertDialogFooter>
                    <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
                    <AlertDialogAction
                      className={buttonVariants({ variant: "destructive" })}
                      onClick={handleDeleteAgent}
                      disabled={deleteDuplicateBusy}
                    >
                      <Trash2 size={14} />
                      {deleteDuplicateBusy ? t("deleting") : t("delete")}
                    </AlertDialogAction>
                  </AlertDialogFooter>
                </AlertDialogContent>
              </AlertDialog>
            )}
            <Button asChild>
              <Link href="/admin/channels">
                <Rocket size={14} />
                {t("deployToChannel")}
              </Link>
            </Button>
          </>
        )}
      </Card>
    </>
  );
}
