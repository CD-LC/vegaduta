// Runtime validation for the few API payloads the console cannot afford to
// read blind (FE-030): the session/identity reads made right after sign-in,
// and the event payloads parsed off the chat and voice SSE streams. Every
// other endpoint is typed at compile time only (toJson<T> in ./core) - T there
// is an assertion about the server contract, not a check.
//
// Posture: validate at the boundary, never break the caller. Schemas are
// LOOSE objects (unknown extra fields pass through untouched - core ships new
// fields before this build knows them), and a payload that fails validation
// is logged once via the client logger and then degraded, not thrown: the
// fields that do validate are kept, the ones that don't are dropped, and
// extra fields still pass through. Consumers therefore keep their existing
// `?? default` guards on every field.
import { z } from "zod";
import { reportClientError } from "../clientLogger";
import type { AgentStepEvent } from "./core";

// The enforced CSP (lib/csp.ts) has no 'unsafe-eval'. zod 4 probes
// `new Function("")` each time it builds an object schema; strict CSPs report
// that blocked call even though zod catches the throw, which filed one false
// "eval" violation per page view at /api/csp-report. Jitless mode skips the
// probe (payloads here are small session and SSE reads; the JIT fast path is
// not needed). This must run before the first z.object() below.
z.config({ jitless: true });

// ---------------------------------------------------------------------------
// Session / identity payloads (read right after sign-in)
// ---------------------------------------------------------------------------

/** GET /api/me - MeController.MeResponse: caller identity + effective scopes. */
export const MeResponseSchema = z.looseObject({
  subject: z.string(),
  tenantId: z.string().nullish(),
  roles: z.array(z.string()),
  permissions: z.array(z.string()),
});
export type MeResponse = z.infer<typeof MeResponseSchema>;

/** GET /api/account - AccountResponse(email, phoneNumber, avatarUrl). */
export const AccountResponseSchema = z.looseObject({
  email: z.string().nullish(),
  phoneNumber: z.string().nullish(),
  avatarUrl: z.string().nullish(),
});
export type AccountResponse = z.infer<typeof AccountResponseSchema>;

/**
 * GET /api/tenant/settings - TenantSettingsResponse: the tenant's plan and
 * live entitlements. Every field is optional here because pages model only
 * the subset they read, and older cores omit the newer flags.
 */
export const TenantSettingsResponseSchema = z.looseObject({
  tenantId: z.string().nullish(),
  accountType: z.string().nullish(),
  experienceMode: z.string().nullish(),
  plan: z.string().nullish(),
  organizationName: z.string().nullish(),
  maxUsers: z.number().nullish(),
  maxAgents: z.number().nullish(),
  maxWorkflows: z.number().nullish(),
  maxKbCollections: z.number().nullish(),
  usePlatformLlm: z.boolean().nullish(),
  byokAllowed: z.boolean().nullish(),
  whatsappAllowed: z.boolean().nullish(),
  whiteLabel: z.boolean().nullish(),
  liveVisionAllowed: z.boolean().nullish(),
  brandDisplayName: z.string().nullish(),
  brandLogoUrl: z.string().nullish(),
});
export type TenantSettingsResponse = z.infer<typeof TenantSettingsResponseSchema>;

// ---------------------------------------------------------------------------
// Chat / voice stream event payloads
// ---------------------------------------------------------------------------

/**
 * One activity-trace event (STEP_EVENT_MARKER chunk on the chat SSE stream).
 * `type` is an open string - see AgentStepEvent in ./core for why.
 */
export const AgentStepEventSchema = z.looseObject({
  type: z.string(),
  id: z.number().nullish(),
  tool: z.string().nullish(),
  args: z.string().nullish(),
  preview: z.string().nullish(),
  message: z.string().nullish(),
  ms: z.number().nullish(),
});

/** Voice turn `transcript` event - VoiceTurnTranscriptEvent. */
export const VoiceTranscriptEventSchema = z.looseObject({
  transcript: z.string(),
  method: z.string(),
});
export type VoiceTranscriptEvent = z.infer<typeof VoiceTranscriptEventSchema>;

/** Voice turn `audio-chunk` event - VoiceTurnAudioChunkEvent. */
export const VoiceAudioChunkEventSchema = z.looseObject({
  seq: z.number(),
  textFragment: z.string(),
  audioBase64: z.string(),
  method: z.string(),
});
export type VoiceAudioChunkEvent = z.infer<typeof VoiceAudioChunkEventSchema>;

/** Voice turn `done` summary event. */
export const VoiceDoneEventSchema = z.looseObject({
  sessionId: z.string(),
  transcript: z.string(),
  replyText: z.string(),
  speechText: z.string(),
  audioBase64: z.string(),
});
export type VoiceDoneEvent = z.infer<typeof VoiceDoneEventSchema>;

// ---------------------------------------------------------------------------
// Boundary helpers
// ---------------------------------------------------------------------------

type LooseSchema = z.ZodObject<z.ZodRawShape, z.core.$loose>;

// One report per label+issue per page life - a stream that sends the same
// malformed event 500 times must not turn into 500 client-log beacons.
const reported = new Set<string>();

function reportShapeMismatch(label: string, detail: string): void {
  const key = `${label}|${detail}`;
  if (reported.has(key)) return;
  reported.add(key);
  reportClientError("client_error", `Unexpected ${label} payload shape`, { label, detail });
}

/** Test hook: forget which mismatches were already reported. */
export function resetSchemaReportsForTests(): void {
  reported.clear();
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Validate `data` against a loose object schema. On success returns the
 * parsed value (extra fields included). On failure logs once and degrades:
 * a non-object payload becomes `null` (what the old untyped code effectively
 * handed callers when there was nothing to read), an object keeps every field
 * that validates on its own plus all unknown extra fields, and drops only the
 * fields that failed. Never throws.
 */
export function parseAtBoundary<S extends LooseSchema>(schema: S, data: unknown, label: string): z.infer<S> | null {
  const result = schema.safeParse(data);
  if (result.success) return result.data;
  const detail = result.error.issues
    .slice(0, 5)
    .map((issue) => `${issue.path.join(".") || "(root)"}: ${issue.message}`)
    .join("; ");
  reportShapeMismatch(label, detail);
  if (!isPlainObject(data)) return null;
  const salvaged: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(data)) {
    const fieldSchema = schema.shape[key];
    if (!fieldSchema) {
      salvaged[key] = value; // unknown extra field - passed through as-is
      continue;
    }
    const field = z.safeParse(fieldSchema, value);
    if (field.success) salvaged[key] = field.data;
  }
  // Degraded result: fields that failed validation are absent, so this is a
  // best-effort partial of the schema's type - see this file's header.
  return salvaged as z.infer<S>;
}

/**
 * Parse one STEP_EVENT_MARKER payload (the text after the marker). Returns
 * null for malformed JSON or a non-object payload - the trace is a nicety and
 * a bad line is skipped, never allowed to kill the turn. An object missing
 * `type` or carrying a wrongly-typed field is logged and passed through with
 * the valid fields only.
 */
export function parseStepEventPayload(json: string): AgentStepEvent | null {
  let raw: unknown;
  try {
    raw = JSON.parse(json);
  } catch {
    reportShapeMismatch("chat-stream:step-event", "invalid JSON");
    return null;
  }
  return parseAtBoundary(AgentStepEventSchema, raw, "chat-stream:step-event");
}
