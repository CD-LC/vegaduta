"use client";

import { createElement } from "react";
import { useRouter } from "next/navigation";
import { Hammer, MessageSquarePlus, SlidersHorizontal, Sparkles, Workflow, Zap } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { PaletteItem } from "./AssistantPalette";
import type { ComposerMode } from "./assistantTypes";

const icon = (Icon: typeof Sparkles) => createElement(Icon, { size: 14 });

/** The Cmd/Ctrl+K palette's entries (lifted out of AssistantPane): actions, build shortcuts and starter asks. */
export function useAssistantPaletteItems({
  agentId,
  jobs,
  onNewThread,
  onMode,
  onPrompt,
}: {
  agentId: string | null;
  /** Background jobs are reachable (the "In background" mode is offered). */
  jobs: boolean;
  onNewThread: () => void;
  onMode: (mode: ComposerMode) => void;
  onPrompt: (text: string) => void;
}): PaletteItem[] {
  const t = useTranslations("assistant");
  const router = useRouter();
  const actions = t("paletteGroupActions");
  const build = t("paletteGroupBuild");
  return [
    { id: "new", group: actions, label: t("paletteNewThread"), icon: icon(MessageSquarePlus), run: onNewThread },
    { id: "mode-chat", group: actions, label: t("statusMode_chat"), icon: icon(Sparkles), run: () => onMode("chat") },
    ...(jobs
      ? [
          {
            id: "mode-bg",
            group: actions,
            label: t("statusMode_background"),
            icon: icon(Workflow),
            run: () => onMode("background"),
          },
        ]
      : []),
    { id: "mode-task", group: actions, label: t("statusMode_task"), icon: icon(Zap), run: () => onMode("task") },
    {
      id: "tune",
      group: build,
      label: t("journey_tune_title"),
      hint: t("paletteOpens"),
      icon: icon(SlidersHorizontal),
      run: () => router.push(agentId ? `/agents?agent=${encodeURIComponent(agentId)}` : "/agents"),
    },
    {
      id: "workflow",
      group: build,
      label: t("journey_workflow_title"),
      hint: t("paletteOpens"),
      icon: icon(Workflow),
      run: () => router.push("/workflows"),
    },
    {
      id: "app",
      group: build,
      label: t("journey_app_title"),
      hint: t("paletteOpens"),
      icon: icon(Hammer),
      run: () => router.push("/build"),
    },
    ...(["tune", "workflow", "app"] as const).map((j) => ({
      id: `ask-${j}`,
      group: t("paletteGroupAsk"),
      label: t(`journey_${j}_blurb`),
      icon: icon(Sparkles),
      run: () => onPrompt(t(`journey_${j}_prompt`)),
    })),
  ];
}
