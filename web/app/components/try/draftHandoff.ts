// Carrying a /try draft across sign-up (2026-09-19).
//
// THE TWO CARRY-OVER PATHS, and which one this file is:
//   1. CLOUD sandbox: the signup link carries ?demo=<token>; core's
//      DemoConversionService links the demo tenant and copies its agents into
//      the new workspace. That is server-side, already shipped, and NOT this
//      file. When a demo token exists this handoff is cleared so the two
//      mechanisms never produce a duplicate.
//   2. NO sandbox to copy - the visitor ran the on-device agent, or the cloud
//      sandbox was unreachable. Until now "register" from those states lost
//      everything. This file is that path: a small record in localStorage
//      that AgentWorkbench reads ONCE when the new account first opens
//      /agents, pre-filling the new-agent draft.
//
// SAFETY RULES (binding - change them only on purpose):
//   - Never in a URL. Same-origin localStorage survives the Keycloak
//     round-trip (signup and the console share app.<domain>), so nothing
//     about the visitor's business is ever put in a query string, a referrer
//     or a server log.
//   - Size-capped twice: every field on write, and the whole serialised
//     record on read (a record this module did not write is not trusted).
//   - Expires. HANDOFF_TTL_MS after it was written the record is ignored and
//     removed. A future-dated record is treated as forged and removed too.
//   - Allow-listed. `personaId` must be a real DemoPersona id; anything else
//     becomes null. Unknown fields are dropped, never passed through.
//   - Consumed once. The reader removes the record as it returns it, so a
//     draft can never re-apply itself over something the user later typed.
//   - What it becomes is a DRAFT the user can see and edit before saving.
//     The description is the account owner's own words going into their own
//     agent's instructions; it never reaches anyone else's prompt.
//
// The encode/decode/expiry logic is pure and unit-tested; the three storage
// functions at the bottom are thin try/catch wrappers around it.

import { DEMO_PERSONAS, type DemoPersonaId } from "../../lib/edge/demoPersonas";
import { findStarterTemplate, STARTER_TEMPLATE_PREFIX } from "../agents/starterTemplates";
import {
  ABOUT_MAX_LENGTH,
  MIN_SENTENCE_LENGTH,
  agentNameFor,
  cleanAbout,
  isPersonaId,
  sanitizeBusinessName,
} from "./agentDraft";

export const HANDOFF_STORAGE_KEY = "vegaduta.tryDraft";
export const HANDOFF_VERSION = 1;
export const HANDOFF_TTL_HOURS = 48;
export const HANDOFF_TTL_MS = HANDOFF_TTL_HOURS * 60 * 60 * 1000;
/** Generous ceiling for a record whose fields are capped at 60 + 280 chars. */
export const HANDOFF_MAX_BYTES = 2048;
/** Clock skew allowance before a "written in the future" record is refused. */
const FUTURE_SKEW_MS = 5 * 60 * 1000;

export type DraftHandoff = {
  v: number;
  personaId: DemoPersonaId | null;
  businessName: string | null;
  about: string;
  /** Epoch ms when written. */
  at: number;
};

export type HandoffInput = {
  personaId?: string | null;
  businessName?: string | null;
  about?: string | null;
};

/** Normalised record, or null when there is nothing worth carrying. */
export function makeHandoff(input: HandoffInput | null | undefined, now: number): DraftHandoff | null {
  const personaId = isPersonaId(input?.personaId) ? input.personaId : null;
  const businessName = sanitizeBusinessName(input?.businessName);
  const about = cleanAbout(input?.about);
  if (!personaId && !businessName && about.length < MIN_SENTENCE_LENGTH) return null;
  return { v: HANDOFF_VERSION, personaId, businessName, about, at: now };
}

export function encodeHandoff(handoff: DraftHandoff): string {
  return JSON.stringify(handoff);
}

/**
 * Parses and RE-VALIDATES a stored record. Everything is re-sanitised rather
 * than trusted: localStorage is writable by any script on the origin and by
 * the user, and what comes out of here is typed into a form.
 */
