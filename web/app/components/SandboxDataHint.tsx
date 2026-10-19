"use client";

/**
 * One muted line under a chat composer saying why data-file analysis
 * (CSV / XLSX into the run_code sandbox) is not on offer for this agent, with
 * a link to the place that fixes it - when there is such a place.
 *
 * Renders NOTHING unless core sent modalities.sandboxDataUnavailableReason -
 * which it only does when app.sandbox.data-analysis.enabled is on and the
 * sandbox is unavailable for this agent - so a default deployment and an
 * older core show no change. The sentence is the server's, shown verbatim;
 * see lib/sandboxDataHint.ts for the (pure, tested) link choice and the
 * per-tab dismissal memory.
 */

import { useState } from "react";
import type { MouseEvent } from "react";
import Link from "next/link";
import { X } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { cn } from "../lib/utils";
import {
  CAPABILITIES_ANCHOR_ID,
  isSandboxHintDismissed,
  rememberSandboxHintDismissed,
  sandboxDataHint,
  type SandboxDataHintModel,
} from "../lib/sandboxDataHint";
import { goToGuideAnchor } from "./agents/guide/guideDom";

export default function SandboxDataHint({ agent, className }: { agent: any; className?: string }) {
  // Resolved before any hook that needs the i18n provider, so a surface with
  // no hint to show never touches it.
  const hint = sandboxDataHint(agent);
  if (!hint) return null;
  const agentId = typeof agent?.id === "string" ? agent.id : "";
  return <SandboxDataHintLine key={`${agentId}:${hint.reason}`} agentId={agentId} hint={hint} className={className} />;
}

function SandboxDataHintLine({
  agentId,
  hint,
  className,
}: {
  agentId: string;
  hint: SandboxDataHintModel;
  className?: string;
}) {
  const t = useTranslations("sandboxDataHint");
  // Remembered for this browser tab per agent + reason (keyed mount above), so
  // a dismissed hint stays away across reloads and agent switches; a different
  // reason for the same agent is a new hint.
  const [dismissed, setDismissed] = useState(() => isSandboxHintDismissed(agentId, hint.reason));
  if (dismissed) return null;

  function handleLinkClick(e: MouseEvent<HTMLAnchorElement>) {
    if (hint.target !== "capabilities") return;
    // In the agent workbench the Capabilities section is on this very page:
    // open and scroll to it instead of navigating. Elsewhere (/playground)
    // the anchor is absent and the link navigates to the agent.
    if (goToGuideAnchor(CAPABILITIES_ANCHOR_ID)) e.preventDefault();
  }

  return (
    <p
      data-testid="sandbox-data-hint"
      className={cn("mb-2 flex items-start gap-2 text-xs text-muted-foreground", className)}
    >
      <span className="min-w-0 flex-1">
        {hint.reason}
        {/* No link for a reason the person cannot fix (target "none"). */}
        {hint.href && (
          <>
            {" "}
            <Link
              href={hint.href}
              onClick={handleLinkClick}
              className="font-medium text-primary underline-offset-4 hover:underline"
            >
              {hint.target === "capabilities" ? t("openCapabilities") : t("openProviders")}
            </Link>
          </>
        )}
      </span>
      <button
        type="button"
        onClick={() => {
          rememberSandboxHintDismissed(agentId, hint.reason);
          setDismissed(true);
        }}
        aria-label={t("dismiss")}
        title={t("dismiss")}
        // p-0/border-0/bg-transparent: globals.css's bare `button` rule would
        // otherwise pad this icon-only control (same note as ChatAttachments).
        className="-m-1 flex shrink-0 items-center justify-center rounded-full border-0 bg-transparent p-1 text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-2 focus-visible:outline-ring"
      >
        <X size={12} aria-hidden="true" />
      </button>
    </p>
  );
}
