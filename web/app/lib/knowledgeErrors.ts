// Reads the structured facts out of the knowledge API's 4xx details so the UI
// can show a short localized sentence instead of the raw server text (which
// quotes document UUIDs and API paths). Core may later answer with a stable
// problem type; until then these match the current wording and return null
// for anything else, so the caller falls back to its generic message.

type StatusError = { status?: number; message?: string } | null | undefined;

/** 409 on collection delete: "N document(s) are still in it ..." -> N. */
export function collectionNotEmptyDocumentCount(err: unknown): number | null {
  const e = err as StatusError;
  if (!e || e.status !== 409) return null;
  const m = /(\d+)\s+document\(s\)/.exec(e.message ?? "");
  return m ? Number(m[1]) : null;
}

/** 400/409 on upload: 'already in this collection as "name" (document ...' -> name. */
export function duplicateUploadFilename(err: unknown): string | null {
  const e = err as StatusError;
  if (!e || (e.status !== 400 && e.status !== 409)) return null;
  const m = /already in this collection as "([^"]+)"/.exec(e.message ?? "");
  return m ? m[1] : null;
}
