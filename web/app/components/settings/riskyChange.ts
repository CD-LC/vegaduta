import type { SettingCatalogEntry } from "../../lib/api/settings";
import type { RiskyChange } from "./RiskyChangeDialog";
import type { SettingsText } from "./useSettingsText";

/**
 * The confirmation line for a high-risk or security change, or for the reset of one: what it is called, from what to
 * what (already formatted), what it changes, and for a security key which direction is stricter. One place, so the
 * panel, the workspace screen and the reset flow can never describe the same change differently.
 */
export function buildRiskyChange(
  text: SettingsText,
  entry: SettingCatalogEntry,
  from: unknown,
  to: unknown,
  kind: "change" | "reset" = "change"
): RiskyChange {
  const strictness = entry.risk === "security" ? text.strictnessHint(entry) : "";
  return {
    key: entry.key,
    label: text.label(entry),
    risk: entry.risk,
    fromText: text.formatValue(entry, from),
    toText: text.formatValue(entry, to),
    consequence: text.help(entry) || text.description(entry),
    ...(kind === "reset" ? { kind } : {}),
    ...(strictness ? { strictness } : {}),
  };
}
