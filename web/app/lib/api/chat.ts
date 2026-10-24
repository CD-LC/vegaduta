import { runRequestLifecycle } from "../requestLifecycle";
import {
  FRAMEWORK_MESSAGE_FAILURE_CLASSES,
  apiFetch,
  friendlyStatusFallback,
  toJson,
  toJsonQuietOn,
  jsonBody,
  type AgentStepEvent,
  type ApiError,
} from "./core";

import { pumpChatSse } from "./chatSse";
import { knownGuardError } from "../spendCap";
import {
  ChatStreamState,
  buildPumpHandlers,
  incompleteStreamError,
  recoverChatStream,
  resumeChatTurn,
  watchVisibility,
  type ChatResumeLifecycle,
} from "./chatResume";
import { RESUMABLE_OPT_IN_HEADER, TURN_ID_HEADER, clearPendingTurn, savePendingTurn } from "../resumableStream";
import { voiceChatApi } from "./voiceChat";

// Opts a chat turn into resumable streaming (core: ResumableChatStreamService, app.chat.resumable-streams.*).
// A header, not a body field: the POST body stays byte-identical for every caller, and core ignores the header
// entirely while its flag is off - so this is inert against a core that has the feature off or does not have it.
function withResumableOptIn(enabled: boolean, init: { headers: Record<string, string>; body: string }) {
  return enabled ? { ...init, headers: { ...init.headers, [RESUMABLE_OPT_IN_HEADER]: "1" } } : init;
}

/** uploadChatAttachment's response (ChatAttachmentService). */
export interface ChatAttachmentUpload {
  attachmentKey: string;
  category: string;
  mimeType: string;
  originalFilename: string;
  sizeBytes: number;
}

/** A streamChat/lifecycle failure: ApiError plus the lifecycle's own classification. */
type ChatStreamError = ApiError & { failureClass?: string; requestId?: string };

