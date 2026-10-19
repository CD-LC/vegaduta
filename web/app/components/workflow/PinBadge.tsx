"use client";

import { Pin } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { formatPinSize, formatPinTime, pinSourceKey, type PinMeta } from "./pinModel";
import "./pins.css";

type Translator = (key: string, values?: Record<string, string | number>) => string;

/** Tooltip / accessible name for a pinned node: where it came from, when, how big, and what it does. */
export function pinDescription(t: Translator, pin: PinMeta): string {
  return t("pinBadgeTitle", {
    source: t(pinSourceKey(pin.source)),
    when: formatPinTime(pin.pinnedAt) || "-",
    size: formatPinSize(pin.sizeChars) || "-",
  });
}

/** Filled pin glyph on a node card header; only rendered for pinned nodes. */
export default function PinBadge({ pin, size = 13 }: { pin: PinMeta; size?: number }) {
  const t = useTranslations("workflowCanvas") as Translator;
  const text = pinDescription(t, pin);
  return (
    <span className="wfx-pin-badge" role="img" aria-label={text} title={text} data-testid="pin-badge">
      <Pin size={size} fill="currentColor" strokeWidth={2} aria-hidden="true" />
    </span>
  );
}
