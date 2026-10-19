// Artifacts pane - turning a transcript into artifacts with version history.
//
// Pure and derived: nothing here is stored. The registry is rebuilt from the
// message text on every change (cheap - messages without an `artifact:` fence
// are skipped by one regex), so there is no server state to keep in sync and
// no way for the registry to disagree with what the chat shows. Honest
// consequence: artifacts live exactly as long as the transcript that carries
// them; nothing is persisted server-side by this lane.
//
// Versioning rule: the same title (case/spacing/Unicode-form insensitive, see
// artifactIdForTitle) is the same artifact. Each later block with that title
// APPENDS a version, in transcript order. A block whose kind, language and
// content are identical to the artifact's latest version adds nothing (a
// model re-sending an unchanged file is not a revision).

import { parseArtifactSegments, mightContainArtifact } from "./artifactParser";
import { artifactIdForTitle, type ArtifactKind } from "./artifactTypes";

export interface ArtifactVersion {
  /** 1-based, stable for the life of the transcript. */
  number: number;
  kind: ArtifactKind;
  lang: string | null;
  /** Title as written in THIS version's fence (the entry carries the latest). */
  title: string;
  content: string;
  complete: boolean;
  truncated: boolean;
  messageIndex: number;
  blockIndex: number;
}

export interface ArtifactEntry {
  id: string;
  /** Title of the latest version. */
  title: string;
  versions: ArtifactVersion[];
}

export interface ArtifactRegistry {
  /** In order of first appearance. */
  entries: ArtifactEntry[];
  /** `${messageIndex}:${blockIndex}` -> which artifact/version a block in the transcript denotes. */
  byBlock: Record<string, { id: string; number: number }>;
}

export const MAX_ARTIFACTS = 50;
export const MAX_VERSIONS_PER_ARTIFACT = 50;

export interface RegistryMessage {
  role?: string;
  content?: unknown;
}

export const blockKey = (messageIndex: number, blockIndex: number): string => `${messageIndex}:${blockIndex}`;

export function emptyRegistry(): ArtifactRegistry {
  return { entries: [], byBlock: {} };
}

/**
 * Build the registry from a transcript. Only ASSISTANT messages produce
 * artifacts (a user pasting an `artifact:` fence must not create a pane entry
 * that looks agent-authored). `textOf` lets the caller feed the same text it
 * renders (for example with a json_ui block already stripped) so block indices
 * line up with what the message body parses.
 */
export function buildArtifactRegistry(
  messages: readonly RegistryMessage[] | null | undefined,
  textOf?: (m: RegistryMessage, index: number) => string,
): ArtifactRegistry {
  const reg = emptyRegistry();
  if (!Array.isArray(messages)) return reg;
  const byId = new Map<string, ArtifactEntry>();

  for (let mi = 0; mi < messages.length; mi++) {
    const m = messages[mi];
    if (!m || m.role !== "assistant") continue;
    const text = textOf ? textOf(m, mi) : typeof m.content === "string" ? m.content : "";
    if (!mightContainArtifact(text)) continue;

    for (const seg of parseArtifactSegments(text)) {
      if (seg.kind !== "artifact") continue;
      const a = seg.artifact;
      const id = artifactIdForTitle(a.title);
      let entry = byId.get(id);
      if (!entry) {
        if (byId.size >= MAX_ARTIFACTS) continue; // over the cap: no entry, so the block has no byBlock key and the chat shows an inert card
        entry = { id, title: a.title, versions: [] };
        byId.set(id, entry);
        reg.entries.push(entry);
      }
      const last = entry.versions[entry.versions.length - 1];
      const unchanged =
        last !== undefined &&
        last.kind === a.kind &&
        last.lang === a.lang &&
        last.content === a.content &&
        last.complete === a.complete &&
        last.truncated === a.truncated;
      if (unchanged) {
        reg.byBlock[blockKey(mi, seg.blockIndex)] = { id, number: last.number };
        continue;
      }
      const number = last ? last.number + 1 : 1;
      entry.versions.push({
        number,
        kind: a.kind,
        lang: a.lang,
        title: a.title,
        content: a.content,
        complete: a.complete,
        truncated: a.truncated,
        messageIndex: mi,
        blockIndex: seg.blockIndex,
      });
      if (entry.versions.length > MAX_VERSIONS_PER_ARTIFACT) entry.versions.shift();
      entry.title = a.title;
      reg.byBlock[blockKey(mi, seg.blockIndex)] = { id, number };
    }
  }
  return reg;
}

export function findEntry(reg: ArtifactRegistry, id: string | null | undefined): ArtifactEntry | null {
  if (!id) return null;
  return reg.entries.find((e) => e.id === id) ?? null;
}

export function latestVersion(entry: ArtifactEntry): ArtifactVersion | null {
  return entry.versions.length > 0 ? entry.versions[entry.versions.length - 1] : null;
}

/** The requested version, or the latest when the number is null or no longer exists (e.g. aged out of the cap). */
export function resolveVersion(entry: ArtifactEntry, number: number | null | undefined): ArtifactVersion | null {
  if (number != null) {
    const hit = entry.versions.find((v) => v.number === number);
    if (hit) return hit;
  }
  return latestVersion(entry);
}

/** Previous / next version numbers around `number`, for the version switcher. Null at either end. */
export function neighbours(entry: ArtifactEntry, number: number): { prev: number | null; next: number | null } {
  const idx = entry.versions.findIndex((v) => v.number === number);
  if (idx < 0) return { prev: null, next: null };
  return {
    prev: idx > 0 ? entry.versions[idx - 1].number : null,
    next: idx < entry.versions.length - 1 ? entry.versions[idx + 1].number : null,
  };
}
