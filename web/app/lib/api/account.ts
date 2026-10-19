import { API_BASE, apiFetch, friendlyStatusFallback, toJson, jsonBody } from "./core";
import { AccountResponseSchema, parseAtBoundary, type AccountResponse } from "./schemas";
import type { QuickTaskRunDetail, QuickTaskRunSummary } from "./types";

export const accountApi = {
  // Account self-service (AccountController.java) - the logged-in user's own
  // phone number (Simple mode's Quick Task feature delivers WhatsApp/SMS/
  // call results to it) and profile picture (avatarUrl below).
  // Read right after sign-in (AppChromeProvider) - validated at the boundary
  // (./schemas); a shape mismatch is logged and degraded, never thrown.
  getAccount: (): Promise<AccountResponse | null> =>
    apiFetch(`/api/account`)
      .then(toJson)
      .then((data) => parseAtBoundary(AccountResponseSchema, data, "GET /api/account")),
  setAccountPhoneNumber: (phoneNumber: string) =>
    apiFetch(`/api/account/phone`, {
      method: "PUT",
      ...jsonBody({ phoneNumber }),
    }).then(toJson<AccountResponse>),

  // Self-service account deletion (AccountSelfDeletionController). Core demands
  // a RECENT sign-in: an older session gets 401 {code:"reauth_required",
  // max_age}, which toJson keeps as err.code / err.maxAge (see isReauthRequired
  // in ../errors). Callers go through lib/accountDeletion.ts, and only ever
  // from an explicit confirm click.
  deleteAccount: () => apiFetch(`/api/account`, { method: "DELETE" }).then(toJson),

  // Profile-picture upload (AppShell's avatar, next to Sign out) - blob is
  // the already-cropped-to-square PNG/JPEG AvatarUploadModal produced via
  // <canvas>.toBlob, not the original picked file. Returns the refreshed
  // AccountResponse (avatarUrl now set), same "one round trip" shape as
  // setAccountPhoneNumber above.
  uploadAccountAvatar: (blob: Blob) => {
    const formData = new FormData();
    formData.append("file", blob, "avatar.png");
    return apiFetch(`/api/account/avatar`, {
      method: "POST",
      body: formData,
    }).then(toJson<AccountResponse>);
  },
  // Fetches the logged-in user's own avatar as a blob for use in an <img
  // src> via URL.createObjectURL - a plain <img src="/api/account/avatar">
  // can't carry the bearer token AccountController#getAvatar requires, same
  // reasoning as downloadKnowledgeDocumentFile above. Returns null on 404
  // (no avatar uploaded yet) WITHOUT routing through toJson, so AppShell's
  // initials fallback renders quietly instead of raising the global error
  // toast for the completely normal "hasn't uploaded one" state.
  getAccountAvatarBlob: async () => {
    const res = await apiFetch(`/api/account/avatar`);
    if (!res.ok) return null;
    return res.blob();
  },

  // Simple mode's Quick Task feature (QuickTaskController.java) - "type what
  // you want, pick WhatsApp/SMS/a call, hit Run." One-time, synchronous
  // (not streamed) - the caller needs the complete answer to show on-screen
  // and to dispatch as a single message/call.
  runQuickTask: (message: string, channel: string) =>
    apiFetch(`/api/quick-task`, {
      method: "POST",
      ...jsonBody({ message, channel }),
    }).then(toJson),

  // Live trace (#474 follow-up: "I want to see the live trace log for that
  // task - everything running for that task") - same request/work as
  // runQuickTask above, but QuickTaskController#runStream reports back as it
  // happens instead of only once at the end: a "started" event with the
  // real session id, one "tool_call" event per real ToolCallLog row as
  // QuickTaskService's background poll notices it, then "done" or "error".
  // Same "can't use EventSource since this is a POST, read the fetch body
  // as a stream and split on 'data:' lines by hand" approach streamChat
  // above already established - the only difference is each line here is a
  // JSON object (one event), not a raw text token, so it's JSON.parsed and
  // handed to onEvent instead of concatenated.
  // agentId is optional - "customers should be able to select the agents" -
  // omitted/undefined keeps today's auto-select behavior server-side
  // (QuickTaskService#selectAgentOrThrow), a real id routes straight to
  // that agent instead.
  // Generic over the caller's event type: each line is JSON.parse'd and handed
  // over unvalidated (see SimpleTaskRunner's QuickTaskStreamEvent).
  runQuickTaskStream: async <E = unknown>(message: string, channel: string, onEvent: (event: E) => void, agentId: string) => {
    const res = await apiFetch(`/api/quick-task/stream`, {
      method: "POST",
      ...jsonBody({ message, channel, agentId: agentId || undefined }),
    });
    if (!res.ok) {
      let body: { error?: string } = {};
      try {
        body = (await res.json()) ?? {};
      } catch {
        // ignore
      }
      throw new Error(body.error || friendlyStatusFallback(res.status));
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
     
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";
      for (const line of lines) {
        if (!line.startsWith("data:")) continue;
        const raw = line.slice(5).replace(/^ /, "");
        if (!raw) continue;
        try {
          onEvent(JSON.parse(raw));
        } catch {
          // A malformed line shouldn't kill the whole stream - skip it.
        }
      }
    }
  },

  // "Detect the underlying workflow if specified, select defined tools/MCP
  // tools" - a dry-run preview called before the channel picker/Run button,
  // so the person sees which agent + tools would be used before committing.
  previewQuickTask: (message: string, agentId: string) =>
    apiFetch(`/api/quick-task/preview`, {
      method: "POST",
      ...jsonBody({ message, agentId: agentId || undefined }),
    }).then(toJson),

  // #606 follow-up: "it should generate an ID of a task and we should be
  // able to see the complete log with request sent and response back - not
  // just half of it - any point of time." taskId is the same sessionId the
  // "started"/"done" SSE events and the blocking runQuickTask response
  // already carry - this is what makes that id actually look-up-able
  // afterward instead of write-only.
  getQuickTask: (taskId: string) => apiFetch(`/api/quick-task/${encodeURIComponent(taskId)}`).then(toJson<QuickTaskRunDetail>),
  getRecentQuickTasks: () => apiFetch(`/api/quick-task/recent`).then(toJson<QuickTaskRunSummary[]>),

  // Tenant signup (Phase 0 Stage 5) - deliberately plain fetch(), NOT
  // apiFetch(): a brand-new visitor has no Keycloak session yet, so there is
  // no token for apiFetch's auth.getToken() to attach, and calling it would
  // pointlessly touch the (uninitialized, on this page - see AuthProvider's
  // PUBLIC_ROUTES exemption) Keycloak client for no benefit. Matches
  // TenantSignupController's own SecurityConfig permitAll() rule.
  signup: (payload: object) =>
    fetch(`${API_BASE}/api/signup`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson),

  // SocialLoginRow's "which buttons should actually render" lookup
  // (SocialAuthConfigController, core/admin) - same plain-fetch reasoning as
  // signup() just above: rendered on /signup before anyone has a Keycloak
  // session, so there's no token to attach and apiFetch would just touch an
  // uninitialized Keycloak client for no benefit. Swallows any failure into
  // an empty list rather than throwing - toJson()'s toast-on-error wiring
  // would otherwise pop an alarm over a cosmetic "should I show 3 buttons or
  // 0" lookup, and the row already renders fine with zero social buttons.
  getSocialProviders: (): Promise<{ providers: string[] }> =>
    fetch(`${API_BASE}/api/auth/social-providers`)
      .then(toJson<{ providers: string[] }>)
      .catch(() => ({ providers: [] as string[] })),

  // Social-login onboarding (OnboardingGate) - unlike signup, the caller IS
  // authenticated (e.g. a Google login) but has no tenant yet, so this uses
  // apiFetch to attach their token. POST /api/onboarding provisions their
  // organization/tenant (OnboardingController + TenantProvisioningService),
  // and is exempted from TenantContextFilter's missing-tenant 403.
  onboarding: (payload: object) =>
    apiFetch(`/api/onboarding`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson),
};
