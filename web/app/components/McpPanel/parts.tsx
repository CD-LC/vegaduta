"use client";

import { Clock, ExternalLink, AlertTriangle, ShieldCheck, HelpCircle, KeyRound, CheckCircle2 } from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Alert, AlertTitle, AlertDescription } from "../ui/alert";
import { credentialGuideFor, type McpCredentialGuideField } from "../../lib/credentialGuides";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";
import { TONE_SUCCESS, TONE_WARNING, usageStatusOf, reviewStatusOf } from "./helpers";

// Badges and alerts shared by the MCP inspector and grid cards. The
// connection-diagnostic controls live in connectionParts.tsx, the
// enable/catalog-connect forms in catalogParts.tsx.

// Provenance/trust badge - NOT_REQUIRED covers both a remote registration
// (never spawns a process) and a curated catalog entry the platform team
// already vetted, so both read as "Platform-vetted"; anything else is a
// self-service registration (hand-typed, or added via Browse registry) that
// has to clear (or is clearing) the review gate. Renders nothing on an older
// core with no reviewStatus field at all - there's nothing honest to claim
// either way there.
export function ProvenanceReviewBadge({ server, compact }: { server: any; compact?: boolean }) {
  const t = useTranslations("mcpPanel");
  const status = reviewStatusOf(server);
  if (!status) return null;
  const sizeCls = compact ? "h-5 shrink-0 gap-1 whitespace-nowrap px-2 text-xs font-medium" : "gap-1";
  if (status === "NOT_REQUIRED") {
    return (
      <Badge variant="outline" className={cn(sizeCls, TONE_SUCCESS)} title={t("provenancePlatformVettedTooltip")}>
        <ShieldCheck size={compact ? 10 : 12} aria-hidden="true" />{" "}
        {compact ? "Verified" : t("provenancePlatformVetted")}
      </Badge>
    );
  }
  if (status === "APPROVED") {
    return (
      <Badge variant="outline" className={cn(sizeCls, TONE_SUCCESS)} title={t("provenanceReviewedTooltip")}>
        <ShieldCheck size={compact ? 10 : 12} aria-hidden="true" /> {t("provenanceReviewed")}
      </Badge>
    );
  }
  if (status === "PENDING") {
    return (
      <Badge
        variant="outline"
        className={cn(sizeCls, "border-[var(--info-border)] bg-[var(--info-bg)] text-[var(--info-text)]")}
        title={t("provenanceAwaitingReviewTooltip")}
      >
        <Clock size={compact ? 10 : 12} aria-hidden="true" /> {t("provenanceAwaitingReview")}
      </Badge>
    );
  }
  // REJECTED
  return (
    <Badge
      variant="outline"
      className={cn(sizeCls, "border-destructive/30 bg-destructive/10 text-destructive")}
      title={t("provenanceRejectedTooltip")}
    >
      <AlertTriangle size={compact ? 10 : 12} aria-hidden="true" /> {t("provenanceRejected")}
    </Badge>
  );
}

// Catalog entries (card.kind === "catalog") come from McpCatalogService, the
// platform team's own curated list - unlike arbitrary "Browse registry"
// results (RegistryBrowser.tsx deliberately has no verified badge, since
// registry publication alone doesn't mean much - see RegistrySearchResult's
// javadoc), every entry here was hand-picked and reviewed before being
// listed, so a "Verified" badge is meaningful and non-universal here. Shown
// before the entry is ever added, not just after (ProvenanceReviewBadge
// above only has something to render once a `server` row - post
// registration - exists).
export function CatalogVerifiedBadge({ compact }: { compact?: boolean }) {
  const t = useTranslations("mcpPanel");
  const sizeCls = compact ? "h-5 shrink-0 gap-1 whitespace-nowrap px-2 text-xs font-medium" : "gap-1";
  return (
    <Badge variant="outline" className={cn(sizeCls, TONE_SUCCESS)} title={t("provenancePlatformVettedTooltip")}>
      <ShieldCheck size={compact ? 10 : 12} aria-hidden="true" /> {compact ? "Verified" : t("provenancePlatformVetted")}
    </Badge>
  );
}

