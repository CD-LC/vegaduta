import { DEMO_PERSONAS, type DemoPersonaId } from "../../lib/edge/demoPersonas";

// FREE-plan starter templates (funnel audit, 2026-09: "Give FREE tenants a
// starter template so the first agent is one click, not a blank draft").
//
// The full /agents/templates gallery (components/agentTemplates.ts) is a paid
// capability in the UI - AgentWorkbench lock-badges the Templates button on
// FREE - so a brand-new free user's only path was "New agent" -> an empty
// draft. This module is the small allow-list that stays open on every plan:
// the same 16 business personas the no-signup /try sandbox offers
// (TrySandbox's PERSONA_GROUPS), whose persona blocks already live
// client-side in lib/edge/demoPersonas.ts. Someone who tried the sandbox as
// "a clinic" and then signed up gets the exact same starting point here.
//
// Verified before shipping this: core has NO plan gate on templates. Picking
// a template only pre-fills the create form's systemPrompt client-side, and
// the resulting POST /api/agents is the same call a blank draft makes (the
// only server-side FREE gates are the agent cap and the 24h edit lock), so
// nothing here promises a capability the backend would then refuse.
//
// Keys are namespaced "starter:<personaId>" so they can never collide with an
// AGENT_TEMPLATES key, and so AgentWorkbench's ?template= deep-link handler
// can tell the two galleries apart with one prefix check.
export const STARTER_TEMPLATE_PREFIX = "starter:";

export interface StarterTemplate {
  /** "starter:<personaId>" - the value carried in /agents?template=<key>. */
  key: string;
  personaId: DemoPersonaId;
  /** en.json `try` namespace key for the display label (the sandbox's own
   * persona chip text, e.g. try_personaKirana -> "Kirana store"). */
  labelKey: string;
  /** Pre-filled agent name (English literal, same as the sandbox seeds). */
  defaultName: string;
  systemPrompt: string;
}

// Mirror of TrySandbox.tsx's PERSONA_GROUPS id -> labelKey pairs. That
// constant is module-private to the sandbox and the sandbox has no reason to
// know a console-side consumer exists, so this is a deliberate copy - keep
// the two in step if a persona is added on either side.
const PERSONA_LABEL_KEYS: Record<DemoPersonaId, string> = {
  kirana: "try_personaKirana",
  boutique: "try_personaBoutique",
  bakery: "try_personaBakery",
  restaurant: "try_personaRestaurant",
  clinic: "try_personaClinic",
  pharmacy: "try_personaPharmacy",
  salon: "try_personaSalon",
  gym: "try_personaGym",
  tuition: "try_personaTuition",
  ca_office: "try_personaCaOffice",
  real_estate: "try_personaRealEstate",
  travel: "try_personaTravel",
  mobile_repair: "try_personaMobileRepair",
  auto_garage: "try_personaAutoGarage",
  contractor: "try_personaContractor",
  photographer: "try_personaPhotographer",
};

// Same order the sandbox's persona picker uses (retail & food, health &
// wellness, learning & professional, trades & services) so the two galleries
// read identically.
const STARTER_ORDER: DemoPersonaId[] = [
  "kirana",
  "boutique",
  "bakery",
  "restaurant",
  "clinic",
  "pharmacy",
  "salon",
  "gym",
  "tuition",
  "ca_office",
  "real_estate",
  "travel",
  "mobile_repair",
  "auto_garage",
  "contractor",
  "photographer",
];

/**
 * The real-workspace prompt skeleton around a persona block. Deliberately NOT
 * demoPersonas.buildDemoSystemPrompt(): that skeleton is the sandbox's, and
 * says so to the model ("no-signup sandbox ... running entirely on this
 * device ... forgets everything when this tab closes") - true there, false
 * for an agent living in a registered workspace with channels and memory.
 * The behaviour rules are otherwise the same ones the sandbox seeds, so the
 * agent someone liked on /try behaves the same way here.
 */
function buildStarterSystemPrompt(personaId: DemoPersonaId): string {
  const { businessLabel, promptBlock } = DEMO_PERSONAS[personaId];
  return `You are a friendly, highly capable AI assistant for ${businessLabel} - a small or medium business in India.

How to behave:
- Answer in the language the person writes in. Indian languages, English, or a mix (Hinglish etc.) are all fine and normal.
- Be concrete and short. Prefer a worked example over a description of what you could do. If they say "draft a reply to a customer asking about delivery", write the actual reply.
- You are good at: customer replies, order and enquiry handling, product descriptions, price lists, polite follow-ups, summarising messages, and translating between Indian languages.
- Sound like a capable colleague, not a chatbot. No filler, no "As an AI language model".

${promptBlock}

What you must be straight about:
- Never invent facts about ${businessLabel} - its prices, stock, staff or policies. If the owner has given you documents or notes, answer from those; otherwise ask for the detail, or clearly mark an example as an example.
- If something needs a human decision (a refund, a discount, a medical or legal question), say so plainly and offer to note it down for the owner instead of guessing.`;
}

export const STARTER_TEMPLATES: StarterTemplate[] = STARTER_ORDER.map((personaId) => ({
  key: `${STARTER_TEMPLATE_PREFIX}${personaId}`,
  personaId,
  labelKey: PERSONA_LABEL_KEYS[personaId],
  defaultName: DEMO_PERSONAS[personaId].defaultAgentName,
  systemPrompt: buildStarterSystemPrompt(personaId),
}));

// Console-gallery (AGENT_TEMPLATES) keys that stay open on every plan, next
// to the persona starters above. The gallery as a whole is lock-badged for
// FREE on /agents/templates; a key listed here keeps its "Use this template"
// button live there. Same verified premise as the header: core has no plan
// gate on templates, so this only decides what the UI offers.
//   - "data-analyst": the "Analyze data" starting point (D3).
//   - the "Start in 1 minute" row on the agents empty state (wave 3, see
//     QUICK_START_TEMPLATE_KEYS in templateAudience.ts): five templates that
//     need no knowledge base, connector, capability or channel - just a
//     conversation - so a brand-new FREE tenant's first useful agent is one
//     click. The same premise applies: picking one only pre-fills the form,
//     and the create call is not plan-gated. templateAudience.test.ts holds
//     the two lists together.
export const OPEN_CONSOLE_TEMPLATE_KEYS: readonly string[] = [
  "data-analyst",
  "explain-it-simply-tutor",
  "resume-cover-letter-tailor",
  "client-call-to-actions",
  "whatsapp-reply-drafter",
  "pre-pr-review-buddy",
];

export function isOpenConsoleTemplate(key: string | null | undefined): boolean {
  return typeof key === "string" && OPEN_CONSOLE_TEMPLATE_KEYS.includes(key);
}

export function isStarterTemplateKey(key: string | null | undefined): boolean {
  return typeof key === "string" && key.startsWith(STARTER_TEMPLATE_PREFIX);
}

export function findStarterTemplate(key: string | null | undefined): StarterTemplate | null {
  if (!isStarterTemplateKey(key)) return null;
  return STARTER_TEMPLATES.find((s) => s.key === key) ?? null;
}
