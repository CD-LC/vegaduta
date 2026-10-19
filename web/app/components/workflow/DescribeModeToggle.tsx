"use client";

import { useTranslations } from "../../lib/i18n";
import type { DescribeMode } from "./workflowPlan";

/**
 * Describe bar: "Change this workflow" / "Start over" (nlflow W4).
 *
 * A two-button segmented toggle instead of a native <select> (the console does
 * not use OS-rendered pickers) and instead of the shared Select, whose floating
 * panel and 12rem minimum width do not fit a one-line bar at phone width.
 * Buttons carry aria-pressed; the group carries the accessible name.
 */
type Props = {
  mode: DescribeMode;
  onChange: (mode: DescribeMode) => void;
  disabled?: boolean;
};

const MODES: { mode: DescribeMode; labelKey: string }[] = [
  { mode: "edit", labelKey: "barModeEdit" },
  { mode: "replace", labelKey: "barModeReplace" },
];

export default function DescribeModeToggle({ mode, onChange, disabled = false }: Props) {
  const t = useTranslations("workflowDescribe");
  return (
    <div
      role="group"
      aria-label={t("barModeLabel")}
      data-testid="workflow-describe-mode"
      className="inline-flex shrink-0 overflow-hidden rounded-md border border-border bg-background"
    >
      {MODES.map((m) => {
        const active = m.mode === mode;
        return (
          <button
            key={m.mode}
            type="button"
            aria-pressed={active}
            disabled={disabled}
            onClick={() => onChange(m.mode)}
            data-testid={`workflow-describe-mode-${m.mode}`}
            className={
              "h-8 rounded-none border-0 px-2.5 text-xs font-medium shadow-none transition-colors disabled:opacity-50 " +
              (active ? "bg-accent text-foreground" : "bg-transparent text-muted-foreground hover:text-foreground")
            }
          >
            {t(m.labelKey)}
          </button>
        );
      })}
    </div>
  );
}
