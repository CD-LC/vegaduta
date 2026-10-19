// Edge Wave 2 — EDGE-A (docs/rewriting-story/04-design/EDGE-AI-STRATEGY.md;
// closes the on-device half of finding E-1/F-25): the pattern-tier guardrail
// module the edge router runs BEFORE local inference (screenInput) and over
// the completed local reply (screenOutput), plus the PII scrub applied to
// text that falls through to hosted (prepareHostedFallthrough / scrubPii).
//
// PORTED FROM (cite + drift risk): the regex pattern sets here are hand-ported
// from the two server-side pattern guardrails —
//   core/guardrails/src/main/java/com/agenticai/core/guardrails/impl/PiiGuardrail.java
//   core/guardrails/src/main/java/com/agenticai/core/guardrails/impl/JailbreakDetectorGuardrail.java
// — including their ReDoS remediation idioms (bounded lazy gaps instead of
// unbounded `.*`, and a scan-length cap; see the Java classes' 2026-07-29
// javadoc for the measured O(n^4) blow-up the bounds prevent). There is no
// build-time enforcement keeping the two languages in sync: if the Java
// patterns change, THIS FILE MUST BE UPDATED BY HAND or edge and hosted turns
// will screen differently. That drift risk is accepted and named here rather
// than papered over — the alternative (shipping the Java patterns over the
// wire) would turn a permitAll config endpoint into a guardrail-configuration
// oracle.
//
// HONEST LIMITATIONS, named per the strategy's "pattern-tier guardrails are
// weaker than server semantic ones" tradeoff:
//   - Only the regex-portable pair (pii, jailbreak) is represented. Agents
//     whose enabled guardrails include anything else (profanity / toxicity /
//     policy_violation / custom — semantic or unported) are routed hosted by
//     tierRouter instead of being half-governed here; the server tells the
//     client which case it is via guardrailsPatternRepresentable (see
//     EdgeAgentPolicyLookupService.PATTERN_TIER_GUARDRAIL_IDS, the server
//     mirror of this module's scope).
//   - screenOutput runs on the ACCUMULATED reply text after generation
//     completes, so mid-stream tokens may already have rendered before a
//     violation is detected; the mitigation is that routeTurn replaces the
//     whole rendered reply with a refusal notice through its existing return
//     shape (callers already overwrite streamed content with the final
//     `text`). A true token-by-token screen would add per-token regex cost to
//     the hot path for marginal benefit — documented tradeoff, not oversight.

// ---------------------------------------------------------------------------
// PII patterns — ported from PiiGuardrail.java (field-for-field, same order).
// ---------------------------------------------------------------------------

// Email (simplified — not RFC-complete, same as the Java).
const EMAIL_PATTERN = /\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/;
const EMAIL_PATTERN_G = new RegExp(EMAIL_PATTERN.source, "g");

// Phone — NANP 3-3-4 (parens/space/dot/dash separators) + India 5+5 grouping,
// optional +CC. (?<!\d)/(?!\d) instead of \b so a match can't start/end
// mid-number (e.g. inside a 16-digit card) — same reasoning as the Java.
// Lookbehind is supported by every browser this app's WebGPU/WebLLM edge tier
// can run on (Chrome 62+), so no compatibility shim is needed at this layer.
const PHONE_PATTERN =
  /(?<!\d)(?:\+?\d{1,3}[-. ]?)?(?:(?:\(\d{3}\)|\d{3})[-. ]?\d{3}[-. ]?\d{4}|\d{5}[-. ]?\d{5})(?!\d)/;
const PHONE_PATTERN_G = new RegExp(PHONE_PATTERN.source, "g");

// Social Security Number (XXX-XX-XXXX).
const SSN_PATTERN = /\b\d{3}-\d{2}-\d{4}\b/;
const SSN_PATTERN_G = new RegExp(SSN_PATTERN.source, "g");

// Credit card (simplified 4x4 groups, same as the Java).
const CREDIT_CARD_PATTERN = /\b\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{4}\b/;
const CREDIT_CARD_PATTERN_G = new RegExp(CREDIT_CARD_PATTERN.source, "g");

