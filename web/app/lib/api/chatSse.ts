import type { RequestLifecycleOperationContext } from "../requestLifecycle";
import { STREAM_ERROR_MARKER, STEP_EVENT_MARKER, type AgentStepEvent } from "./core";
import { parseStepEventPayload } from "./schemas";
import { parseSpendCapPayload, spendCapError } from "../spendCap";
import { TURN_END_EVENT, parseTurnEnd, type EventTracker, type TurnEnd } from "../resumableStream";

// The chat SSE reader, shared by streamChat's POST stream and the resume GET
// stream (api/chat.ts). It was previously inline in streamChat; moved here
// verbatim so the resume path parses a stream exactly the way the original does
// (same event assembly, same marker handling, same lifecycle hooks) instead of
// growing a second, subtly different parser. What this adds on top of the
// original, all inert for a stream that carries none of it (every flag-off
// stream, every older core):
//   - `id:` / `event:` fields are read, so a resumable stream's events can be
//     de-duplicated by id and its closing `turn-end` frame recognised;
//   - an SSE comment line (`:keepalive`, only ever sent on resume streams)
//     resets the idle timer without delivering anything.

export interface ChatSseHandlers {
  onChunk: (chunk: string) => void;
  steps?: { onEvent: (event: AgentStepEvent) => void };
  /** Drops events whose id was already delivered (a resume replays from its cursor). Absent = deliver everything. */
  tracker?: EventTracker;
  /** The closing frame of a resumable stream. */
  onTurnEnd?: (end: TurnEnd) => void;
}

