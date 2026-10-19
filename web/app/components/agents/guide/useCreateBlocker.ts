"use client";

import { useCallback } from "react";
import { useLocale } from "../../../lib/i18n";
import { findCreateBlocker, type CreateBlocker } from "./agentChecklist";
import { goToGuideAnchor } from "./guideDom";

/**
 * Save-time validation that points at the field (2026-09-19).
 *
 * AgentWorkbench#validate stays the single authority on WHETHER a save is
 * blocked - this only decides what the person is told and where the page
 * takes them. Returned as a function so the workbench's change is one call
 * inside handleCreate.
 *
 * Locale rule: the plain-language copy in agentChecklist.ts is English only
 * (messages/*.json is off-limits to piecemeal edits - see glossary.ts). So an
 * English reader gets the plain sentence, and every other locale keeps the
 * string validate() already translated rather than being switched to English
 * mid-screen. The scroll-and-highlight happens for everyone: it needs no
 * words.
 */
export function useCreateBlocker() {
  const locale = useLocale();
  return useCallback(
    (
      input: Parameters<typeof findCreateBlocker>[0],
      translated: string
    ): { message: string; blocker: CreateBlocker | null } => {
      let blocker: CreateBlocker | null = null;
      try {
        blocker = findCreateBlocker(input);
        if (blocker?.anchor) goToGuideAnchor(blocker.anchor);
      } catch {
        // Guidance must never be the reason a validation message is lost.
        blocker = null;
      }
      const english = typeof locale === "string" && locale.toLowerCase().startsWith("en");
      return { message: blocker && english ? blocker.message : translated, blocker };
    },
    [locale]
  );
}