// PII keywords that only count alongside a long digit run (>=8), mirroring
// PiiGuardrail#containsSuspiciousNumbers.
const PII_KEYWORD_PATTERN =
  /\b(ssn|social.?security|credit.?card|account.?number|routing|cvv|pin|password|secret)\b/i;
const SUSPICIOUS_NUMBER_PATTERN = /\d{8,}/;

// ---------------------------------------------------------------------------
// Jailbreak patterns — ported from JailbreakDetectorGuardrail.java, including
// the ReDoS-safe idioms:
//   GAP = the bounded, LAZY `[^\n]{0,40}?` replacing the old unbounded `.*`
//   (bounding removes the polynomial backtracking blow-up — the engine can
//   try at most 41 split points per gap instead of O(n));
//   MAX_SCANNED_CHARS caps how much text reaches the regex engine at all, so
//   a future pattern edit can't reintroduce an unbounded outage.
// ---------------------------------------------------------------------------

const GAP = "[^\\n]{0,40}?";
/** Mirrors JailbreakDetectorGuardrail.MAX_SCANNED_CHARS — text beyond this is
 * ignored rather than rejected (the output path is legitimately long). */
export const MAX_SCANNED_CHARS = 16_000;

const JAILBREAK_PATTERNS: RegExp[] = [
  // Role override / instruction ignore
  new RegExp("ignore" + GAP + "previous" + GAP + "instruction", "i"),
  new RegExp("disregard" + GAP + "prompt", "i"),
  new RegExp("forget" + GAP + "your" + GAP + "instruction", "i"),
  // Prompt extraction attempts
  new RegExp("show" + GAP + "(?:me|us)" + GAP + "(?:your|the)" + GAP + "(?:system" + GAP + ")?prompt", "i"),
  new RegExp("(?:system|original)" + GAP + "prompt" + GAP + "(?:was|is)", "i"),
  new RegExp("what" + GAP + "(?:are|is)" + GAP + "your" + GAP + "(?:system" + GAP + ")?instruction", "i"),
  // Authority spoofing
  new RegExp("i" + GAP + "(?:am|'m)" + GAP + "(?:admin|developer|owner|creator)", "i"),
  new RegExp("you" + GAP + "must" + GAP + "listen" + GAP + "to" + GAP + "me", "i"),
  // Format override
  new RegExp("respond" + GAP + "only" + GAP + "(?:in|as|like)" + GAP + "(?:json|xml|html|markdown|code)", "i"),
  new RegExp("output" + GAP + "format" + GAP + "override", "i"),
  // Direct injection markers
  /\[\[JAILBREAK\]\]/i,
  // Bounded for the same reason as the gaps (see the Java comment).
  /<<<[^\n]{0,200}?>>>/i,
  // Common attack prefixes
  /^(DAN|UCAR|ALPACA|GPT-4|REAL-ME):/i,
];

// ---------------------------------------------------------------------------
// Public surface
// ---------------------------------------------------------------------------

export interface GuardrailScreenResult {
  blocked: boolean;
  /** Machine-ish category ("jailbreak" | "pii") + the user-facing message the
   * router surfaces as the refusal notice. Both unset when not blocked. */
  reason?: "jailbreak" | "pii";
  message?: string;
}

const PASS: GuardrailScreenResult = { blocked: false };

function scanned(text: string): string {
  return text.length > MAX_SCANNED_CHARS ? text.slice(0, MAX_SCANNED_CHARS) : text;
}

/**
 * Input-path screen (user message, before local inference): jailbreak/prompt-
 * injection patterns — mirrors what JailbreakDetectorGuardrail contributes to
 * AgentChatService#enforceInboundGuardrails. The PII patterns deliberately do
 * NOT block input here: on the server, PiiGuardrail is "primarily [for] agent
 * responses" (its own javadoc), and blocking a person from telling THEIR OWN
 * agent their own phone number on-device would be stricter than hosted — the
 * input-side PII protection edge adds is the fallthrough scrub instead.
 */
