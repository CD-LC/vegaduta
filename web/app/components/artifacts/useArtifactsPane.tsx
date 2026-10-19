"use client";

// One hook that gives a chat surface the whole artifacts feature, so the host
// (ChatPanel today) needs only: the hook call, a wrapper element, and one
// render-branch for assistant text. With the feature flag off the hook is
// inert: no parsing, `pane` is null, `renderAssistantText` returns the host's
// own rendering - the chat DOM is identical to before.

import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { useTranslations } from "../../lib/i18n";
import { extractUiSpec } from "../generative-ui/extractUiSpec";
import ArtifactPane from "./ArtifactPane";
import ArtifactMessageBody from "./ArtifactMessageBody";
import { mightContainArtifact } from "../../lib/artifacts/artifactParser";
import { mightContainCandidate, promoteCandidates, type ArtifactCandidate } from "../../lib/artifacts/artifactCandidates";
import { artifactIdForTitle } from "../../lib/artifacts/artifactTypes";
import {
  buildArtifactRegistry,
  emptyRegistry,
  findEntry,
  latestVersion,
  type ArtifactEntry,
  type ArtifactRegistry,
  type ArtifactVersion,
  type RegistryMessage,
} from "../../lib/artifacts/artifactVersions";
import { ARTIFACTS_BUILD_FLAG, readArtifactsOverride } from "../../lib/artifacts/artifactsFlag";
import { useThrottledSnapshot } from "../../lib/artifacts/useThrottledSnapshot";

/** The text a message RENDERS (json_ui block already removed), so block indices match the message body. */
function renderedTextOf(m: RegistryMessage): string {
  const c = typeof m.content === "string" ? m.content : "";
  if (!mightContainArtifact(c)) return c;
  const ui = extractUiSpec(c);
  return ui ? ui.text : c;
}

export interface ArtifactsPaneApi {
  enabled: boolean;
  /** Render one assistant message's text; falls through to `renderPlain` when the feature is off or the text has no artifact. */
  renderAssistantText: (text: string, messageIndex: number, isStreamingMessage: boolean, renderPlain: (t: string) => ReactNode) => ReactNode;
  /** Side pane element for the host to place beside the chat, or null. */
  pane: ReactNode;
  /** Close the pane (new session / agent switch). */
  reset: () => void;
}