// Decrypt-failure visibility (McpServerRegistrationResponse.credentialsCorrupted,
// 2026-08-05): the stored env ciphertext exists but couldn't be decrypted/
// parsed on the last connect/test attempt - deliberately NOT rendered as a
// generic connection-error red block, since "your credentials are broken"
// and "you never entered any" call for two different next actions. Offers a
// direct path back to re-entering them (opens the same warn-before-enable
// credential form every Enable click already uses).
export function CredentialsCorruptedAlert({ onReEnter }: { onReEnter?: () => void }) {
  const t = useTranslations("mcpPanel");
  return (
    <Alert variant="warning">
      <KeyRound aria-hidden="true" />
      <AlertTitle>{t("credentialsCorruptedAlertTitle")}</AlertTitle>
      <AlertDescription>
        {t("credentialsCorruptedAlertBody")}
        {onReEnter && (
          <Button type="button" size="sm" className="mt-2 w-fit" onClick={onReEnter}>
            {t("reEnterCredentialsButton")}
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}

// MCP-STDIO-SANDBOXING review-state explanation - PENDING reads as a normal,
// expected, neutral/informational step for a brand-new self-service STDIO
// server (not an error), REJECTED surfaces the reviewer's reason (when
// given). The backend has no ETA field on this DTO today, so this
// deliberately does not fabricate a "usually within X hours" claim.
export function ReviewStatusAlert({ server }: { server: any }) {
  const t = useTranslations("mcpPanel");
  const status = reviewStatusOf(server);
  if (status === "PENDING") {
    return (
      <Alert variant="info">
        <Clock aria-hidden="true" />
        <AlertTitle>{t("reviewPendingAlertTitle")}</AlertTitle>
        <AlertDescription>{t("reviewPendingAlertBody")}</AlertDescription>
      </Alert>
    );
  }
  if (status === "REJECTED") {
    return (
      <Alert variant="destructive">
        <AlertTriangle aria-hidden="true" />
        <AlertTitle>{t("reviewRejectedAlertTitle")}</AlertTitle>
        <AlertDescription>
          <p>{server?.reviewRejectionReason || t("reviewRejectedAlertNoReason")}</p>
        </AlertDescription>
      </Alert>
    );
  }
  return null;
}

// Wires MCP_CREDENTIAL_GUIDES into the actual first-contact "type your
// credential here" forms (CatalogConnectForm / EnableConfirmCard) rather
// than only the after-the-fact editor (tools/credentials/page.tsx) - same
// fuzzy name match (credentialGuideFor, lib/credentialGuides.ts) so a given
// server resolves to the identical guide wherever its credential form
// appears. Renders nothing when no guide matches this server/entry name.
// True when the field itself should be masked - an explicit `secret` wins,
// otherwise fall back to the same name-sniffing heuristic the freeform rows
// below already use, so a documented field without an explicit `secret`
// flag still masks sensibly.
export function isSecretCredentialField(field: McpCredentialGuideField): boolean {
  return field.secret ?? /token|key|secret|auth|password/i.test(field.name);
}

export function McpCredentialGuideAlert({ name }: { name: string | null | undefined }) {
  const guide = credentialGuideFor(name);
  if (!guide) return null;
  return (
    <Alert variant="info">
      <HelpCircle aria-hidden="true" />
      <AlertTitle>{guide.title}</AlertTitle>
      <AlertDescription>
        <ul className="ms-4 list-disc space-y-1">
          {guide.steps.map((step: string, i: number) => (
            <li key={i}>{step}</li>
          ))}
        </ul>
        {guide.href && (
          <a
            href={guide.href}
            target="_blank"
            rel="noopener noreferrer"
            className="mt-2 inline-flex items-center gap-1 font-medium text-[var(--info-text)] hover:underline"
          >
            {guide.hrefLabel || "Learn more"} <ExternalLink size={12} aria-hidden="true" />
          </a>
        )}
      </AlertDescription>
    </Alert>
  );
}

// Readiness badge - inspector (full) and grid-card (compact) variants. Color
// is a 3-way READY/ACKNOWLEDGED (success) vs FAILED (error) vs UNTESTED
// (warning) map; the wording itself only distinguishes ready/acknowledged
// vs "not verified" either way - FAILED and UNTESTED share copy, just not
// color, exactly as before this restyle.
export function ReadinessBadge({ server, compact }: { server: any; compact?: boolean }) {
    const tHC = useTranslations("hcComponentsMcpPanelParts");
  const status = usageStatusOf(server);
  if (!status) return null;
  const ready = status === "READY";
  const acked = status === "ACKNOWLEDGED";
  const tone = ready || acked ? "success" : status === "FAILED" ? "error" : "warning";
  const ariaLabel = ready || acked ? "Tested and ready" : "Not yet verified";
  const toneClass =
    tone === "success"
      ? "text-[var(--state-success)]"
      : tone === "error"
        ? "text-destructive"
        : "text-[var(--state-warning)]";

  if (compact) {
    return (
      <Badge
        variant="outline"
        className={cn(
          "ms-1.5 h-4 gap-0.5 px-1.5 text-xs font-semibold",
          tone === "success"
            ? TONE_SUCCESS
            : tone === "error"
              ? "border-destructive/30 bg-destructive/10 text-destructive"
              : TONE_WARNING
        )}
      >
        <span className="sr-only">{ariaLabel} — </span>
        {ready ? (
          <>
            <CheckCircle2 aria-hidden="true" /> Ready
          </>
        ) : acked ? (
          "Trusted"
        ) : (
          "Not verified"
        )}
      </Badge>
    );
  }
  return (
    <p className={cn("mt-1 flex items-center gap-1 text-xs font-semibold", toneClass)}>
      {ready ? (
        <>
          <CheckCircle2 size={12} aria-hidden="true" /> {tHC("hcComponentsMcpPanelParts.s1")}
        </>
      ) : acked ? (
        "Trusted — manually acknowledged; agents can use this server"
      ) : (
        "Not yet verified — agents won't use this server until it passes a connection test (or you mark it trusted)"
      )}
    </p>
  );
}

// The prominent amber "what to actually do about it" box - the hint is the
// product of the whole diagnostic flow, so it gets the loudest rendering.
export function HintBox({ hint }: { hint: string }) {
  return (
    <Alert variant="warning" className="mt-2 px-3 py-2">
      <AlertDescription className="text-xs font-medium">{hint}</AlertDescription>
    </Alert>
  );
}
