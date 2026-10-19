"use client";

import { X } from "lucide-react";
import { Button } from "../../ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "../../ui/tooltip";
import { edgeEnabled, setEdgeBannerDismissed, setEdgeOptIn } from "../../../lib/edge/capabilities";
import { EDGE_BRANDING } from "../../../lib/edge/branding";

/** Edge opt-in banner: brand-first copy, dismissible, never blocking. */
export function EdgeOptInBanner({
  edgeTier0Ready,
  edgeOptIn,
  edgeBannerDismissed,
}: {
  edgeTier0Ready: boolean;
  edgeOptIn: boolean;
  edgeBannerDismissed: boolean;
}) {
  return (
    <>
    {/* Edge opt-in banner: brand-first copy from the branding
      seam, dismissible, never blocking. Shown only when the flag is on,
      the browser's built-in model can actually serve (tier 0), and the
      person has neither enabled nor dismissed it. */}
    {edgeEnabled() && edgeTier0Ready && !edgeOptIn && !edgeBannerDismissed && (
      <div className="flex items-center justify-between gap-3 border-b border-border bg-secondary/30 px-5 py-2.5">
        <p className="min-w-0 text-xs text-muted-foreground">
          <span className="font-medium text-foreground">{EDGE_BRANDING.bannerTitle}</span>
          {" - "}
          {EDGE_BRANDING.bannerBody}
        </p>
        <div className="flex shrink-0 items-center gap-1.5">
          <Button type="button" variant="outline" size="sm" onClick={() => setEdgeOptIn(true)}>
            {EDGE_BRANDING.bannerEnable}
          </Button>
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                onClick={() => setEdgeBannerDismissed()}
                aria-label={EDGE_BRANDING.bannerDismissLabel}
                className="flex size-6 items-center justify-center rounded border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-secondary hover:text-foreground"
              >
                <X size={12} />
              </button>
            </TooltipTrigger>
            <TooltipContent>Dismiss</TooltipContent>
          </Tooltip>
        </div>
      </div>
    )}
    </>
  );
}
