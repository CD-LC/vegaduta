import { apiFetch } from "./core";
import { VIEW_AS_HEADER, classifyViewAsStatus, normalizeSession, type ViewAsSession } from "../viewAs";

export type ViewAsCheck =
  | { kind: "ok"; session: ViewAsSession }
  | { kind: "invalid"; status: number }
  | { kind: "error"; status: number | null };

/**
 * GET /api/view-as/session with the given grant (the admin's own bearer token
 * rides along via apiFetch). Never toasts: the caller owns the wording. A
 * network failure is "error" (retry later), never "invalid" - a blip must not
 * end someone's view-as session.
 */
export async function checkViewAsSession(grant: string): Promise<ViewAsCheck> {
  let res: Response;
  try {
    res = await apiFetch(`/api/view-as/session`, { headers: { [VIEW_AS_HEADER]: grant }, skipGetCache: true });
  } catch {
    return { kind: "error", status: null };
  }
  const verdict = classifyViewAsStatus(res.status);
  if (verdict === "invalid") return { kind: "invalid", status: res.status };
  if (verdict === "error") return { kind: "error", status: res.status };
  let body: any = null;
  try {
    body = await res.json();
  } catch {
    body = null;
  }
  const session = normalizeSession(body);
  return session ? { kind: "ok", session } : { kind: "error", status: res.status };
}
