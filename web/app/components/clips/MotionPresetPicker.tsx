"use client";

import { MOTION_PRESETS, type MotionPreset } from "../../lib/edge/video/kenburns";

/**
 * Motion preset selector for the Clip Studio. Six fixed
 * camera moves from kenburns.ts, rendered as a small toggle grid. Purely
 * presentational — selection state lives in ClipStudio.
 */
export default function MotionPresetPicker({
  value,
  onChange,
  disabled = false,
}: {
  value: MotionPreset;
  onChange: (preset: MotionPreset) => void;
  disabled?: boolean;
}) {
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
      {MOTION_PRESETS.map((preset) => {
        const selected = preset.id === value;
        return (
          <button
            key={preset.id}
            type="button"
            disabled={disabled}
            onClick={() => onChange(preset.id)}
            aria-pressed={selected}
            className={
              "rounded-md border px-3 py-2 text-start transition-colors disabled:cursor-not-allowed disabled:opacity-50 " +
              (selected
                ? "border-primary bg-primary/10 text-foreground"
                : "border-border bg-transparent text-muted-foreground hover:border-primary/40 hover:text-foreground")
            }
          >
            <span className="block text-xs font-medium">{preset.label}</span>
            <span className="mt-0.5 block text-[11px] leading-snug">{preset.description}</span>
          </button>
        );
      })}
    </div>
  );
}
