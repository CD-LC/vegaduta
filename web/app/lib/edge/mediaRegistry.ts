// In-page registry for media the edge generators produce.
//
// Local generation tools (local_generate_image, local_generate_audio) produce
// binary artifacts, and a LocalTool's result is a STRING handed back to the
// model - the model needs the words ("generated a 512x512 image"), while the
// PERSON needs the bytes. This module is the bridge: each generated artifact
// is stored here under an id, and surfaces (the Edge console's media list, a
// chat renderer that learns to sniff the id) can retrieve the blob by id.
//
// Scope: session-only, in-memory object URLs. Nothing is written to disk and
// nothing survives a reload - the file tools deliberately cannot write, and a
// generated PNG is a download click, not a persistence problem. The cap is a
// simple LRU over the object URLs so a runaway tool loop cannot leak blobs.

export type MediaArtifactKind = "image" | "audio";

export interface MediaArtifact {
  id: string;
  kind: MediaArtifactKind;
  mime: string;
  /** Object URL for direct rendering (<img src>, <audio src>, download href). */
  url: string;
  /** Byte size of the underlying blob. */
  byteSize: number;
  /** The prompt/description the generator was given. */
  description: string;
  /** Generation seed, when the generator accepted one (image). */
  seed?: number;
  width?: number;
  height?: number;
  /** Duration in seconds, when the generator produced timed media (audio). */
  durationSeconds?: number;
  createdAt: number;
}

const MAX_ARTIFACTS = 24;

let nextId = 1;
const artifacts = new Map<string, { artifact: MediaArtifact; blob: Blob }>();

export function addMediaArtifact(input: {
  kind: MediaArtifactKind;
  mime: string;
  blob: Blob;
  description: string;
  seed?: number;
  width?: number;
  height?: number;
  durationSeconds?: number;
}): MediaArtifact {
  const id = `edge-media-${nextId++}`;
  const url = URL.createObjectURL(input.blob);
  const artifact: MediaArtifact = {
    id,
    kind: input.kind,
    mime: input.mime,
    url,
    byteSize: input.blob.size,
    description: input.description,
    seed: input.seed,
    width: input.width,
    height: input.height,
    durationSeconds: input.durationSeconds,
    createdAt: Date.now(),
  };
  artifacts.set(id, { artifact, blob: input.blob });
  while (artifacts.size > MAX_ARTIFACTS) {
    const oldest = artifacts.keys().next().value;
    if (!oldest) break;
    removeMediaArtifact(oldest);
  }
  return artifact;
}

export function getMediaArtifact(id: string): MediaArtifact | null {
  return artifacts.get(id)?.artifact ?? null;
}

export function getMediaArtifactBlob(id: string): Blob | null {
  return artifacts.get(id)?.blob ?? null;
}

export function listMediaArtifacts(): MediaArtifact[] {
  return [...artifacts.values()].map((e) => e.artifact).sort((a, b) => b.createdAt - a.createdAt);
}

export function removeMediaArtifact(id: string): void {
  const entry = artifacts.get(id);
  if (!entry) return;
  artifacts.delete(id);
  try {
    URL.revokeObjectURL(entry.artifact.url);
  } catch {
    // Best-effort.
  }
}

export function clearMediaArtifacts(): void {
  for (const id of artifacts.keys()) removeMediaArtifact(id);
}
