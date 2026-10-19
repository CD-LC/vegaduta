"use client";

import { Bot, CloudUpload, MessageSquare, Zap } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import Select from "../Select";
import type { ComposerMode } from "./assistantTypes";

/**
 * The row under the box that replaced three pages: who answers (the Assistant or any agent of the
 * workspace; the old Playground's dropdown) and how the request is sent (one turn, a background
 * job - the old Jobs launcher - or a one-shot Quick Task). Renders only the parts that apply: one
 * agent means no picker; a core without jobs or quick tasks hides those modes.
 */
export default function ComposerModeBar({
  agents,
  activeAgentId,
  assistantId,
  onSelectAgent,
  mode,
  onMode,
  modes,
  disabled,
}: {
  agents: ReadonlyArray<{ id: string; name: string }>;
  activeAgentId: string;
  assistantId: string;
  onSelectAgent: (id: string) => void;
  mode: ComposerMode;
  onMode: (mode: ComposerMode) => void;
  /** Which modes this install offers besides chat. */
  modes: { background: boolean; task: boolean };
  disabled: boolean;
}) {
  const t = useTranslations("assistant");
  const options = agents.map((a) => ({
    value: a.id,
    label: a.id === assistantId ? t("agentOptionAssistant", { name: a.name }) : a.name,
  }));
  // Quick Task without a chosen agent lets the server pick the specialist.
  if (modes.task) options.push({ value: "", label: t("agentOptionAuto") });
  const chips: { value: ComposerMode; icon: typeof MessageSquare; show: boolean }[] = [
    { value: "chat", icon: MessageSquare, show: true },
    { value: "background", icon: CloudUpload, show: modes.background },
    { value: "task", icon: Zap, show: modes.task },
  ];
  const showPicker = agents.length > 1 || modes.task;
  if (!showPicker && !modes.background && !modes.task) return null;

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5" data-testid="composer-mode-bar">
      {showPicker && (
        <div className="flex min-w-0 items-center gap-1.5">
          <Bot size={14} className="shrink-0 text-muted-foreground" aria-hidden="true" />
          <Select
            aria-label={t("agentPickerLabel")}
            data-testid="assistant-agent-picker"
            value={activeAgentId}
            onChange={(e: { target: { value: string } }) => onSelectAgent(e.target.value)}
            options={options}
            disabled={disabled}
            searchable={agents.length > 8}
            className="min-w-44 max-w-64 text-xs [&_button]:h-8 [&_button]:border-transparent [&_button]:bg-transparent [&_button]:px-2 [&_button]:text-xs"
          />
        </div>
      )}
      <div
        role="radiogroup"
        aria-label={t("modeLabel")}
        className="flex items-center gap-0.5 rounded-full bg-secondary/70 p-0.5"
      >
        {chips
          .filter((c) => c.show)
          .map(({ value, icon: Icon }) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={mode === value}
              disabled={disabled}
              onClick={() => onMode(value)}
              title={t(`modeHint_${value}`)}
              data-testid={`mode-${value}`}
              className={cn(
                "inline-flex h-7 items-center gap-1 rounded-full px-2.5 text-xs transition-colors",
                mode === value ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:bg-secondary/60"
              )}
            >
              <Icon size={12} aria-hidden="true" />
              {t(`mode_${value}`)}
            </button>
          ))}
      </div>
      {mode !== "chat" && (
        <span className="min-w-0 flex-1 truncate text-[11px] text-muted-foreground">{t(`modeHint_${mode}`)}</span>
      )}
    </div>
  );
}