export function useArtifactsPane({
  turns,
  sending,
  onAskPrefill,
}: {
  turns: readonly RegistryMessage[];
  sending: boolean;
  /** Put text in the host's composer (never sends it). */
  onAskPrefill: (text: string) => void;
}): ArtifactsPaneApi {
  const t = useTranslations("artifacts");
  const [enabled, setEnabled] = useState<boolean>(ARTIFACTS_BUILD_FLAG);
  const [open, setOpen] = useState<{ id: string; version: number | null } | null>(null);
  const [collapsed, setCollapsed] = useState(false);
  // Plain ```html / ```svg blocks the person chose to "Open as artifact" (bug 9, see artifactCandidates.ts),
  // as `${messageIndex}:${contentFingerprint}`. In memory only: like the registry, nothing is stored.
  const [promoted, setPromoted] = useState<ReadonlySet<string>>(() => new Set<string>());
  const promoteText = useCallback(
    (text: string, messageIndex: number, set: ReadonlySet<string> = promoted): string =>
      set.size === 0 ? text : promoteCandidates(text, (fp) => set.has(`${messageIndex}:${fp}`)),
    [promoted],
  );

  // The per-viewer override lives in localStorage; read it after mount (SSR-safe, no hydration mismatch).
  useEffect(() => {
    if (!ARTIFACTS_BUILD_FLAG && readArtifactsOverride()) setEnabled(true);
  }, []);

  // While a reply streams, the registry and the streaming message body both work from a snapshot that
  // refreshes at most every STREAM_SNAPSHOT_MS, instead of re-splitting a growing artifact per token.
  const viewTurns = useThrottledSnapshot(turns, enabled && sending);
  const registry: ArtifactRegistry = useMemo(
    () => (enabled ? buildArtifactRegistry(viewTurns, (m, mi) => promoteText(renderedTextOf(m), mi)) : emptyRegistry()),
    [enabled, viewTurns, promoteText],
  );

  // A new session / agent switch empties the transcript, which empties the registry: drop the stale open
  // selection too, so an unrelated artifact that later reuses the title does not pop the pane open.
  useEffect(() => {
    if (open && !findEntry(registry, open.id)) setOpen(null);
  }, [registry, open]);

  const openArtifact = useCallback(
    (id: string, number: number) => {
      const entry = findEntry(registry, id);
      const latest = entry ? latestVersion(entry) : null;
      setOpen({ id, version: latest && latest.number === number ? null : number });
      setCollapsed(false);
    },
    [registry],
  );

  const reset = useCallback(() => {
    setOpen(null);
    setCollapsed(false);
    setPromoted(new Set<string>());
  }, []);

  // "Open as artifact": mark the block promoted and open the pane on exactly the version it becomes (an
  // artifact with the same title may already exist, in which case this block is one of its versions).
  const promoteCandidate = useCallback(
    (messageIndex: number, candidate: ArtifactCandidate) => {
      const next = new Set(promoted);
      next.add(`${messageIndex}:${candidate.fingerprint}`);
      setPromoted(next);
      const id = artifactIdForTitle(candidate.title);
      const nextRegistry = buildArtifactRegistry(viewTurns, (m, mi) => promoteText(renderedTextOf(m), mi, next));
      const entry = findEntry(nextRegistry, id);
      const mine = entry?.versions.find((v) => v.messageIndex === messageIndex && v.content === candidate.content);
      const latest = entry ? latestVersion(entry) : null;
      setOpen({ id, version: mine && latest && mine.number !== latest.number ? mine.number : null });
      setCollapsed(false);
    },
    [promoted, promoteText, viewTurns],
  );

  const askToChange = useCallback(
    (entry: ArtifactEntry, version: ArtifactVersion) => {
      onAskPrefill(t("askPrefill", { title: entry.title, n: version.number }));
      // On a phone the pane is a full-screen sheet over the composer: close it so the prefilled box is visible.
      try {
        if (typeof window !== "undefined" && window.matchMedia("(max-width: 767px)").matches) setOpen(null);
      } catch {
        /* matchMedia unavailable: leave the pane as is */
      }
    },
    [onAskPrefill, t],
  );

  const renderAssistantText = useCallback<ArtifactsPaneApi["renderAssistantText"]>(
    (text, messageIndex, isStreamingMessage, renderPlain) => {
      if (!enabled) return renderPlain(text);
      // A finished message may also carry a plain ```html / ```svg block worth offering the pane for.
      const offers = !isStreamingMessage && mightContainCandidate(text);
      if (!mightContainArtifact(text) && !offers) return renderPlain(text);
      // The streaming message renders from the same snapshot the registry was built from, so a block is
      // never looked up in a registry that is older than the text being parsed.
      let shown = offers ? promoteText(text, messageIndex) : text;
      if (isStreamingMessage) {
        const snapTurn = viewTurns[messageIndex];
        if (!snapTurn) return "…"; // first tokens of a new message: the snapshot has not caught up yet
        shown = renderedTextOf(snapTurn);
        if (!mightContainArtifact(shown)) return renderPlain(shown);
      }
      return (
        <ArtifactMessageBody
          text={shown}
          messageIndex={messageIndex}
          registry={registry}
          streaming={isStreamingMessage}
          activeId={open ? open.id : null}
          onOpen={openArtifact}
          onPromote={offers ? (candidate) => promoteCandidate(messageIndex, candidate) : undefined}
          renderText={renderPlain}
        />
      );
    },
    [enabled, registry, viewTurns, open, openArtifact, promoteText, promoteCandidate],
  );

  const entry = enabled && open ? findEntry(registry, open.id) : null;
  const pane = entry ? (
    <ArtifactPane
      entry={entry}
      versionNumber={open ? open.version : null}
      streaming={sending}
      collapsed={collapsed}
      onSelectVersion={(n) => setOpen((o) => (o ? { id: o.id, version: n } : o))}
      onToggleCollapsed={() => setCollapsed((c) => !c)}
      onClose={() => setOpen(null)}
      onAskToChange={askToChange}
    />
  ) : null;

  return { enabled, renderAssistantText, pane, reset };
}

/**
 * Wrapper that places the pane beside the chat. With the feature off it renders
 * its children only (identical DOM to before). With it on, the wrapper stays
 * mounted whether or not a pane is open, so opening/closing the pane never
 * remounts the chat (which would drop focus and scroll position).
 */
export function ArtifactLayout({ enabled, pane, children }: { enabled: boolean; pane: ReactNode; children: ReactNode }) {
  if (!enabled) return <>{children}</>;
  return (
    <div className="flex min-w-0 flex-col gap-4 md:flex-row md:items-start" data-testid="artifact-layout">
      <div className="min-w-0 flex-1">{children}</div>
      {pane}
    </div>
  );
}
