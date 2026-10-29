"use client";

import { useEffect, useId, useRef, useState, type MutableRefObject } from "react";
import { ArrowLeft, FileSearch, X } from "lucide-react";
import type { SettingsClient } from "../../../../lib/api/settings";
import { useTranslations } from "../../../../lib/i18n";
import { Button } from "../../../ui/button";
import type { PlaygroundAgent } from "../types";
import { LimitsSection } from "./LimitsSection";
import { ModelSection } from "./ModelSection";
import { PromptPreviewView } from "./PromptPreviewView";
import { ToolsSection } from "./ToolsSection";

/**
 * The playground's "Run settings" drawer: everything that decides how the next message runs, in one place and
 * in plain language - the model and its depth and sampling (with a warning for any setting the provider drops),
 * the tools on or off for this run only (never saved to the agent), the limits a turn runs under and where each comes from - plus "Preview prompt", which
 * shows the final system prompt and tool list by block with the source and version of each.
 *
 * It slides over the right edge of the playground card (the parent card is `relative`), so the transcript behind
 * it stays where it is. Not modal: the conversation remains readable and the Escape key or the close button
 * returns focus to the button that opened it. Each section loads only once the drawer is open, so a closed
 * playground makes no extra requests.
 */
export function RunSettingsDrawer({
  agent,
  sessionId,
  onClose,
  returnFocusRef,
  runTools,
  onRunToolsChange,
  settingsClient,
}: {
  agent: PlaygroundAgent;
  /** Test seam: the settings-catalog client the limits are read through. Defaults to the authenticated one. */
  settingsClient?: SettingsClient;
  sessionId: string | null;
  onClose: () => void;
  /** The tool selection of this playground session's runs (`null` = the agent's own tools); the owner keeps it. */
  runTools: string[] | null;
  /** The tool switches changed the selection. Nothing is saved: the owner only keeps it for the next messages. */
  onRunToolsChange: (names: string[] | null) => void;
  /** The button that opened the drawer; focus goes back to it on close. */
  returnFocusRef?: MutableRefObject<HTMLElement | null>;
}) {
  const t = useTranslations("agentPlayground");
  const titleId = useId();
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  const [view, setView] = useState<"settings" | "prompt">("settings");

  // Focus lands on the heading when the drawer opens and when its view changes (a screen reader then announces
  // where it is), and returns to the opener when the drawer goes away.
  useEffect(() => {
    headingRef.current?.focus();
  }, [view]);
  useEffect(() => {
    const opener = returnFocusRef?.current ?? null;
    return () => {
      opener?.focus();
    };
  }, [returnFocusRef]);

  // Escape steps back (preview -> settings) and then closes. Listened for on the panel itself, so it only
  // fires while focus is inside it and never swallows Escape elsewhere on the page.
  const panelRef = useRef<HTMLDivElement | null>(null);
  const viewRef = useRef(view);
  viewRef.current = view;
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  useEffect(() => {
    const panel = panelRef.current;
    if (!panel) return undefined;
    const onKeyDown = (e: KeyboardEvent) => {
      // Something inside already handled this Escape (a confirmation dialog closing itself): do not also close
      // the drawer under it.
      if (e.key !== "Escape" || e.defaultPrevented) return;
      e.stopPropagation();
      if (viewRef.current === "prompt") setView("settings");
      else onCloseRef.current();
    };
    panel.addEventListener("keydown", onKeyDown);
    return () => panel.removeEventListener("keydown", onKeyDown);
  }, []);

  return (
    <div
      ref={panelRef}
      role="dialog"
      aria-modal="false"
      aria-labelledby={titleId}
      data-testid="run-settings-drawer"
      className="absolute inset-y-0 end-0 z-20 flex w-full max-w-md flex-col border-s border-border bg-card text-card-foreground shadow-xl"
    >
      <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-3">
        <div className="flex min-w-0 items-center gap-1.5">
          {view === "prompt" && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => setView("settings")}
              aria-label={t("rsBack")}
              data-testid="rs-back"
            >
              <ArrowLeft size={14} aria-hidden="true" />
            </Button>
          )}
          <h2
            id={titleId}
            ref={headingRef}
            tabIndex={-1}
            className="m-0 truncate text-sm font-semibold text-foreground outline-none"
          >
            {view === "prompt" ? t("rsPreviewTitle") : t("rsTitle")}
          </h2>
        </div>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={onClose}
          aria-label={t("rsClose")}
          data-testid="rs-close"
        >
          <X size={14} aria-hidden="true" />
        </Button>
      </div>
      <div className="min-h-0 flex-1 space-y-5 overflow-y-auto px-4 py-4">
        {view === "settings" ? (
          <>
            <p className="m-0 text-xs text-muted-foreground">{t("rsIntro", { name: agent.name })}</p>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setView("prompt")}
              data-testid="rs-preview-button"
            >
              <FileSearch size={14} aria-hidden="true" />
              {t("rsPreviewButton")}
            </Button>
            <ModelSection agent={agent} />
            <ToolsSection agent={agent} runTools={runTools} onRunToolsChange={onRunToolsChange} />
            <LimitsSection agent={agent} settingsClient={settingsClient} />
          </>
        ) : (
          <>
            <p className="m-0 text-xs text-muted-foreground">{t("rsPreviewIntro")}</p>
            <PromptPreviewView agent={agent} sessionId={sessionId} />
          </>
        )}
      </div>
    </div>
  );
}