export function screenInput(text: string): GuardrailScreenResult {
  if (!text || !text.trim()) return PASS;
  const s = scanned(text);
  for (const pattern of JAILBREAK_PATTERNS) {
    if (pattern.test(s)) {
      return {
        blocked: true,
        reason: "jailbreak",
        // Verbatim from JailbreakDetectorGuardrail's fail message.
        message:
          "Your request appears to contain a prompt injection or jailbreak attempt. " +
          "Please submit a direct, legitimate request.",
      };
    }
  }
  return PASS;
}

/**
 * Output-path screen (the completed local reply): PII leakage — mirrors
 * PiiGuardrail's checks in its exact order/priority. Buffered-at-completion
 * by design; see the header's honest-limitations note about mid-stream
 * tokens.
 */
export function screenOutput(text: string): GuardrailScreenResult {
  if (!text || !text.trim()) return PASS;
  const s = scanned(text);
  const fail = (message: string): GuardrailScreenResult => ({ blocked: true, reason: "pii", message });
  if (EMAIL_PATTERN.test(s)) {
    return fail("Response contains email addresses. Please remove personally identifiable information.");
  }
  if (PHONE_PATTERN.test(s)) {
    return fail("Response contains phone numbers. Please remove personally identifiable information.");
  }
  if (SSN_PATTERN.test(s)) {
    return fail("Response contains social security numbers. This data must not be exposed.");
  }
  if (CREDIT_CARD_PATTERN.test(s)) {
    return fail("Response contains credit card numbers. This data must not be exposed.");
  }
  if (PII_KEYWORD_PATTERN.test(s) && SUSPICIOUS_NUMBER_PATTERN.test(s)) {
    return fail("Response appears to contain sensitive authentication or account data.");
  }
  return PASS;
}

/**
 * Redact the PII classes the pattern tier knows about, replacing each match
 * with a bracketed placeholder. Order matters: SSN and credit-card runs are
 * redacted BEFORE phone so a 16-digit card never half-matches as a phone
 * number once its neighbors are gone (the lookaround guards make this safe
 * either way — the ordering is belt-and-braces). Unlike screenOutput this is
 * a TRANSFORM, not a verdict — used on text that is about to leave the device
 * (hosted fallthrough), where blocking outright would strand the person with
 * no answer at all.
 */
export function scrubPii(text: string): string {
  return scrubPiiCategories(text, { secrets: true, contact: true });
}

/** Which classes to mask. Split out (2026-09-11) for redactor.ts's default
 * hosted-send scrub, which masks the classes a hosted model never needs
 * verbatim (`secrets`) without touching the ones an ordinary task often does
 * need (`contact` — "draft a reply to jane@acme.com"). scrubPii keeps its
 * exact previous behavior by asking for both. */
export interface PiiScrubCategories {
  /** Government ids, card numbers. */
  secrets: boolean;
  /** Email addresses, phone numbers. */
  contact: boolean;
}

export function scrubPiiCategories(text: string, categories: PiiScrubCategories): string {
  if (!text) return text;
  // Ordering is the original scrubPii sequence, conditionals aside — email,
  // then SSN and card, then phone — so asking for both categories produces
  // byte-identical output to the pre-split implementation (email first also
  // keeps a long digit run inside an address from being carved up before the
  // address itself matches).
  let out = text;
  if (categories.contact) out = out.replace(EMAIL_PATTERN_G, "[email redacted]");
  if (categories.secrets) {
    out = out
      .replace(SSN_PATTERN_G, "[ssn redacted]")
      .replace(CREDIT_CARD_PATTERN_G, "[card number redacted]");
  }
  if (categories.contact) out = out.replace(PHONE_PATTERN_G, "[phone redacted]");
  return out;
}

/**
 * EDGE-A's fallthrough scrub (finding E-2/F-25: `handled:false` used to
 * re-send the verbatim message): what a caller SHOULD send to the hosted path
 * instead of the raw text when an edge turn falls through. Today this is the
 * PII scrub; kept as its own named export (rather than callers importing
 * scrubPii directly) so a future risk screen can join the fallthrough
 * pipeline without touching every caller. routeTurn also pre-computes this
 * onto its `{handled:false}` results as `scrubbedInput` for guardrail-enabled
 * agents — see tierRouter.ts.
 */
export function prepareHostedFallthrough(text: string): string {
  return scrubPii(text);
}