export function decodeHandoff(raw: string | null | undefined, now: number): DraftHandoff | null {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > HANDOFF_MAX_BYTES) return null;
  let parsed: any;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
  if (parsed.v !== HANDOFF_VERSION) return null;
  if (typeof parsed.at !== "number" || !Number.isFinite(parsed.at)) return null;
  if (parsed.at - now > FUTURE_SKEW_MS) return null;
  if (now - parsed.at > HANDOFF_TTL_MS) return null;
  const rebuilt = makeHandoff(
    {
      personaId: typeof parsed.personaId === "string" ? parsed.personaId : null,
      businessName: typeof parsed.businessName === "string" ? parsed.businessName : null,
      about: typeof parsed.about === "string" ? parsed.about.slice(0, ABOUT_MAX_LENGTH * 2) : null,
    },
    parsed.at
  );
  return rebuilt;
}

export type WorkbenchDraft = {
  name: string;
  /** Full instructions to set, or null to keep the form's own default and
   * only append `aboutParagraph`. */
  systemPrompt: string | null;
  /** "" when the visitor gave no description. */
  aboutParagraph: string;
  /** "starter:<persona>" so the workbench shows which starter this is, or
   * null for the general agent. */
  templateKey: string | null;
};

/** What the owner said about the business, as an instructions paragraph. */
export function aboutParagraphFor(handoff: DraftHandoff): string {
  const lines: string[] = [];
  if (handoff.businessName) lines.push(`The business is called ${handoff.businessName}.`);
  if (handoff.about.length >= MIN_SENTENCE_LENGTH) {
    lines.push(`In the owner's own words: "${handoff.about.replace(/"/g, "'")}"`);
  }
  return lines.length ? `About this business:\n- ${lines.join("\n- ")}` : "";
}

/**
 * The new-agent form values for a handoff. A persona maps to the REAL starter
 * template (components/agents/starterTemplates.ts) - the same instructions a
 * free account gets from the starter gallery - with the owner's description
 * appended. No persona keeps whatever default the form already has.
 */
export function handoffToWorkbenchDraft(handoff: DraftHandoff): WorkbenchDraft {
  const aboutParagraph = aboutParagraphFor(handoff);
  const templateKey = handoff.personaId ? `${STARTER_TEMPLATE_PREFIX}${handoff.personaId}` : null;
  const starter = findStarterTemplate(templateKey);
  const name = handoff.businessName
    ? agentNameFor(handoff.personaId, handoff.businessName)
    : starter?.defaultName ?? (handoff.personaId ? DEMO_PERSONAS[handoff.personaId].defaultAgentName : "");
  return {
    name,
    systemPrompt: starter ? (aboutParagraph ? `${starter.systemPrompt}\n\n${aboutParagraph}` : starter.systemPrompt) : null,
    aboutParagraph,
    templateKey: starter ? starter.key : null,
  };
}

// --- storage wrappers (never throw) -----------------------------------------

function storage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null; // storage disabled / private mode / sandboxed frame
  }
}

/** Returns true when the record was written. False is not an error the
 * visitor needs to see: sign-up still works, the draft just starts blank. */
export function saveDraftHandoff(input: HandoffInput | null | undefined, now: number = Date.now()): boolean {
  const handoff = makeHandoff(input, now);
  const store = storage();
  if (!store) return false;
  try {
    if (!handoff) {
      store.removeItem(HANDOFF_STORAGE_KEY);
      return false;
    }
    store.setItem(HANDOFF_STORAGE_KEY, encodeHandoff(handoff));
    return true;
  } catch {
    return false; // quota, or storage went away mid-call
  }
}

export function clearDraftHandoff(): void {
  try {
    storage()?.removeItem(HANDOFF_STORAGE_KEY);
  } catch {
    /* nothing to report */
  }
}

/**
 * Read-and-remove. Whatever is stored under the key is removed whether or not
 * it decodes - an expired, oversized or malformed record has no second use.
 */
export function consumeDraftHandoff(now: number = Date.now()): DraftHandoff | null {
  const store = storage();
  if (!store) return null;
  let raw: string | null = null;
  try {
    raw = store.getItem(HANDOFF_STORAGE_KEY);
    if (raw !== null) store.removeItem(HANDOFF_STORAGE_KEY);
  } catch {
    return null;
  }
  return decodeHandoff(raw, now);
}

/** The one call AgentWorkbench makes. Null = nothing to apply. */
export function consumeTryDraftForWorkbench(now: number = Date.now()): WorkbenchDraft | null {
  const handoff = consumeDraftHandoff(now);
  return handoff ? handoffToWorkbenchDraft(handoff) : null;
}
