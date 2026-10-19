"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ShieldAlert } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Button } from "../ui/button";
import BrandText from "../BrandText";

/**
 * Lethal-trifecta warning for the agent workbench's tools section.
 *
 * Core computes this report (LethalTrifectaDetector,
 * GET /api/agents/{id}/tool-access/trifecta); the PATCH's X-Agent-Warning
 * header is not CORS-exposed, so the only usable source from the app origin is
 * the GET.
 *
 * The three legs are: tools that read private data, tools that ingest
 * untrusted content, and tools that send data out. Any two are ordinary; all
 * three together mean a prompt injection hidden in the untrusted content can
 * exfiltrate the private data.
 *
 * The report started as advisory only. Core can now act on it
 * (app.tools.classification.trifecta-enforcement), and the same GET says how:
 * `enforcement` is the configured mode (off | warn | require-approval | block),
 * `enforced` is true when the platform acts on a trifecta this agent holds, and
 * `gated` is true when actions that send data out always need a person for this
 * agent. All three are read defensively: an older core that sends none of them
 * is simply "warn, not enforced, not gated", exactly the old advisory.
 *
 * The component self-hides on any fetch failure (older core answers 404),
 * because a missing advisory must not look like an error on the builder's
 * screen. The one thing it shows regardless of the report is `blockedMessage`:
 * the 400 a block-mode save was rejected with, which the caller already has.
 */

export interface TrifectaReport {
  privateDataTools: string[];
  untrustedContentTools: string[];
  egressTools: string[];
  unclassifiedTools: string[];
}

export type TrifectaEnforcementMode = "off" | "warn" | "require-approval" | "block";

export interface TrifectaEnforcement {
  mode: TrifectaEnforcementMode;
  enforced: boolean;
  gated: boolean;
}

const ENFORCEMENT_MODES: readonly string[] = ["off", "warn", "require-approval", "block"];

function names(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === "string" && v.length > 0) : [];
}

/** Defensive read of the wire shape - a partial/odd body becomes empty legs, never a throw. */
export function normalizeTrifectaReport(raw: unknown): TrifectaReport {
  const r = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  return {
    privateDataTools: names(r.privateDataTools),
    untrustedContentTools: names(r.untrustedContentTools),
    egressTools: names(r.egressTools),
    unclassifiedTools: names(r.unclassifiedTools),
  };
}

/** Defensive read of the enforcement fields; absent or unknown means the old advisory ("warn"). */
export function normalizeTrifectaEnforcement(raw: unknown): TrifectaEnforcement {
  const r = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const mode =
    typeof r.enforcement === "string" && ENFORCEMENT_MODES.includes(r.enforcement)
      ? (r.enforcement as TrifectaEnforcementMode)
      : "warn";
  return { mode, enforced: r.enforced === true, gated: r.gated === true };
}

/** Same rule as LethalTrifectaDetector.Report#isLethalTrifecta: all three legs non-empty. */
export function isLethalTrifecta(report: TrifectaReport | null | undefined): boolean {
  return (
    !!report &&
    report.privateDataTools.length > 0 &&
    report.untrustedContentTools.length > 0 &&
    report.egressTools.length > 0
  );
}

/**
 * True for the 400 a block-mode allow-list save is rejected with
 * (TrifectaEnforcementPolicy.BLOCK_MESSAGE_PREFIX in core), so the Tools tab can
 * show it as a policy decision with the three legs named, not a generic error.
 */
export function isTrifectaBlockError(err: unknown): boolean {
  if (!err || typeof err !== "object") return false;
  const e = err as { status?: unknown; message?: unknown };
  return e.status === 400 && typeof e.message === "string" && e.message.toLowerCase().includes("lethal-trifecta policy");
}

