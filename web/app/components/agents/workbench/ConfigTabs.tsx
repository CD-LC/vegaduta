"use client";

import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { useSearchParams } from "next/navigation";
import { SlidersHorizontal } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Tabs, TabsList, TabsTrigger } from "../../ui/tabs";
import { WORKBENCH_TAB_IDS, tabFromSection, type WorkbenchTab } from "../AgentWorkbench/behaviorModel";
import BehaviorLimitsTab from "../AgentWorkbench/BehaviorLimitsTab";
import { useWorkbench } from "./context";

const ORDER: WorkbenchTab[] = ["setup", "behavior"];

/**
 * The config column's two tabs: "Setup" (every existing section, unchanged) and "Behavior & limits" (what this
 * agent may do on a turn, with inheritance from the workspace). Both panels stay mounted once shown and are only
 * hidden, so edits in one are never lost by looking at the other. ?section=behavior (the playground's run settings
 * link) opens the Behavior tab and ?focus=<setting key> brings that setting into view.
 */
export default function ConfigTabs({
  children,
  onBehaviorDirtyChange,
}: {
  /** The Setup tab's content: the existing sections. */
  children: ReactNode;
  onBehaviorDirtyChange?: (dirty: boolean) => void;
}) {
  const t = useTranslations("agentBehavior");
  const { isDraft, selectedAgent, locked } = useWorkbench();
  const params = useSearchParams();
  const section = params?.get("section") ?? null;
  const focus = params?.get("focus") ?? null;
  const [tab, setTab] = useState<WorkbenchTab>(tabFromSection(section));
  // The Behavior tab loads its data on first view, not for everyone who never opens it.
  const [behaviorSeen, setBehaviorSeen] = useState(tabFromSection(section) === "behavior");
  const [dirty, setDirty] = useState(false);
  const tabRefs = useRef<Record<WorkbenchTab, HTMLButtonElement | null>>({ setup: null, behavior: null });

  const select = (next: WorkbenchTab) => {
    setTab(next);
    if (next === "behavior") setBehaviorSeen(true);
  };

  // A link to this page that arrives while it is already open (same route, new query) switches the tab too.
  useEffect(() => {
    if (tabFromSection(section) === "behavior") select("behavior");
  }, [section, focus]);

  const reportDirty = (next: boolean) => {
    setDirty(next);
    onBehaviorDirtyChange?.(next);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const i = ORDER.indexOf(tab);
    let next: WorkbenchTab | null = null;
    if (e.key === "ArrowRight") next = ORDER[(i + 1) % ORDER.length];
    else if (e.key === "ArrowLeft") next = ORDER[(i + ORDER.length - 1) % ORDER.length];
    else if (e.key === "Home") next = ORDER[0];
    else if (e.key === "End") next = ORDER[ORDER.length - 1];
    if (!next) return;
    e.preventDefault();
    select(next);
    tabRefs.current[next]?.focus();
  };

  const ids = WORKBENCH_TAB_IDS;
  return (
    <Tabs value={tab} onValueChange={(v) => select(v as WorkbenchTab)} className="min-w-0 gap-4">
      <TabsList aria-label={t("tabs.label")} onKeyDown={onKeyDown} className="h-10">
        <TabsTrigger
          value="setup"
          id={ids.setupTab}
          aria-controls={ids.setupPanel}
          tabIndex={tab === "setup" ? 0 : -1}
          ref={(el) => {
            tabRefs.current.setup = el;
          }}
        >
          {t("tabs.setup")}
        </TabsTrigger>
        <TabsTrigger
          value="behavior"
          id={ids.behaviorTab}
          aria-controls={ids.behaviorPanel}
          tabIndex={tab === "behavior" ? 0 : -1}
          ref={(el) => {
            tabRefs.current.behavior = el;
          }}
        >
          <SlidersHorizontal size={13} aria-hidden="true" />
          {t("tabs.behavior")}
          {dirty ? (
            <>
              <span aria-hidden="true" className="size-1.5 rounded-full bg-primary" data-testid="behavior-tab-dirty" />
              <span className="sr-only">{t("tabs.unsaved")}</span>
            </>
          ) : null}
        </TabsTrigger>
      </TabsList>

      <div id={ids.setupPanel} role="tabpanel" aria-labelledby={ids.setupTab} hidden={tab !== "setup"}>
        <div className="flex min-w-0 flex-col gap-4">{children}</div>
      </div>

      <div id={ids.behaviorPanel} role="tabpanel" aria-labelledby={ids.behaviorTab} hidden={tab !== "behavior"}>
        {behaviorSeen ? (
          isDraft || !selectedAgent ? (
            <p
              aria-disabled="true"
              data-testid="behavior-save-first"
              className="m-0 rounded-md border border-dashed border-border px-3 py-2 text-[12.5px] text-muted-foreground"
            >
              {t("saveFirst")}
            </p>
          ) : (
            <BehaviorLimitsTab
              key={selectedAgent.id}
              agent={selectedAgent}
              locked={!!locked}
              focusKey={focus}
              onDirtyChange={reportDirty}
            />
          )
        ) : null}
      </div>
    </Tabs>
  );
}