export async function pumpChatSse(
  res: Response,
  ctx: RequestLifecycleOperationContext,
  handlers: ChatSseHandlers
): Promise<void> {
  const { onChunk, steps, tracker } = handlers;
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  // Upper bound on what the parser holds for ONE line or ONE event. A stream
  // that never sends a newline (or never ends an event) would otherwise grow
  // these without limit and exhaust the tab; 4 MB is far above any real
  // answer (the server caps a turn in the low hundreds of KB).
  const MAX_PENDING_CHARS = 4 * 1024 * 1024;
  let pendingDataChars = 0;
  // True once the stream has carried an `id:` field, i.e. it is a resumable
  // stream. Such a stream is always properly terminated (blank line after every
  // event), so an unterminated tail at EOF is a connection that was cut
  // mid-event and must be DISCARDED (per the SSE spec) and re-fetched by the
  // resume - flushing it would deliver half a token under a real id and the
  // resume would then skip the rest of it.
  let sawEventId = false;
  // SSE EVENT assembly, not line-by-line dispatch (2026-09-17 fix).
  // One backend chunk is ONE event, but an event whose payload
  // contains newlines is legally framed as SEVERAL "data:" lines:
  // Spring's SseEmitter.SseEventBuilderImpl#data does
  // StringUtils.replace(text, "\n", "\ndata:") before writing
  // (verified against spring-webmvc 6.2.5 bytecode), exactly as the
  // SSE spec requires. This parser used to hand every such line to
  // onChunk separately with nothing in between, and callers
  // concatenate (`assistantText += chunk`), so EVERY newline in an
  // assistant reply was silently deleted - paragraphs, bullet lists,
  // headings and code fences all collapsed into one run-on blob that
  // the markdown renderer then had no structure left to render. It
  // bites hardest on the CAREFUL/RIGOROUS/PLANNED reasoning depths,
  // where AgentChatService buffers the ENTIRE answer server-side and
  // emits it as a single event - i.e. the whole reply is one
  // multi-line payload, so all of its structure was lost at once.
  // Accumulating a event's data lines and rejoining them with "\n"
  // (what the voice parser further down already does, see its
  // dataLines.join("\n")) is the spec-correct reassembly.
  let dataLines: string[] = [];
  let pendingId: string | null = null;
  let pendingEvent: string | null = null;
  const dispatchEvent = () => {
    const eventId = pendingId;
    const eventName = pendingEvent;
    pendingId = null;
    pendingEvent = null;
    if (dataLines.length === 0) return;
    const payload = dataLines.join("\n");
    dataLines = [];
    pendingDataChars = 0;
    // Closing frame of a resumable stream: not text, never delivered to onChunk.
    if (eventName === TURN_END_EVENT) {
      handlers.onTurnEnd?.(parseTurnEnd(payload) ?? { status: "unknown", lastEventId: null });
      ctx.onChunk();
      return;
    }
    // A replay of an event this caller already rendered (the resume cursor can
    // overlap what arrived before the connection died). Dropped by id so no
    // token is ever shown twice; an event with no id is always delivered.
    if (tracker && !tracker.accept(eventId)) {
      ctx.onChunk();
      return;
    }
    // Mirror of AgentController.STREAM_ERROR_MARKER - a mid-stream
    // provider failure (HTTP status already committed by the time this
    // arrives, so it can't come back as a normal error response) is
    // sent as one final synthetic chunk instead of the connection just
    // dying silently (previously surfaced to users as a stuck
    // "Thinking..." and to the console as net::ERR_QUIC_PROTOCOL_ERROR).
    // Thrown here rather than passed to onChunk so callers can tell a
    // real error apart from literal streamed text.
    // Spend cap reached after the stream opened: core sends an SSE `error`
    // event (or the in-band marker) carrying the 402 JSON. Thrown as the clear
    // spend-limit message, and like the marker it is the server's verdict, so
    // never resumed.
    const capPayload =
      eventName === "error"
        ? payload
        : payload.startsWith(STREAM_ERROR_MARKER)
          ? payload.slice(STREAM_ERROR_MARKER.length)
          : null;
    const cap = capPayload != null ? parseSpendCapPayload(capPayload) : null;
    if (cap) {
      const failure: any = spendCapError(cap);
      failure.streamMarker = true;
      throw failure;
    }
    if (payload.startsWith(STREAM_ERROR_MARKER)) {
      const failure: Error & { streamMarker?: boolean } = new Error(
        payload.slice(STREAM_ERROR_MARKER.length) || "The response was interrupted. Please try again."
      );
      // The server's own verdict on this turn, not a dropped connection: a
      // resume would only replay the same failure, so streamChat never resumes it.
      failure.streamMarker = true;
      throw failure;
    }
    // Activity-trace chunk (only ever present when `steps` was
    // passed - see STEP_EVENT_MARKER above). Intercepted BEFORE the
    // generic branch below so it never reaches onChunk and can never
    // be appended into the visible assistant bubble as literal JSON.
    // A malformed payload is skipped silently rather than thrown:
    // the trace is a nicety, and killing a turn that is otherwise
    // answering perfectly well over one unparseable trace line would
    // be strictly worse than showing a slightly shorter trace (same
    // fail-open posture the backend emitter takes).
    if (payload.startsWith(STEP_EVENT_MARKER)) {
      // Validated at the boundary (schemas.ts): a non-object is skipped and a
      // shape mismatch is logged once while the valid fields still pass through.
      const event = steps ? parseStepEventPayload(payload.slice(STEP_EVENT_MARKER.length)) : null;
      if (event) {
        try {
          steps?.onEvent(event);
        } catch {
          // ignore - see above
        }
      }
      // Counted as real output on purpose. The backend commits the
      // HTTP status line the moment this chunk goes out (which is
      // also why AgentController widens its own "has anything
      // streamed?" check to include these), so a retry from here
      // could only ever duplicate work - disarming it is correct,
      // not incidental. It also resets the idle timer, which is what
      // keeps a long-running tool call from reading as a dead
      // connection.
      ctx.onChunk();
      return;
    }
    onChunk(payload);
    // Real output just reached the caller - resets the idle
    // timer AND permanently disables retry for this attempt
    // (see requestLifecycle.ts's header) so a transient
    // mid-stream failure can never replay text the caller
    // already rendered.
    ctx.onChunk();
  };
  const consumeLine = (rawLine: string) => {
    // CR tolerance: the spec lets an event line end CR, LF or CRLF.
    // Spring writes bare LF, so this is defensive only - but without
    // it a CRLF stream would never produce an empty line and events
    // would never dispatch.
    const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
    // Blank line = end of event.
    if (line === "") {
      dispatchEvent();
      return;
    }
    // SSE framing only: strip EXACTLY the 5-char "data:" prefix, nothing
    // more. A prior version of this also conditionally ate a 6th
    // character whenever the line started with "data: " (colon + space),
    // on the assumption that the space was always framing. That's the
    // same "every word runs together" bug moved one layer down, not
    // fixed: Spring's writer emits "data:<content>" with NO separator
    // space of its own, so whenever the model's streamed chunk is
    // exactly a single space (a lone word-boundary token - common with
    // several providers, including the OpenAI-compatible hosted-pool
    // path), the wire line is "data:" + " " = "data: ", byte-identical
    // to what "framing space, empty content" would produce. The old code
    // couldn't tell those apart and silently dropped every one of those
    // space-only chunks, which is why entire replies rendered with zero
    // spaces between words. Since the framing never adds its own space,
    // slice(5) alone is always correct and never ambiguous.
    if (line.startsWith("data:")) {
      pendingDataChars += line.length;
      if (pendingDataChars > MAX_PENDING_CHARS) {
        throw new Error("chat stream event exceeded the size limit");
      }
      dataLines.push(line.slice(5));
    } else if (line.startsWith("id:")) {
      sawEventId = true;
      pendingId = line.slice(3).trim();
    } else if (line.startsWith("event:")) {
      pendingEvent = line.slice(6).trim();
    } else if (line.startsWith(":")) {
      // Comment (a resume stream's keepalive): proof the connection is alive,
      // nothing to deliver.
      ctx.onChunk();
    }
    // Every other field (`retry:`) carries no payload on this endpoint -
    // ignored, per the spec, exactly as before.
  };
  // eslint-disable-next-line no-constant-condition
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    if (buffer.length > MAX_PENDING_CHARS) {
      await reader.cancel();
      throw new Error("chat stream line exceeded the size limit");
    }
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";
    for (const line of lines) consumeLine(line);
  }
  // Flush the decoder's own tail, then any final line and any final
  // event the server never terminated with a blank line - the old
  // loop dropped both on the floor (the sibling parsers in
  // clients/shared/src/api/sse.ts and streamVoiceTurn below already
  // flush theirs). Not for a stream that carries event ids - see sawEventId.
  buffer += decoder.decode();
  if (sawEventId) return;
  if (buffer) consumeLine(buffer);
  dispatchEvent();
}