export default function AgentTrifectaWarning({
  agentId,
  refreshKey = 0,
  approvalRequired = false,
  onRequireApproval,
  requireApprovalDisabled = false,
  blockedMessage = null,
}: {
  agentId: string | null | undefined;
  /**
   * Any value that changes when the agent's tool mix may have changed (a
   * counter bumped after a save, or a fingerprint of the saved tool config);
   * a change refetches the report.
   */
  refreshKey?: number | string;
  /** True when the agent already waits for a human on every tool call. */
  approvalRequired?: boolean;
  /** Switches the agent to the tier that gates every tool call. Omit to hide the action. */
  onRequireApproval?: () => void;
  requireApprovalDisabled?: boolean;
  /** The block-mode rejection message of the last save attempt, shown whatever the report says. */
  blockedMessage?: string | null;
}) {
  const t = useTranslations("agentTrifecta");
  // Keyed by the agent it was fetched for, so switching agents can never
  // flash the previous agent's warning while the new fetch is in flight.
  const [loaded, setLoaded] = useState<{
    agentId: string;
    report: TrifectaReport;
    enforcement: TrifectaEnforcement;
  } | null>(null);

  useEffect(() => {
    if (!agentId) {
      setLoaded(null);
      return undefined;
    }
    let cancelled = false;
    const requestedFor: string = agentId;
    api
      .getAgentToolTrifecta(requestedFor)
      .then((raw: unknown) => {
        if (!cancelled) {
          setLoaded({
            agentId: requestedFor,
            report: normalizeTrifectaReport(raw),
            enforcement: normalizeTrifectaEnforcement(raw),
          });
        }
      })
      .catch(() => {
        // 404 (older core / agent not visible) or a transient failure: the
        // advisory simply is not shown. Never an error state.
        if (!cancelled) setLoaded(null);
      });
    return () => {
      cancelled = true;
    };
  }, [agentId, refreshKey]);

  const current = loaded && loaded.agentId === agentId ? loaded : null;
  const blocked =
    agentId && blockedMessage ? (
      <Alert variant="error" data-testid="trifecta-save-blocked">
        <ShieldAlert aria-hidden="true" />
        <AlertTitle>{t("saveBlockedTitle")}</AlertTitle>
        <AlertDescription>
          <p className="min-w-0 break-words">{blockedMessage}</p>
        </AlertDescription>
      </Alert>
    ) : null;

  if (!agentId || !current) return blocked ? <div className="mb-3">{blocked}</div> : null;

  const { report, enforcement } = current;
  const lethal = isLethalTrifecta(report);
  const unclassified = report.unclassifiedTools.length;
  if (!lethal && unclassified === 0) return blocked ? <div className="mb-3">{blocked}</div> : null;

  const unclassifiedLine =
    unclassified > 0 ? (
      <p
        className="text-xs text-muted-foreground"
        data-testid="trifecta-unclassified"
        title={report.unclassifiedTools.join(", ")}
      >
        {unclassified === 1 ? t("unclassifiedOne") : t("unclassifiedMany", { count: unclassified })}
      </p>
    ) : null;

  if (!lethal) {
    return (
      <div className="mb-3 space-y-1.5">
        {blocked}
        {unclassifiedLine}
      </div>
    );
  }

  const legs: { key: string; label: string; tools: string[] }[] = [
    { key: "private", label: t("legPrivate"), tools: report.privateDataTools },
    { key: "untrusted", label: t("legUntrusted"), tools: report.untrustedContentTools },
    { key: "egress", label: t("legEgress"), tools: report.egressTools },
  ];

  return (
    <div className="mb-3 space-y-1.5">
      {blocked}
      <Alert variant="warning" data-testid="trifecta-warning">
        <ShieldAlert aria-hidden="true" />
        <AlertTitle><BrandText text={t("title")} /></AlertTitle>
        <AlertDescription>
          <p><BrandText text={t("body")} /></p>
          <ul className="w-full space-y-0.5">
            {legs.map((leg) => (
              <li key={leg.key} data-testid={`trifecta-leg-${leg.key}`} className="min-w-0 break-words">
                <span className="font-medium">{leg.label}</span>{" "}
                <span className="font-mono text-xs">{leg.tools.join(", ")}</span>
              </li>
            ))}
          </ul>
          {enforcement.mode === "block" && (
            <p className="font-medium" data-testid="trifecta-block-note">
              {t("blockedNote")}
            </p>
          )}
          {/* Only promise "every action" while the tier really is SUPERVISED: a later switch to a more autonomous tier is not intercepted. */}
          {enforcement.enforced && enforcement.mode === "require-approval" && approvalRequired && (
            <p className="font-medium" data-testid="trifecta-enforced-note">
              {t("enforcedNote")}
            </p>
          )}
          {enforcement.gated && (
            <p className="font-medium" data-testid="trifecta-gated-note">
              {t("gatedNote")}
            </p>
          )}
          <p>{t("advice")}</p>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            {approvalRequired ? (
              <span className="text-xs" data-testid="trifecta-approval-on">
                {t("approvalAlreadyOn")}
              </span>
            ) : (
              onRequireApproval && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  data-testid="trifecta-require-approval"
                  disabled={requireApprovalDisabled}
                  onClick={onRequireApproval}
                >
                  {t("requireApproval")}
                </Button>
              )
            )}
            <Button asChild variant="ghost" size="sm">
              <Link
                href={`/agents/quality?tab=simulations&agentId=${encodeURIComponent(agentId)}#attack`}
                data-testid="trifecta-test-link"
              >
                {t("testAgent")}
              </Link>
            </Button>
          </div>
        </AlertDescription>
      </Alert>
      {unclassifiedLine}
    </div>
  );
}
