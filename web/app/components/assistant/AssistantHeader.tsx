"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { Brain, Globe, Monitor, Sparkles, Square, Volume2, VolumeX } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import ChatExportMenu from "../ChatExportMenu";
import AssistantInfoCard from "./AssistantInfoCard";
import { stopSpeaking } from "./speakText";
import type { AssistantPrefs } from "./assistantPrefs";

/** A header toggle that is on: a quiet brand tint (not a glow), so on and off are told apart without shouting. */
const TOGGLE_ON = "bg-primary/12 text-primary hover:bg-primary/18 hover:text-primary";

/**
 * The conversation's own header (lifted out of AssistantPane): who answers, then a short row of quiet icon
 * toggles. System status lives in the app bar's quiet indicator, not here, so the conversation stays the
 * loudest thing on the page.
 */
export default function AssistantHeader({
  agent,
  justCreated,
  busy,
  prefs,
  setPrefs,
  speaking,
  previews,
  browser,
  sessionId,
  leftRail,
  rightRail,
}: {
  agent: { id: string; name: string; isAssistant: boolean } | null;
  justCreated: boolean;
  /** A turn or a background task is running: the mark pulses. */
  busy: boolean;
  prefs: AssistantPrefs;
  setPrefs: (patch: Partial<AssistantPrefs>) => void;
  speaking: boolean;
  previews: { count: number; visible: boolean; onToggle: () => void };
  browser: { ready: boolean; visible: boolean; pending: boolean; onToggle: () => void };
  sessionId: string | null;
  leftRail: ReactNode;
  rightRail: ReactNode;
}) {
  const t = useTranslations("assistant");
  const tb = useTranslations("assistantBrowser");
  return (
    <header className="asst-header flex items-center gap-1.5 border-b border-border px-2 py-1.5 sm:gap-2 sm:px-4">
      {leftRail}
      <Sparkles
        className={cn("size-4 shrink-0 text-primary", busy && "asst-mark-busy")}
        aria-hidden="true"
        data-testid="assistant-mark"
        data-busy={busy ? "true" : undefined}
      />
      <h2 className="truncate text-sm font-semibold text-foreground">{agent?.name ?? t("title")}</h2>
      {agent && !agent.isAssistant && (
        <span
          className="hidden truncate rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground sm:inline"
          data-testid="talking-to"
        >
          {t("answeringAgent", { name: agent.name })}
        </span>
      )}
      {justCreated && (
        <span className="hidden rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground sm:inline">
          {t("justCreated")}
        </span>
      )}
      <span className="flex-1" />
      <AssistantInfoCard />
      <Button
        type="button"
        size="icon"
        variant="ghost"
        aria-pressed={prefs.speakReplies}
        aria-label={prefs.speakReplies ? t("speakRepliesOff") : t("speakRepliesOn")}
        title={prefs.speakReplies ? t("speakRepliesOff") : t("speakRepliesOn")}
        className={cn(prefs.speakReplies && TOGGLE_ON)}
        onClick={() => {
          // Off means silent now: speech already queued in the browser would otherwise play on.
          if (prefs.speakReplies) stopSpeaking();
          setPrefs({ speakReplies: !prefs.speakReplies });
        }}
      >
        {prefs.speakReplies ? <Volume2 size={16} aria-hidden="true" /> : <VolumeX size={16} aria-hidden="true" />}
      </Button>
      {speaking && (
        <Button
          type="button"
          size="sm"
          variant="destructive"
          onClick={() => stopSpeaking()}
          aria-label={t("stopSpeakingNow")}
          title={t("stopSpeakingNow")}
          data-testid="stop-speaking"
        >
          <Square size={12} aria-hidden="true" />
          <span className="hidden sm:inline">{t("stopSpeakingNow")}</span>
        </Button>
      )}
      <Button
        type="button"
        size="icon"
        variant="ghost"
        aria-pressed={prefs.showThinking}
        aria-label={prefs.showThinking ? t("hideThinking") : t("showThinking")}
        title={prefs.showThinking ? t("hideThinking") : t("showThinking")}
        className={cn(prefs.showThinking && TOGGLE_ON)}
        onClick={() => setPrefs({ showThinking: !prefs.showThinking })}
      >
        <Brain size={16} aria-hidden="true" />
      </Button>
      {previews.count > 0 && (
        <Button
          type="button"
          size="sm"
          variant={previews.visible ? "secondary" : "ghost"}
          onClick={previews.onToggle}
          aria-label={t("previewTitle")}
          title={t("previewTitle")}
          data-testid="preview-toggle"
        >
          <Monitor size={14} aria-hidden="true" />
          <span className="hidden md:inline">{t("previewTitle")}</span>
        </Button>
      )}
      {browser.ready && (
        <Button
          type="button"
          size="sm"
          variant={browser.visible ? "secondary" : "ghost"}
          onClick={browser.onToggle}
          aria-pressed={browser.visible}
          aria-label={tb("toggle")}
          title={tb("toggle")}
          data-testid="browser-toggle"
        >
          <Globe size={14} aria-hidden="true" />
          <span className="hidden md:inline">{tb("toggle")}</span>
          {browser.pending && !browser.visible && (
            <span className="size-2 rounded-full bg-amber-500" role="status" aria-label={tb("needsYou")} />
          )}
        </Button>
      )}
      {agent && <ChatExportMenu agentId={agent.id} sessionId={sessionId} />}
      {agent && (
        <Link
          href={`/agents?agent=${encodeURIComponent(agent.id)}`}
          className="hidden whitespace-nowrap rounded-md px-1.5 py-1 text-xs text-muted-foreground underline-offset-2 hover:text-foreground hover:underline lg:inline"
        >
          {t("openAgent")}
        </Link>
      )}
      {rightRail}
    </header>
  );
}
