// Sandbox lifecycle: resume, create and reset a demo session. Never rejects; see ../demoClient.ts.

import { API_BASE, readJson, storeToken, timeoutSignal } from "./demoHttp";

export type DemoSession = {
  demoToken: string | null;
  tenantId: string;
  workspaceName: string;
  agentId: string | null;
  agentName: string | null;
  createdAt: string;
  expiresAt: string;
  daysRemaining: number;
  messagesUsed: number;
  messageLimit: number;
  // AI image generation. Reported on the session (not only on the image
  // response) so a RESUMED sandbox knows its real remaining allowance on
  // first paint instead of advertising images it has already spent.
  imagesUsed: number;
  imageLimit: number;
  // False when a platform admin has switched images off (demo.image.enabled).
  // The affordance is then hidden entirely - offering a button that can only
  // decline is worse than not offering one.
  imageEnabled: boolean;
  expired: boolean;
  // The 24h-editable / view-only split (demo.editable-hours, 2026-07-28).
  // True when the sandbox is past its hands-on window but not yet expired:
  // the transcript stays viewable (GET /api/demo/history) while chat/image/
  // video refuse softly. Optional so an older backend degrades to today's
  // always-editable behaviour on its own.
  readOnly?: boolean;
  // ISO instant the editable phase ends — lets the UI show a real countdown
  // instead of discovering the flip by having a message refused.
  editableUntil?: string | null;
  // Echo of the persona chip picked before boot ("kirana" | "clinic" |
  // "tuition" | "restaurant" | "salon"), so the agent's first reply is already
  // about the visitor's business. Absent/null when they chose "Just exploring"
  // or on an older backend that predates the field.
  persona?: string | null;
};

// Flat optional-field shape rather than a discriminated union — same
// strict:false compiler reasoning as CreateResult below.
export type FetchSessionResult = {
  /** The live (possibly expired-but-renderable) session, when the server answered 200. */
  session?: DemoSession | null;
  /**
   * True ONLY when the server definitively said this token is no good (4xx —
   * expired-and-purged, or a different environment). That is the one case
   * where discarding the stored token is correct. A network failure or a 5xx
   * leaves `stale` false: the sandbox may be perfectly fine, and wiping the
   * only credential to it over a transient blip would strand the visitor —
   * their next create attempt then trips the per-IP live-sandbox cap for a
   * sandbox they can no longer reach.
   */
  stale?: boolean;
};

/** Resumes an existing sandbox. See FetchSessionResult for the stale-vs-unreachable split. */
export async function fetchSession(token: string): Promise<FetchSessionResult> {
  try {
    const res = await fetch(`${API_BASE}/api/demo/session`, {
      headers: { "X-Demo-Token": token },
      signal: timeoutSignal(15_000),
    });
    if (res.ok) {
      const session = (await readJson(res)) as DemoSession | null;
      return session ? { session } : { stale: false };
    }
    // 404 (unknown/purged token) and any other 4xx: the server understood us
    // and said no — the token is genuinely dead. 5xx means the server is
    // having a moment; the token may still be fine.
    return { stale: res.status >= 400 && res.status < 500 };
  } catch {
    return { stale: false };
  }
}

// Deliberately a flat optional-field shape rather than a discriminated union
// on `ok`: this project's tsconfig runs with `strict: false`, and without
// strictNullChecks TypeScript will not narrow a `{ok:true}|{ok:false}` union
// from an `if (!result.ok)` guard - it reports the error branch's fields as
// missing. A flat type is the honest fit for the compiler settings that
// actually apply here.
export type CreateResult = {
  ok: boolean;
  session?: DemoSession;
  message?: string;
};

export async function createSession(input?: {
  email?: string;
  businessName?: string;
  // One of "kirana" | "clinic" | "tuition" | "restaurant" | "salon". Optional;
  // omitted entirely for "Just exploring". An older backend simply ignores the
  // extra field, so this degrades to today's generic sandbox on its own.
  persona?: string;
  // The Agent Studio builds agents from the visitor's own briefing, so it asks
  // for a sandbox with no seeded starter agent. An older backend ignores it.
  blank?: boolean;
}): Promise<CreateResult> {
  try {
    const res = await fetch(`${API_BASE}/api/demo/session`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(input ?? {}),
      // Generous: provisioning genuinely runs ~90 tenant migrations. But not
      // unbounded — without a ceiling a stalled connection means the boot
      // screen narrates forever with nothing coming.
      signal: timeoutSignal(45_000),
    });
    const body = await readJson(res);
    if (!res.ok) {
      return {
        ok: false,
        message: body?.error || "We couldn't start a sandbox just now. Please try again in a moment.",
      };
    }
    const session = body as DemoSession;
    if (session?.demoToken) storeToken(session.demoToken);
    return { ok: true, session };
  } catch {
    // Network-level failure (core down, DNS, CORS). Still a sentence, never a
    // stack trace or a red banner.
    return {
      ok: false,
      message: "We couldn't reach the sandbox just now. Please try again in a moment.",
    };
  }
}

/**
 * POST /api/demo/session/reset — deletes the current sandbox and returns a
 * freshly provisioned one in the same call (DemoSessionController). Same
 * contract as createSession: never rejects, resolves every failure into a
 * soft human sentence. On success the NEW token is stored — the old sandbox
 * is gone server-side, so keeping its token would only resurrect it as a
 * stale-resume failure on the next visit.
 */
export async function resetSession(
  token: string,
  input?: {
    email?: string;
    businessName?: string;
    persona?: string;
    blank?: boolean;
  }
): Promise<CreateResult> {
  try {
    const res = await fetch(`${API_BASE}/api/demo/session/reset`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Demo-Token": token },
      body: JSON.stringify(input ?? {}),
      // Provisioning is the same ~90-migration tenant build as createSession,
      // so it gets the same generous-but-bounded ceiling.
      signal: timeoutSignal(45_000),
    });
    const body = await readJson(res);
    if (!res.ok) {
      return {
        ok: false,
        message: body?.error || "We couldn't reset your sandbox just now. Please try again in a moment.",
      };
    }
    const session = body as DemoSession;
    if (session?.demoToken) storeToken(session.demoToken);
    return { ok: true, session };
  } catch {
    return {
      ok: false,
      message: "We couldn't reach the sandbox just now. Please try again in a moment.",
    };
  }
}
