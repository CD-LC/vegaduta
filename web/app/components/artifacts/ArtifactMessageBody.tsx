"use client";

// Renders one assistant message when the artifacts pane is on: ordinary text
// segments go through the caller's renderer (ChatPanel passes its MediaCard, so
// links/media behave exactly as before), and each `artifact:` fenced block
// becomes a compact card that opens the pane. The artifact body itself is NEVER
// rendered inline in the transcript - only its title, kind and version.

import { Fragment, type ReactNode } from "react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { ArtifactKindIcon } from "./ArtifactPane";
import { parseArtifactSegments, mightContainArtifact } from "../../lib/artifacts/artifactParser";
import { blockKey, type ArtifactRegistry } from "../../lib/artifacts/artifactVersions";
import { mightContainCandidate, splitCandidates, type ArtifactCandidate } from "../../lib/artifacts/artifactCandidates";

export default function ArtifactMessageBody({
  text,
  messageIndex,
  registry,
  streaming,
  activeId,
  onOpen,
  onPromote,
  renderText,
}: {
  text: string;
  messageIndex: number;
  registry: ArtifactRegistry;
  /** This message is the one currently streaming in. */
  streaming: boolean;
  /** Artifact id currently open in the pane (highlights its card). */
  activeId: string | null;
  onOpen: (id: string, versionNumber: number) => void;
  /**
   * Offer "Open as artifact" under a complete plain ```html / ```svg block (a
   * model that ignored the `artifact:` header). Omitted while the message is
   * still streaming. Clicking never renders or runs anything by itself: the
   * block becomes an ordinary artifact, behind the same sandbox and "Run
   * preview" gate.
   */
  onPromote?: (candidate: ArtifactCandidate) => void;
  renderText: (text: string) => ReactNode;
}) {
  const t = useTranslations("artifacts");

  // Ordinary text, with an offer under each candidate block it contains.
  const renderWithOffers = (plain: string, keyPrefix: string): ReactNode => {
    if (!onPromote || !mightContainCandidate(plain)) return renderText(plain);
    const pieces = splitCandidates(plain);
    if (!pieces.some((p) => p.type === "candidate")) return renderText(plain);
    return (
      <div className="space-y-2">
        {pieces.map((p, pi) => {
          if (p.type === "text") {
            return p.text.trim() === "" ? null : <Fragment key={`${keyPrefix}p${pi}`}>{renderText(p.text)}</Fragment>;
          }
          const kindLabel = t(p.candidate.kind === "svg" ? "kindSvg" : "kindHtml");
          return (
            <Fragment key={`${keyPrefix}p${pi}`}>
              {renderText(p.text)}
              <button
                type="button"
                onClick={() => onPromote(p.candidate)}
                aria-label={t("candidateOpenLabel", { kind: kindLabel })}
                data-testid="artifact-candidate-open"
                className="inline-flex max-w-full items-center gap-2 rounded-lg border border-border bg-card px-3 py-1.5 text-left text-xs font-medium text-foreground transition-colors hover:bg-accent"
              >
                <ArtifactKindIcon kind={p.candidate.kind} className="size-4 shrink-0 text-muted-foreground" />
                <span className="min-w-0 truncate">{t("candidateOpen")}</span>
              </button>
            </Fragment>
          );
        })}
      </div>
    );
  };

  if (!mightContainArtifact(text)) return <>{renderWithOffers(text, "")}</>;

  const segments = parseArtifactSegments(text);
  return (
    <div className="space-y-2">
      {segments.map((seg, si) => {
        if (seg.kind === "text") return <Fragment key={`t${si}`}>{renderWithOffers(seg.text, `t${si}`)}</Fragment>;
        const a = seg.artifact;
        const ref = registry.byBlock[blockKey(messageIndex, seg.blockIndex)];
        if (!ref) {
          return (
            <p key={`a${si}`} className="rounded-lg border border-dashed border-border px-3 py-2 text-xs text-muted-foreground">
              {t("chipUnavailable", { title: a.title })}
            </p>
          );
        }
        const status = !a.complete ? (streaming ? t("chipDrafting") : t("chipIncomplete")) : null;
        return (
          <button
            key={`a${si}`}
            type="button"
            onClick={() => onOpen(ref.id, ref.number)}
            aria-label={t("chipOpen", { title: a.title })}
            data-testid="artifact-chip"
            className={cn(
              "flex w-full items-center gap-3 rounded-xl border bg-card px-3 py-2.5 text-left transition-colors hover:bg-accent",
              activeId === ref.id ? "border-primary/60" : "border-border",
            )}
          >
            <ArtifactKindIcon kind={a.kind} className="size-5 shrink-0 text-muted-foreground" />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm font-medium text-foreground">{a.title}</span>
              <span className="block truncate text-xs text-muted-foreground">
                {t("versionLabel", { n: ref.number })}
                {status ? ` · ${status}` : ""}
              </span>
            </span>
          </button>
        );
      })}
    </div>
  );
}
