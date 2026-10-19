"use client";

import { useState } from "react";
import { Share2 } from "lucide-react";
import { Button } from "../ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";
import SubmitTemplateFlow from "./SubmitTemplateFlow";
import { useAuth } from "../AuthProvider";
import type { CommunityTemplateKind } from "../../lib/api";

// Self-contained "Share as template" entry point for the Agents (or Workflows)
// screen. Drop it in next to an agent with its id/name and it handles the rest
// (opens the submit flow pre-targeted at that source). Deliberately owns its
// own open/close state and imports nothing from the agents feature, so it can
// be mounted from AgentWorkbench.tsx with a single line without this file
// depending on - or being owned by - that surface.
//
// Example mount (Agents screen, per-agent action row):
//   <ShareAsTemplateButton sourceId={agent.id} sourceName={agent.name} />
export default function ShareAsTemplateButton({
  sourceId,
  sourceName,
  sourceKind = "AGENT",
  variant = "outline",
  size = "sm",
  className,
  label = "Share as template",
}: {
  sourceId: string;
  sourceName?: string;
  sourceKind?: CommunityTemplateKind;
  variant?: "default" | "outline" | "ghost" | "secondary";
  size?: "default" | "sm" | "lg" | "icon";
  className?: string;
  label?: string;
}) {
  const [open, setOpen] = useState(false);
  const { hasPermission } = useAuth();
  // Creating a template needs MANAGE_TEMPLATES: without it there is nothing
  // this button could do but fail.
  if (!hasPermission("MANAGE_TEMPLATES")) return null;
  const tooltip = "Share this as a community template";
  const button = (
    <Button
      variant={variant}
      size={size}
      className={className}
      onClick={() => setOpen(true)}
      aria-label={size === "icon" ? label : undefined}
      title={size === "icon" ? undefined : tooltip}
    >
      <Share2 size={size === "icon" ? 16 : 14} strokeWidth={2} aria-hidden="true" />
      {size !== "icon" && label}
    </Button>
  );
  return (
    <>
      {/* Icon-only mounts get a real tooltip (the label is otherwise invisible);
          labelled mounts keep the plain title. */}
      {size === "icon" ? (
        <Tooltip>
          <TooltipTrigger asChild>{button}</TooltipTrigger>
          <TooltipContent>{tooltip}</TooltipContent>
        </Tooltip>
      ) : (
        button
      )}
      <SubmitTemplateFlow
        open={open}
        onClose={() => setOpen(false)}
        presetSource={{ kind: sourceKind, id: String(sourceId), name: sourceName }}
      />
    </>
  );
}