export const chatApi = {
  // Page-load restore of a turn this tab left in flight (resumable streams): replays it from its first event and
  // follows it live to the end. See chatResume.ts#resumeChatTurn and resumableStream.ts#loadPendingTurn.
  resumeChatTurn,

  // Stage 4 (Chat file upload) - uploads one image/audio/video file for use
  // in a subsequent streamChat call on this same agent. Rejects (400) if the
  // file isn't image/audio/video, is over its category's size cap, or the
  // agent's currently-resolved model doesn't accept that modality - see
  // ChatAttachmentService's javadoc. Returns { attachmentKey, category,
  // mimeType, originalFilename, sizeBytes } - pass attachmentKey/mimeType
  // straight into streamChat below.
  uploadChatAttachment: (agentId: string, file: Blob) => {
    const formData = new FormData();
    formData.append("file", file);
    return apiFetch(`/api/agents/${agentId}/chat/attachments`, {
      method: "POST",
      body: formData,
    }).then(toJson<ChatAttachmentUpload>);
  },

  // Chat (Stage 3) - streams Server-Sent Events from a POST, so it can't use
  // the browser's EventSource (GET-only) - reads the fetch response body as
  // a stream instead and parses the "data:" lines by hand.
  //
  // Stage 4 (Chat file upload): attachment is optional, { attachmentKey,
  // attachmentMimeType } from uploadChatAttachment above - omit/pass null
  // for a plain text turn, unchanged from before this stage existed.
  //
  // Multimodal playground attachments: `attachments` is the newer inline
  // variant - [{ fileName, mimeType, dataBase64 }] (max 3 files, 10 MB total
  // decoded; caps come from the agent's `modalities` block, see
  // ChatAttachments.tsx). The key is only included when the array is
  // non-empty, so every existing caller's POST body stays byte-identical.
  //
  // Live vision: each inline attachment may carry an optional
  // `source` ("upload" | "camera" | "screen" - absent means "upload"), which
  // is what the backend's per-user daily frame quota counts on, and a turn
  // that belongs to a watch session carries `visionSessionId` (from
  // startVisionSession below) so its frames are attributed to that session.
  // Both are omitted from the POST body when unset, keeping every existing
  // caller's payload byte-identical.
  //
  // CONFIRMED BUG this call now closes (audit 2026-08-06): neither the
  // initial fetch nor the reader.read() loop below carried any timeout -
  // a server that went silent mid-stream hung the UI forever, client-side,
  // with zero recovery. Now runs under the Universal Request Lifecycle
  // (requestLifecycle.ts): connect/idle/absolute timeouts, abort support,
  // bounded retry gated on nothing having streamed yet, and telemetry.
  // `lifecycle` is a new, optional 9th param so every existing positional
  // call (keyframes.ts included) keeps working byte-identical when omitted:
  //   - lifecycle.signal: an external AbortSignal (e.g. a page-level
  //     "cancel this whole operation" signal) wired straight into the
  //     controller, same effect as calling controller.cancel().
  //   - lifecycle.onController: fired synchronously with the fresh
  //     RequestLifecycleController the moment this call starts, so a UI
  //     caller can stash it in state and drive useRequestLifecycle() +
  //     RequestStatusIndicator (see ChatPanel.tsx / AgentPlayground.tsx) -
  //     live CONNECTING/PROCESSING/STREAMING state, a real Cancel button,
  //     and a distinct message for every failure class instead of one
  //     generic catch.
  streamChat: (
    agentId: string | number,
    message: string,
    sessionId: string,
    onChunk: (chunk: string) => void,
    attachment?: { attachmentKey?: string; mimeType?: string } | null,
    turnRef?: string,
    attachments?: { fileName: string; mimeType: string; dataBase64: string; source?: string }[],
    visionSessionId?: string | null,
    // Resumable streams (2026-10-04) add four optional members, all inert when omitted or when core's
    // app.chat.resumable-streams flag is off: `resumable: true` opts a call IN (default: off, so voice turns, the
    // tutor, keyframe analysis and every other caller keep "disconnect = stop generating"); `persistPending: true`
    // remembers the in-flight turn in sessionStorage so a reloaded page can restore it (see resumeChatTurn);
    // `onReconnecting` / `onReconcile` let the host show a quiet "reconnecting..." line and swap in the server's
    // copy of a resumed reply (see chatResume.ts).
    lifecycle?: ChatResumeLifecycle & { resumable?: boolean; persistPending?: boolean },
    // Opt-in activity trace (2026-08-17). Passing this - and ONLY passing this
    // - adds `stepEvents: true` to the POST body, which is what makes the
    // backend interleave STEP_EVENT_MARKER chunks with the normal tokens. Omit
    // it and both the request body and the response stream are byte-identical
    // to before this parameter existed, which is the whole contract: the same
    // SSE endpoint feeds the LiveKit voice bridge and every omnichannel
    // adapter, none of which can filter chunks they don't recognize (see
    // ChatRequest's javadoc in core/agents for why this had to be opt-in).
    // Added as a 10th positional parameter so every existing call site keeps
    // working untouched.
    steps?: { onEvent: (event: AgentStepEvent) => void },
    // Chat branching: edit / regenerate stream exactly like a chat turn from a different
    // route (branchEditPath / branchRegeneratePath in ./chatBranching), and the body fields they
    // read (message, turnRef, stepEvents) are the ones sent below. Omitted = the normal chat
    // endpoint, so every existing caller is unchanged. 11th positional parameter, same reason as
    // the ones above.
    pathOverride?: string,
    // Run-scoped tool selection (the playground's "this run only" tool switches): sent as `toolNames`, which
    // narrows THIS turn to those of the agent's tools - core refuses a name the agent does not offer, a tool kept
    // stays behind its approval gate, and the agent itself is never changed. Omitted/null = no field in the body,
    // so every other caller sends the bytes it always did; an empty array is sent as `[]` (a run without tools).
    // 12th positional parameter, same reason as the ones above.
    toolNames?: string[] | null
  ): Promise<string | null> => {
    // Captures whatever the operation itself threw (a real HTTP-error
    // Error with .status/.upstreamStatus attached, the STREAM_ERROR_MARKER
    // Error, or apiFetch's friendly network-down Error) so it can be
    // re-thrown verbatim below for failure classes the framework doesn't
    // synthesize its own message for - see FRAMEWORK_MESSAGE_FAILURE_CLASSES.
    let lastOperationError: unknown = null;
    // Failure class of the attempt that just ended, for the resume decision below.
    let lastFailureClass: string | null = null;
    // Opt-in (ChatPanel only): a detached turn keeps generating - and billing - after the client goes away, so only
    // a host that implements Stop/reconnect/restore may ask for it.
    const resumable = lifecycle?.resumable === true;
    const streamState = new ChatStreamState();
    streamState.sessionId = sessionId || null;
    const pumpHandlers = buildPumpHandlers(streamState, onChunk, steps);

    const controller = runRequestLifecycle<string | null>(
      async (ctx) => {
        try {
          const res = await apiFetch(pathOverride || `/api/agents/${agentId}/chat`, {
            method: "POST",
            signal: ctx.signal,
            ...withResumableOptIn(
              resumable,
              jsonBody({
                sessionId: sessionId || undefined,
                message,
                attachmentKey: attachment?.attachmentKey || undefined,
                attachmentMimeType: attachment?.mimeType || undefined,
                // Same client-generated turn id the inline TurnFeedback control will
                // later post back, sent up-front so the backend records this turn's RL
                // episode under the exact ref feedback matches on (agent+session+turnRef).
                turnRef: turnRef || undefined,
                attachments: attachments && attachments.length > 0 ? attachments : undefined,
                visionSessionId: visionSessionId || undefined,
                // Only present when this caller asked for a trace - `undefined`
                // is dropped by jsonBody, so a caller that didn't opt in sends
                // the exact same bytes it always did.
                stepEvents: steps ? true : undefined,
                toolNames: toolNames ?? undefined,
              })
            ),
          });
          // Headers are in hand the instant fetch() resolves, win or lose -
          // that's "connected" (CONNECTING -> PROCESSING, connect timer
          // stops, idle timer starts) regardless of what status code comes
          // back next.
          ctx.markConnected();
          if (!res.ok) {
            let body: { error?: string; message?: string; upstreamStatus?: unknown } = {};
            try {
              body = (await res.json()) ?? {};
            } catch {
              // ignore
            }
            // Spend cap reached (402) / view-as read-only (403): a clear,
            // specific message instead of the raw error code.
            const guard = knownGuardError(res.status, body);
            if (guard) throw guard;
            // body.message covers the attachment rejections (413 too large /
            // 415 unsupported modality / 400 malformed) whose contract may carry
            // { message } instead of { error } - surfaced verbatim either way.
            const err: ApiError = new Error(body.error || body.message || friendlyStatusFallback(res.status));
            err.upstreamStatus = body.upstreamStatus;
            // The status lets vision callers (CameraPanel's watch loop) tell an
            // exhausted daily frame quota (429) or a plan gate (403) apart from a
            // transient failure and render the honest inline notice for each.
            err.status = res.status;
            throw err;
          }
          const newSessionId = res.headers.get("X-Session-Id");
          // Resumable streams (core: ResumableChatStreamService). When the server detached this turn it names it in
          // X-Turn-Id. Absent - flag off, an older core, an opted-out caller - and nothing below behaves differently
          // from before: the stream is read by the same parser (chatSse.ts, moved out of this function verbatim) and
          // every failure propagates exactly as it always did.
          streamState.sessionId = newSessionId || streamState.sessionId;
          const headerTurnId = res.headers.get(TURN_ID_HEADER);
          if (headerTurnId) {
            streamState.turnId = headerTurnId;
            // Page-load restore needs to know a turn was in flight; only hosts that implement it ask for this.
            if (lifecycle?.persistPending) {
              savePendingTurn({
                turnId: headerTurnId,
                agentId: String(agentId),
                sessionId: streamState.sessionId,
                userMessage: String(message ?? ""),
                startedAt: Date.now(),
              });
            }
          }
          streamState.inFlight = true;
          try {
            await pumpChatSse(res, ctx, pumpHandlers);
          } finally {
            streamState.inFlight = false;
          }
          // A stream that names its turn always ends with a closing frame; EOF without one is a cut connection,
          // not a finished reply, and is recovered below instead of being reported as success.
          if (streamState.turnId && !streamState.turnEnd) throw incompleteStreamError();
          return newSessionId;
        } catch (err) {
          lastOperationError = err;
          throw err;
        }
      },
      {
        target: "chat-stream:core",
        surface: "chat-stream",
        signal: lifecycle?.signal,
        // Agent turns that invoke tools (web search, code execution, MCP
        // connectors) can legitimately go quiet longer than the framework's
        // 20s/120s safety-net defaults before the first/next token - looser,
        // still-bounded ceilings here so a real hang (the bug this closes)
        // still gets caught without false-triggering on a slow-but-healthy
        // tool call.
        //
        // connectTimeoutMs needs the same treatment, not just the two above
        // (2026-08-09 fix): markConnected() only fires once bytes start
        // arriving, but AgentChatService#chat's CAREFUL/RIGOROUS/PLANNED
        // reasoning depths buffer the ENTIRE answer server-side - including
        // multi-call self-consistency/Reflexion verification - before
        // emitting anything (see that method's javadoc), and every
        // Platform-pool (hosted) turn additionally runs through
        // attemptHostedChain's fallback-chain wrapper, which BYOK turns skip.
        // Both can legitimately blow past the framework's 10s connect
        // default even though the turn is healthy, which is exactly what
        // was surfacing as "Timed out establishing the connection" in the
        // playground while the usage/cost dashboard showed the call
        // completed fine server-side.
        //
        // Widened further (2026-08-12, direct report: "there is an error
        // pops up and status retrying 2/2 even though llm is taking some
        // time to respond or agent completing the action"). The 2026-08-09
        // numbers above were sized for a single buffered turn; the SAME
        // session's own fixes now make a healthy turn routinely take
        // meaningfully longer than that: RIGOROUS chains up to five
        // sequential completions (primary answer, consistency check +
        // revise, Reflexion critique + revise - see AgentChatService#
        // verifyThenRespond/ReflexionService), PLANNED can run a full
        // second buffered completion on a mid-plan retry, and every depth
        // can now make up to GuardedToolCallback's 15-call step-limit worth
        // of REAL tool round trips in one turn - including the new
        // fetch_web_page tool, itself allowed up to ~10s per fetch. None of
        // that is a hang; it's the agent actually doing the multi-step work
        // this session's other fixes exist to enable, so the ceiling has to
        // cover it rather than misclassify it as a dead connection.
        // absoluteTimeoutMs is the one hard backstop against a genuine
        // infinite loop/hang - raised, not removed, so that failure mode is
        // still caught, just with realistic headroom for the legitimate
        // worst case above.
        //
        // Widened again (2026-08-13, direct report + measured evidence): a
        // real PLANNED turn creating one Jira ticket with a single mid-plan
        // retry already measured 65.6s server-side (visible in the LLM call
        // log). A turn that also lists Confluence spaces, creates a page,
        // AND links it back into the Jira ticket - genuinely more tool round
        // trips, still with the same retry-on-tool-failure possibility on
        // top - comfortably clears 90s while still being a healthy, working
        // turn, not a hang. Third time this exact ceiling has needed raising
        // as this session's own other fixes (multi-step planning, tool
        // retry, more real MCP tools) made a normal turn legitimately
        // slower - see the two comments above this one for 2026-08-09 and
        // 2026-08-12. absoluteTimeoutMs stays put: the real backstop against
        // an actual infinite loop shouldn't need to move just because a
        // healthy multi-tool turn got slower.
        timeoutPolicy: { connectTimeoutMs: 180_000, streamIdleTimeoutMs: 60_000, absoluteTimeoutMs: 300_000 },
        // A chat turn is NOT idempotent, so the framework default
        // (["connect-timeout", "connection-refused"]) is wrong here.
        // `connection-refused` is safe - nothing ever reached core, so
        // nothing ran. `connect-timeout` is not: on the buffered reasoning
        // depths nothing is written to the wire until the whole turn is
        // finished, so hitting connectTimeoutMs means "the turn is STILL
        // RUNNING server-side", and the retry starts a SECOND full turn -
        // re-invoking every tool the first one is in the middle of calling
        // (sending a message, creating a ticket, spending money) and
        // double-billing the tenant, then usually timing out again. This is
        // the "retrying 2/2" the timeout comments above describe; widening
        // the ceilings made it rarer, dropping the class makes it impossible.
        retryPolicy: { maxRetries: 1, retryableFailureClasses: ["connection-refused"] },
      }
    );

    lifecycle?.onController?.(controller);
    streamState.currentController = controller;
    const stopWatchingVisibility = resumable ? watchVisibility(streamState) : (): void => undefined;

    const firstAttempt = controller.done.then((result) => {
      if (result.ok) return result.value;
      const failureClass = result.snapshot.failureClass;
      lastFailureClass = failureClass;
      // Timeout/cancel/circuit-breaker outcomes already carry their own
      // precise, distinct message from the framework itself (see
      // requestLifecycle.ts's runAttempt/finish) - prefer that over
      // whatever the operation happened to throw when the abort landed
      // mid-await (often just a raw AbortError). Everything else (a real
      // HTTP error, a mid-stream STREAM_ERROR_MARKER, a network failure)
      // re-throws the ORIGINAL error object so existing callers that read
      // err.status/err.upstreamStatus (CameraPanel's watch loop via
      // AgentPlayground's sendServerTurn, in particular) keep working
      // unchanged.
      if (lastOperationError && failureClass && !FRAMEWORK_MESSAGE_FAILURE_CLASSES.has(failureClass)) {
        throw lastOperationError;
      }
      const err: ChatStreamError = new Error(
        result.snapshot.error || "The chat request didn't complete. Please try again."
      );
      err.failureClass = failureClass;
      err.requestId = result.snapshot.requestId;
      throw err;
    });

    // Resumable streams: a stream the server named (X-Turn-Id) that broke - dropped connection, sleeping tab, idle
    // timeout - is resumed from the last event it delivered instead of failing the turn (chatResume.ts; the
    // decisions are pure and unit-tested in resumableStream.ts). A stream with no turn id - flag off, an older
    // core - re-throws the very same error it always did.
    return firstAttempt
      .catch((err) => {
        // No turn id (flag off, an older core, or a Stop before the first byte): nothing to resume. A Stop before the
        // first byte is handled by core itself - it cancels a detached turn whose original reader leaves before any
        // output (ResumableChatStreamService), exactly as a disconnect always did.
        if (!streamState.turnId) throw err;
        return recoverChatStream({
          agentId,
          state: streamState,
          handlers: pumpHandlers,
          lifecycle,
          firstError: err,
          firstFailureClass: lastFailureClass,
        });
      })
      .finally(() => {
        stopWatchingVisibility();
        // Kept when the page itself cancelled us (navigation): coming back to the chat should restore the reply.
        if (lifecycle?.persistPending && streamState.turnId && !lifecycle.signal?.aborted)
          clearPendingTurn(String(agentId));
      });
  },

  // Voice calling, push-to-talk turns and reply playback - see voiceChat.ts.
  ...voiceChatApi,

  // Live vision sessions - the audit/quota/approval anchor for a
  // CameraPanel watch (or future live) session. startVisionSession returns
  // { sessionId, mode, source, startedAt, frameAllowanceRemaining }; the
  // sessionId then rides every watch turn's streamChat call (visionSessionId
  // above). 403 (plan lacks live vision), 429 (daily frame/minute quota
  // exhausted) and 503 (platform vision flag off) are expected STATES the
  // panel renders inline - toJsonQuietOn keeps them out of the global error
  // toast, same posture as the LLM-usage endpoints' FREE-plan 403.
  startVisionSession: (agentId: string, mode: "SNAPSHOT" | "WATCH" | "LIVE", source: "CAMERA" | "SCREEN") =>
    apiFetch(`/api/vision/sessions`, {
      method: "POST",
      ...jsonBody({ agentId, mode, source }),
    }).then(toJsonQuietOn([403, 429, 503])),
  // Ends the session - returns { sessionId, endedAt, framesAnalyzed,
  // durationSeconds } for the panel's honest "N frames analyzed" closing note.
  endVisionSession: (sessionId: string) =>
    apiFetch(`/api/vision/sessions/${sessionId}/end`, {
      method: "POST",
      ...jsonBody({}),
    }).then(toJson<{ sessionId: string; endedAt: string; framesAnalyzed: number; durationSeconds: number }>),
};
