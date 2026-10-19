import { runModel } from "./engine";
import { API_BASE } from "../../lib/api";

// ---------------------------------------------------------------------------
// Published-app bridge (vd.call / vd.ai.chat / vd.auth.*)
//
// The app is sandboxed, so the HOST page makes the real calls. End-user
// credentials are entered into the host's own sign-in overlay - never into
// the app - and the issued token is stored on THIS origin keyed by app slug
// (vd.appauth:<slug>) and attached as a Bearer header the app never sees.
// The same protocol lives in public/run-app.html - keep them in step.

export interface AppAuthStore {
  token: string;
  user: { id?: string; email?: string; name?: string } | null;
}

export const appAuthKey = (slug: string) => `vd.appauth:${slug}`;

/** Server UserView uses `displayName`; the in-app vd.auth.* contract uses `name`. */
export function normUser(u: unknown): AppAuthStore["user"] {
  if (!u || typeof u !== "object") return null;
  const o = u as Record<string, unknown>;
  return {
    id: typeof o.id === "string" ? o.id : undefined,
    email: typeof o.email === "string" ? o.email : undefined,
    name: (typeof o.name === "string" && o.name) || (typeof o.displayName === "string" ? o.displayName : undefined),
  };
}

export function readAppAuth(slug: string): AppAuthStore | null {
  try {
    const raw = localStorage.getItem(appAuthKey(slug));
    const v = raw ? JSON.parse(raw) : null;
    return v && typeof v.token === "string" && v.token ? v : null;
  } catch {
    return null;
  }
}

export function writeAppAuth(slug: string, v: AppAuthStore | null): void {
  try {
    if (v) localStorage.setItem(appAuthKey(slug), JSON.stringify(v));
    else localStorage.removeItem(appAuthKey(slug));
  } catch {
    /* private mode */
  }
}

export async function appApi(slug: string, path: string, body?: unknown, token?: string | null) {
  const res = await fetch(`${API_BASE}/api/public/apps/${encodeURIComponent(slug)}${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const j = await res.json().catch((): null => null);
  return { status: res.status, ok: res.ok, body: j };
}

/** A call/ai turn that can pause for the sign-in overlay once: on a
 * sign_in_required 401 it asks the host to authenticate the end user, then
 * retries with the fresh token. */
export async function callPublished(
  slug: string,
  path: string,
  body: unknown,
  requestSignIn: (slug: string) => Promise<AppAuthStore["user"]>
): Promise<{ ok: boolean; text?: string; user?: AppAuthStore["user"] }> {
  const attempt = async () => {
    const auth = readAppAuth(slug);
    return appApi(slug, path, body, auth?.token);
  };
  let r = await attempt();
  if (r.status === 401 && r.body?.error === "sign_in_required") {
    const user = await requestSignIn(slug);
    if (!user) return { ok: false, text: "Sign-in was cancelled." };
    r = await attempt();
  }
  const j = r.body;
  if (r.ok && typeof j?.text === "string") return { ok: true, text: j.text };
  const err = typeof j?.message === "string" && j.message ? j.message : typeof j?.error === "string" ? j.error : `HTTP ${r.status}`;
  return { ok: false, text: err === "sign_in_required" ? "This part needs a signed-in user." : err };
}

export const STUDIO_AI_FALLBACK_SYSTEM =
  "You are the AI built into a small app. Answer the user's request directly and concisely, plain text, no markdown fences.";

/** The vdCall kinds a PUBLISHED app sends. `reply` posts vdReply back. */
export async function handleAppCall(
  d: { kind: string; slug?: string; alias?: string; input?: string; data?: unknown; prompt?: string; action?: string },
  reply: (res: { ok: boolean; text?: string; user?: AppAuthStore["user"] }) => void,
  hooks: { requestSignIn: (slug: string) => void }
): Promise<void> {
  const slug = typeof d.slug === "string" ? d.slug : "";
  const signIn = (s: string) =>
    new Promise<AppAuthStore["user"]>((resolve) => {
      const list = pendingAuthResolvers.get(s) || [];
      list.push(resolve);
      pendingAuthResolvers.set(s, list);
      hooks.requestSignIn(s);
    });
  try {
    if (d.kind === "call") {
      reply(
        await callPublished(slug, `/call/${encodeURIComponent(String(d.alias || ""))}`, {
          input: String(d.input ?? ""),
          data: d.data && typeof d.data === "object" ? (d.data as Record<string, unknown>) : {},
        }, signIn)
      );
      return;
    }
    if (d.kind === "ai") {
      if (!slug) {
        // Not published yet - preview the AI block on the studio's own
        // engine (on-device WebLLM first, cloud fallback): the same promise
        // the builder's "AI inside the app" toggle advertises.
        const res = await runModel({
          system: STUDIO_AI_FALLBACK_SYSTEM,
          user: String(d.prompt ?? ""),
          prefer: "auto",
          replyTokens: 700,
        });
        reply(res.ok ? { ok: true, text: res.text } : { ok: false, text: res.error || "The AI couldn't answer." });
        return;
      }
      reply(await callPublished(slug, "/ai/chat", { prompt: String(d.prompt ?? "") }, signIn));
      return;
    }
    if (d.kind === "auth") {
      if (!slug) {
        reply({ ok: false, text: "Publish the app to turn on sign-in (Share tab → Publish).", user: null });
        return;
      }
      const action = String(d.action || "user");
      if (action === "signOut") {
        writeAppAuth(slug, null);
        void appApi(slug, "/auth/logout", {}).catch(() => {});
        reply({ ok: true, user: null });
        return;
      }
      const stored = readAppAuth(slug);
      if (action === "user") {
        if (!stored) {
          reply({ ok: true, user: null });
          return;
        }
        const me = await appApi(slug, "/auth/me", undefined, stored.token);
        if (me.ok && me.body?.user) {
          reply({ ok: true, user: normUser(me.body.user) });
          return;
        }
        writeAppAuth(slug, null);
        reply({ ok: true, user: null });
        return;
      }
      // signIn / require / signUp - reuse a live session if we already have one.
      if (stored) {
        const me = await appApi(slug, "/auth/me", undefined, stored.token);
        if (me.ok && me.body?.user) {
          reply({ ok: true, user: normUser(me.body.user) });
          return;
        }
        writeAppAuth(slug, null);
      }
      const user = await signIn(slug);
      reply(user ? { ok: true, user } : { ok: false, text: "Sign-in was cancelled.", user: null });
      return;
    }
    reply({ ok: false, text: "Unknown call." });
  } catch {
    reply({ ok: false, text: "Couldn't reach the platform API." });
  }
}

// module-level map of slug -> queued resolvers for pending sign-in overlays;
// the Workspace's overlay drains every waiter when sign-in completes or is
// cancelled, so two app taps that both trigger the gate don't hang.
export const pendingAuthResolvers = new Map<string, ((u: AppAuthStore["user"]) => void)[]>();
