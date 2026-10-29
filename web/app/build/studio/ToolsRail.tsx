"use client";

import { Code2, Link2, MousePointerClick, Palette, Share2, Database, ListChecks, Users } from "lucide-react";
import type { ProjectSession } from "./useProjectSession";
import { type SidePanel } from "./sessionTypes";

import FilesPane from "./FilesPane";
import DeployPane from "./DeployPane";
import { useTranslations } from "../../lib/i18n";

/** Right column: the tool tab strip plus the active panel (files-side panels, then deploy-side panels). */
export default function ToolsRail({ s }: { s: ProjectSession }) {
    const tHC = useTranslations("hcBuildStudioToolsRail");
  const { panel, setPanel } = s;
  return (
    <div className="order-3 flex min-h-0 max-h-[75vh] flex-col gap-3 lg:sticky lg:top-4 lg:max-h-[calc(100vh-6rem)]">
      <div role="tablist" aria-label={tHC("hcBuildStudioToolsRail.s1")} className="grid shrink-0 grid-cols-4 gap-1 rounded-xl bg-secondary p-1">
        {(
          [
            ["design", Palette, "Design"],
            ["element", MousePointerClick, "Element"],
            ["connect", Link2, "Connect"],
            ["share", Share2, "Share"],
            ["code", Code2, "Code"],
            ["check", ListChecks, "Check"],
            ["data", Database, "Data"],
            ["team", Users, "Team"],
          ] as const
        ).map(([k, Icon, label]) => (
          <button
            key={k}
            type="button"
            role="tab"
            id={`studio-tab-${k}`}
            aria-selected={panel === k}
            aria-controls="studio-tabpanel"
            tabIndex={panel === k ? 0 : -1}
            onClick={() => setPanel(k)}
            onKeyDown={(e) => {
              // Arrow keys move between tabs (roving tabindex), like any tablist.
              if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
              const order: SidePanel[] = ["design", "element", "connect", "share", "code", "check", "data", "team"];
              const next = order[(order.indexOf(k) + (e.key === "ArrowRight" ? 1 : order.length - 1)) % order.length];
              setPanel(next);
              e.preventDefault();
              requestAnimationFrame(() => document.getElementById(`studio-tab-${next}`)?.focus());
            }}
            className={`flex flex-col items-center gap-0.5 rounded-lg py-1.5 text-[11px] font-medium transition ${
              panel === k ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
            }`}
          >
            <Icon size={14} /> {label}
          </button>
        ))}
      </div>
      <div
        role="tabpanel"
        id="studio-tabpanel"
        aria-labelledby={`studio-tab-${panel}`}
        className="min-h-0 flex-1 overflow-y-auto overscroll-contain rounded-2xl border border-border bg-card p-4"
      >
      <FilesPane s={s} />
      <DeployPane s={s} />
    </div>
  </div>
  );
}
