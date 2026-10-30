"use client";

import { AlertTriangle, Ban, HelpCircle } from "lucide-react";
import { Badge } from "../ui/badge";
import { cn } from "../../lib/utils";
import { SEVERITY_STATE_TOKEN, UNCLASSIFIED_META, type TierMeta } from "./toolClassification";
import { useTranslations } from "../../lib/i18n";

/**
 * The pills the tool-classification surface renders. Split out of the panel
 * so the table, the legend and (later) the per-agent tool list all draw the
 * same mark for the same tier — see toolClassification.ts for why that
 * matters on a safety screen.
 *
 * All three pills go through the shared `Badge` primitive rather than hand-
 * rolled spans: `variant="state"` is the console's existing `.state-pill`
 * treatment, which derives its wash and border from one `--state` custom
 * property and is contrast-verified in both themes (design-system contract
 * §3). Every pill also carries an icon, because the contract's hard rule §2
 * is that colour may never be the only cue for a category — and because a
 * colour-blind reader must still be able to tell "sends data out" from
 * "reads public info".
 */

/** A single classified tier. `showCode` adds the raw enum value for the
 *  engineer reading over the admin's shoulder. */
export function TierPill({
  meta,
  showCode = false,
  className,
}: {
  meta: TierMeta;
  showCode?: boolean;
  className?: string;
}) {
  const Icon = meta.icon;
  return (
    <Badge
      variant="state"
      state={SEVERITY_STATE_TOKEN[meta.severity]}
      className={cn("gap-1.5", className)}
      // The plain-language meaning is the legend's job, but repeating it as a
      // native tooltip means a reader who has scrolled past the legend does
      // not have to scroll back to remember what a tier meant.
      title={`${meta.code} — ${meta.meaning}`}
    >
      <Icon aria-hidden="true" />
      {meta.label}
      {showCode && <span className="font-mono text-[0.625rem]">{meta.code}</span>}
    </Badge>
  );
}

/**
 * The blocked state, rendered as the loudest thing on its row. Solid
 * `destructive` fill rather than the outlined `.state-pill` used for every
 * classified tier: an unclassified tool is not one more shade of risk, it is
 * a tool that will not run, and the visual weight has to say so without the
 * reader comparing hues.
 */
export function UnclassifiedPill({ className }: { className?: string }) {
  return (
    <Badge variant="destructive" className={cn("gap-1.5", className)} title={UNCLASSIFIED_META.meaning}>
      <Ban aria-hidden="true" />
      {UNCLASSIFIED_META.label}
    </Badge>
  );
}

/**
 * A tier value the API sent that this build of the console does not
 * recognise. Shown verbatim and flagged as unrecognised rather than mapped
 * onto the nearest known tier: guessing here would mean the UI inventing a
 * safety claim about a tool, which is exactly what this screen exists to
 * prevent.
 */
export function UnrecognisedTierPill({ value, className }: { value: string; className?: string }) {
  return (
    <Badge
      variant="outline"
      className={cn("gap-1.5 font-mono text-[0.6875rem]", className)}
      title={`The backend reported "${value}" for this axis. This version of the console doesn't know that value, so it isn't being interpreted — update the console or check the backend's enum.`}
    >
      <AlertTriangle aria-hidden="true" />
      {value}
    </Badge>
  );
}

/**
 * One axis of one row. Three outcomes, deliberately distinct:
 *   - a recognised tier            → its pill
 *   - a value we do not know       → shown verbatim, flagged unrecognised
 *   - nothing declared at all      → "not declared", which (together with the
 *                                     row-level blocked pill) is the
 *                                     fail-closed reading, not a blank cell
 *
 * The third case is why this is a component and not `meta && <TierPill/>`:
 * an empty cell is the one rendering that would quietly read as "fine".
 */
export function TierCell({ meta, rawValue }: { meta: TierMeta | null; rawValue: string | null | undefined }) {
    const tHC = useTranslations("hcComponentsToolsToolClassificationBadges");
  if (meta) return <TierPill meta={meta} />;
  if (rawValue) return <UnrecognisedTierPill value={rawValue} />;
  return (
    <span
      className="inline-flex items-center gap-1 text-xs text-muted-foreground"
      title={tHC("s2")}
    >
      <HelpCircle className="size-3" aria-hidden="true" />
      {tHC("s1")}
    </span>
  );
}
