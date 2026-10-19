// The BIZ personalization model from the approved mockup
// (scratchpad/vegaduta-landing-v1.html): answering interview Q1 rewrites the
// CV skill line, the work trial's first register entry, the approval-queue
// message, the offer letter's greeting, and the salary worksheet's default
// hours.
//
// ALL copy for these variants lives in web/messages/en.json under the "hire"
// namespace (keys "biz_<key>_<field>", defaults under "default_<field>") —
// per the landing-i18n precedent set by e94f580b, nothing user-visible is
// hardcoded here. This module only carries the key vocabulary and the one
// non-copy datum (default worksheet hours per business type). The deliberate
// Hinglish/Devanagari values in en.json (e.g. biz_kirana_approve) are brand
// voice — English-source values, not untranslated leftovers.

export type BizKey = "kirana" | "clinic" | "salon" | "agency";

export const BIZ_KEYS: BizKey[] = ["kirana", "clinic", "salon", "agency"];

/** Salary-worksheet default hours per business type (mockup's BIZ[k].hrs). */
export const BIZ_HRS: Record<BizKey, number> = {
  kirana: 14,
  clinic: 12,
  salon: 10,
  agency: 16,
};

/** Worksheet default before any interview answer (mockup's initial slider). */
export const DEFAULT_HRS = 14;

/**
 * i18n key for a personalized field: bizKey("t1h", "kirana") →
 * "biz_kirana_t1h"; bizKey("t1h", null) → "default_t1h". Every produced key
 * exists in en.json's hire namespace — the provider returns the dotted path
 * on a miss (truthy!), so key coverage is load-bearing.
 */
export function bizKey(
  field:
    | "label"
    | "cvFor"
    | "skillMain"
    | "skillSmall"
    | "t1h"
    | "t1p"
    | "t1pIN"
    | "greet"
    | "biz"
    | "approve"
    | "approveIN",
  biz: BizKey | null
): string {
  return biz ? `biz_${biz}_${field}` : `default_${field}`;
}
