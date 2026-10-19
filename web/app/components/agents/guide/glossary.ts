// Plain-language glossary for the agent workbench (2026-09-19).
//
// The workbench labels its sections for an engineer: "PERSONA · SYSTEM
// PROMPT", "MODEL & GENERATION", "TOOLS · MCP", "KNOWLEDGE COLLECTIONS". A
// shop owner creating a first agent stalls on the words before they ever reach
// the controls. Each entry here is one sentence of meaning plus one concrete
// example, written for someone who runs a business, not someone who has read
// an LLM paper.
//
// English literals on purpose: web/messages/*.json is fragile and shared, and
// a t("missing.key") renders the raw key path to the user. Move these into
// the message catalogue in one deliberate pass, not piecemeal.
//
// NO FICTION: every sentence below describes behaviour that exists in the
// product. "onScreen" is the label the person will actually see in the
// workbench, so they can match the word to the place. Checked against
// PlatformLedger.tsx's do-not-claim list - nothing here mentions learning
// from feedback, a secrets vault, speech-language counts, or any speed,
// capacity or uptime figure.

export interface GlossaryTerm {
  key: string;
  /** The everyday word. */
  term: string;
  /** What the workbench calls it, so the person can find it. */
  onScreen: string;
  /** One sentence. */
  meaning: string;
  /** One concrete example. */
  example: string;
}

export const AGENT_GLOSSARY: GlossaryTerm[] = [
  {
    key: "instructions",
    term: "Instructions",
    onScreen: "Persona · System prompt",
    meaning: "The standing orders your agent follows in every conversation: who it is, how it should sound, and what it must never do.",
    example: "\"You answer customers of Asha's Bakery. Be warm and brief. Never promise a delivery time; offer to check with the owner.\"",
  },
  {
    key: "model",
    term: "Model",
    onScreen: "Model & generation",
    meaning: "The AI that actually writes the replies. The hosted model needs no API key, so you can leave this alone.",
    example: "If the badge says \"Platform LLM\", the hosted model is answering and there is nothing to choose.",
  },
  {
    key: "provider",
    term: "Provider",
    onScreen: "Provider",
    meaning: "The company that runs a model. You only need one if you want to pay that company directly with your own key.",
    example: "Someone who already has an OpenAI account can paste that key on the Providers page and pick OpenAI here.",
  },
  {
    key: "intelligence-source",
    term: "Intelligence source",
    onScreen: "Intelligence source",
    meaning: "Where the thinking happens: on the hosted model, inside this device's browser, or on your own provider key.",
    example: "\"On-device\" downloads a small model into this browser and answers from there; it depends on what this device can run.",
  },
  {
    key: "temperature",
    term: "Temperature",
    onScreen: "Temperature",
    meaning: "How much the wording varies from one reply to the next. Low is careful and repeatable, high is more inventive.",
    example: "Keep it low for prices and policies. Raise it for writing festival offers or product descriptions.",
  },
  {
    key: "max-tokens",
    term: "Reply length",
    onScreen: "Max tokens",
    meaning: "The longest reply the agent is allowed to write.",
    example: "If answers stop mid-sentence, raise this. If replies ramble, lower it.",
  },
  {
    key: "tools",
    term: "Tools",
    onScreen: "Tools · MCP",
    meaning: "Actions the agent can take beyond talking. On Auto-select it picks the right one for each message by itself.",
    example: "Looking something up, or sending a message. MCP is simply the standard plug used to connect an outside app.",
  },
  {
    key: "knowledge",
    term: "Knowledge",
    onScreen: "Knowledge collections",
    meaning: "Your own documents, uploaded so the agent answers from them instead of guessing.",
    example: "Upload your price list and returns policy, and \"what is your refund rule?\" is answered from your policy.",
  },
  {
    key: "guardrails",
    term: "Guardrails",
    onScreen: "Guardrails",
    meaning: "Optional safety checks you can switch on for a saved agent. They are off until you turn them on.",
    example: "Screening abusive language, or spotting personal details such as a phone number.",
  },
  {
    key: "approvals",
    term: "Approvals",
    onScreen: "Approvals",
    meaning: "Before a consequential action the agent stops and waits for a person to say yes.",
    example: "A refund waits in your Approvals inbox until you approve or reject it.",
  },
  {
    key: "playground",
    term: "Playground",
    onScreen: "Playground",
    meaning: "The private chat on this page where you test the agent. Customers never see it.",
    example: "Ask it the rudest or vaguest question a customer might send, and see what it does.",
  },
  {
    key: "channels",
    term: "Channels",
    onScreen: "Deploy → channel",
    meaning: "The places your customers reach the agent. WhatsApp, Telegram and Email are on the Free plan.",
    example: "Connect your business WhatsApp number, and customers who message it are answered by the agent.",
  },
  {
    key: "draft",
    term: "Draft",
    onScreen: "Draft",
    meaning: "An agent you are still filling in. Nothing is created until you press Save draft.",
    example: "You can close the page on a draft and lose nothing but what you typed.",
  },
];
