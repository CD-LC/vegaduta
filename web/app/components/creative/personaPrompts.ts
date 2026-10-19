import type { LucideIcon } from "lucide-react";
import {
  GraduationCap, Store, Code2, Server, LineChart, Database, Headphones,
  ClipboardList, ShieldAlert, Laptop, Palette, Newspaper, Smile,
  Truck, HardHat, Megaphone,
} from "lucide-react";

// Persona/role packs — "who is this platform for", not "what industry is this
// business in" (that's industryPrompts.ts, INDUSTRY_CREATIVE_PACKS). A visitor
// picks how they'd actually use the platform (student, developer, executive,
// call-center operator, ...) rather than what their company sells.
//
// Every `solution` line maps to a real, shipped capability — same discipline
// as DemoLanding.tsx's SecuritySection (no fabricated claims). `recipesId`
// cross-links to a real id in help/workflow-recipes/recipes-index.ts's
// INDUSTRY_INDEX where a close match exists, so "see workflow examples" can
// deep-link into /help/workflow-recipes/[industry] instead of duplicating
// that content here. Left undefined where no close match exists.

export type PersonaPack = {
  id: string;
  name: string;
  icon: LucideIcon;
  /** One-liner: the pain this persona actually has today. */
  problem: string;
  /** One-liner: the real platform capability that addresses it. */
  solution: string;
  /** 2-3 short capability tags shown as badges, e.g. ["MCP tools", "Code review"] */
  capabilityTags: string[];
  /** General "build me X" prompts for the Magic Studio suggestion-chip row. */
  suggestedPrompts: string[];
  /** Optional deep link into /help/workflow-recipes/[id] where a close match exists. */
  recipesId?: string;
};

export const PERSONA_PACKS: PersonaPack[] = [
  {
    id: "students",
    name: "Students",
    icon: GraduationCap,
    problem: "Notes are scattered across three notebooks and a camera roll, and revising means re-reading everything from scratch.",
    solution: "An agent grounded in your own uploaded notes and syllabus (Knowledge collections) answers questions, quizzes you, and explains in any of 22 Indian languages via Sarvam.",
    capabilityTags: ["Knowledge RAG", "Indian-language replies", "Practice questions"],
    suggestedPrompts: [
      "Explain this topic from my notes in Hindi and quiz me on it.",
      "Turn my syllabus PDF into a revision plan.",
      "Generate 10 practice questions from this chapter.",
      "Summarise my class notes into flashcards.",
    ],
    recipesId: "students",
  },
  {
    id: "smb",
    name: "SMB owners",
    icon: Store,
    problem: "Every day brings the same 'what's the price / are you open?' messages, one customer at a time, on WhatsApp.",
    solution: "VegaDūta's flagship use case — a WhatsApp agent that takes orders, collects UPI payment and sends booking reminders, with every price and refund still yours to confirm.",
    capabilityTags: ["WhatsApp orders", "UPI payments", "Reminders"],
    suggestedPrompts: [
      "Answer WhatsApp orders and take UPI payment for my shop.",
      "Send a booking reminder to customers a day before their appointment.",
      "Reply to 'are you open today?' automatically, in my customers' language.",
    ],
    recipesId: "small-business",
  },
  {
    id: "developers",
    name: "Developers",
    icon: Code2,
    problem: "Wiring an agent to your own tools and models means another vendor SDK, and 'automate this' still means writing glue code.",
    solution: "One-click activate the free tools in the official MCP registry (paste a key for the rest), bring your own LLM key (Anthropic, OpenAI, Gemini, Grok, Meta, OpenRouter, Vertex), and build the automation itself as a no-code, Temporal-backed workflow with webhook triggers.",
    capabilityTags: ["MCP registry", "BYOK any LLM", "Workflow engine"],
    suggestedPrompts: [
      "Build an agent that watches a webhook and posts a Slack or Discord summary.",
      "Connect my GitHub repo as an MCP tool and review new PRs.",
      "Route CI failure notifications to the right channel automatically.",
    ],
    recipesId: "software-teams",
  },
  {
    id: "technical-it",
    name: "Technical / IT teams",
    icon: Server,
    problem: "Your own internal tools and APIs aren't reachable by any AI system without custom integration work for each one.",
    solution: "Expose your internal tools and APIs as an MCP server through the platform's inbound gateway, route internal alerts to Slack or Discord, add content-moderation guardrails with a human review queue, and get a full audit trail on every workflow run.",
    capabilityTags: ["MCP inbound gateway", "Guardrails review queue", "Audit trail"],
    suggestedPrompts: [
      "Expose our internal ticketing API as an MCP server other agents can call.",
      "Post this alert to our internal Slack and Discord channels.",
      "Add a human review queue before any flagged response goes out.",
    ],
  },
  {
    id: "executives",
    name: "Executives",
    icon: LineChart,
    problem: "AI spend and agent activity are invisible until the invoice arrives, and nothing stops an agent from acting without sign-off.",
    solution: "See LLM spend and call logs in one place, gate any workflow step behind human approval, and view every customer conversation — WhatsApp, voice, Slack, Discord, Telegram, Instagram, X DMs and email — from a single screen.",
    capabilityTags: ["Spend visibility", "Approval gates", "Omnichannel view"],
    suggestedPrompts: [
      "Show this month's LLM spend broken down by agent.",
      "Require my approval before this workflow sends anything external.",
      "Give me one view of every customer conversation across every channel.",
    ],
  },
  {
    id: "data-operators",
    name: "Data operators",
    icon: Database,
    problem: "Back-office data pipelines mean manually pulling from five systems and re-keying the same record into three more.",
    solution: "Automate the pipeline as a workflow, pull data from external systems with MCP tool-calling, and hold every write or submit behind a human-approval gate.",
    capabilityTags: ["Workflow automation", "MCP tool-calling", "Approval gate"],
    suggestedPrompts: [
      "Build a workflow that pulls yesterday's records from our CRM and reconciles them.",
      "Draft every field for this back-office form, then wait for me to approve.",
      "Pull this week's data from three systems into one report.",
    ],
    recipesId: "data-entry",
  },
  {
    id: "call-centers",
    name: "Call centers",
    icon: Headphones,
    problem: "Callers speak a dozen different languages, and every call still needs a manual CRM update written up afterward.",
    solution: "A real voice agent (Twilio-backed) listens and replies out loud, with live transcription and an automatic CRM update after every call. Spoken-language coverage on calls follows the Twilio relay; it is not a per-language promise.",
    capabilityTags: ["Voice agent", "Live transcription", "Auto CRM update"],
    suggestedPrompts: [
      "Set up a voice agent that answers calls and updates the CRM afterward.",
      "Transcribe this call and summarise the customer's issue.",
      "Have the voice agent greet callers and log each call.",
    ],
    recipesId: "call-center",
  },
  {
    id: "data-entry",
    name: "Data entry",
    icon: ClipboardList,
    problem: "Retyping 400 invoice lines from a PDF into a supplier portal takes two days, and one transposed digit rejects the payment.",
    solution: "An agent reads the source document, drafts every field, and fills the form through a browser-automation MCP tool — then stops for a human to review and click submit.",
    capabilityTags: ["Browser-automation MCP", "Field drafting", "Human submits"],
    suggestedPrompts: [
      "Read this invoice and draft the supplier portal form for me to review.",
      "Fill this application form from the scanned document, then wait for my approval.",
      "Re-key this customer record across three systems, checked once.",
    ],
    recipesId: "data-entry",
  },
  {
    id: "incident-management",
    name: "Incident management",
    icon: ShieldAlert,
    problem: "Alerts arrive faster than anyone can read them, and the same incident gets re-triaged four times under four different tickets.",
    solution: "Triage and de-duplicate the alert flood, escalate with a written summary posted to Slack or Discord for the on-call human (never auto-resolved), and route anything flagged through a guardrails moderation review queue with a full audit trail.",
    capabilityTags: ["Alert triage", "Human escalation", "Audit trail"],
    suggestedPrompts: [
      "Triage and de-duplicate this batch of alerts, then summarise for the on-call analyst.",
      "Escalate this incident with a written summary — don't auto-resolve it.",
      "Show me the audit trail for every action taken on this incident.",
    ],
    recipesId: "security-ops",
  },
  {
    id: "freelancers",
    name: "Freelancers",
    icon: Laptop,
    problem: "You're the sales team, the support desk and the accounts department, and the invoice you meant to chase on Sunday still hasn't gone out.",
    solution: "One unified inbox instead of five — WhatsApp, email, Instagram and X DMs together — invoice chasing with a Razorpay UPI payment link attached, and quotes drafted from your own rate card, never invented.",
    capabilityTags: ["Unified inbox", "UPI invoice chasing", "Rate-card quotes"],
    suggestedPrompts: [
      "Chase this overdue invoice and attach a UPI payment link.",
      "Draft a quote for this enquiry from my own rate card.",
      "Pull my WhatsApp, email, Instagram and X DMs into one inbox.",
    ],
    recipesId: "freelancer",
  },
  {
    id: "content-creators",
    name: "Content creators",
    icon: Palette,
    problem: "A festive poster or a 15-second promo video used to mean hiring a designer or an editor for something that needed to ship today.",
    solution: "Generate images live today (Gemini + Vertex AI) and short videos (Vertex AI Veo — short clips even without an account, longer on paid plans), with prompts tailored to your industry.",
    capabilityTags: ["Image generation", "Video generation (Veo)", "Industry prompts"],
    suggestedPrompts: [
      "A festive poster for my shop's weekend sale.",
      "A 15-second product showcase video.",
      "An Instagram carousel cover for my new collection.",
    ],
  },
  {
    id: "news-media",
    name: "News & media agents",
    icon: Newspaper,
    problem: "A research brief means opening thirty tabs, reading all afternoon, and losing the source links by the time anyone questions a claim.",
    solution: "MCP web-search and research tools fan out across sources in parallel, then synthesise one fact-grounded brief — the agent cites what it found, it doesn't invent quotes — and the finished piece can publish straight to X/Twitter or Slack.",
    capabilityTags: ["MCP web search", "Parallel research", "Cited drafting"],
    suggestedPrompts: [
      "Research this topic across multiple sources and write a cited brief.",
      "Fan out across these five URLs and synthesise one summary with sources.",
      "Monitor X/Twitter for mentions of this story and summarise the thread.",
    ],
    recipesId: "web-monitoring",
  },
  {
    id: "shipping-logistics",
    name: "Shipping & logistics",
    icon: Truck,
    problem: "Half the day goes to 'where is my order?' messages while dispatch sheets, driver updates and COD amounts live in three different places — and the customer only hears from you AFTER they complain about a late delivery.",
    solution: "A WhatsApp agent answers order-status questions in the customer's own language, webhook-triggered workflows push proactive dispatch, out-for-delivery and delay updates the moment status changes (before the complaint, not after), delivery exceptions get triaged with a human deciding the make-good, and COD/UPI collection notes land in one audit-trailed record per shipment.",
    capabilityTags: ["WhatsApp status agent", "Webhook-triggered updates", "Exception triage", "Indian-language replies"],
    suggestedPrompts: [
      "Answer 'where is my order?' on WhatsApp from our shipment data.",
      "Send every customer a proactive update when their parcel goes out for delivery.",
      "Triage today's failed deliveries and draft the reschedule messages for my approval.",
      "Summarise this week's delivery exceptions by route and reason.",
    ],
    recipesId: "logistics",
  },
  {
    id: "construction",
    name: "Construction & contractors",
    icon: HardHat,
    problem: "Site enquiries arrive on calls and WhatsApp at all hours, quotes take days because rates live in an old spreadsheet, clients call for progress updates mid-pour, and chasing material vendors eats the site engineer's afternoon.",
    solution: "An agent qualifies enquiries and books site visits on WhatsApp or a real voice call, drafts quotes strictly from YOUR uploaded rate card (Knowledge collections — nothing invented, and nothing goes out without your approval), sends clients scheduled progress updates in their language, and runs vendor follow-up workflows for material orders with every commitment gated behind you.",
    capabilityTags: ["Enquiry → site visit", "Rate-card quotes (approval-gated)", "Client progress updates", "Vendor follow-ups"],
    suggestedPrompts: [
      "Qualify this enquiry and offer three site-visit slots on WhatsApp.",
      "Draft a quote for this 2BHK interior job from my rate card, then wait for my approval.",
      "Send my client a weekly progress update with what's completed and what's next.",
      "Chase the cement vendor for tomorrow's delivery confirmation.",
    ],
    recipesId: "manufacturing",
  },
  {
    id: "media-marketing",
    name: "Media & marketing",
    icon: Megaphone,
    problem: "Every campaign needs creatives, copy in three languages, posts on five channels and answers in the DMs — and by the time the report is assembled by hand, the moment to fix an underperforming ad has already passed.",
    solution: "Generate campaign images and short promo videos in-platform (Gemini + Vertex AI Veo), draft ad copy and captions in the languages your model supports, publish to X/Twitter and Slack through MCP tools with your sign-off on every post, pull the brand's WhatsApp, Instagram and X DMs into one inbox, and run ad-spend guardrail workflows where the agent proposes budget moves and a human approves them.",
    capabilityTags: ["Image & video generation", "Multilingual copy", "MCP publishing", "Ad-spend guardrails"],
    suggestedPrompts: [
      "A campaign poster and 15-second promo video for our Diwali sale.",
      "Draft this ad's caption in English, Hindi and Kannada, then wait for my sign-off.",
      "Pull our Instagram and X DMs into one inbox and draft replies in our brand voice.",
      "Watch this campaign's leads and send every new one a WhatsApp follow-up.",
    ],
    recipesId: "advertising",
  },
  {
    id: "everyday",
    name: "Everyday / personal use",
    icon: Smile,
    problem: "Bills land wherever they land, renewals get remembered two days late, and you're the only backup for your own memory.",
    solution: "The same real agent and sandbox anyone can try at /try — image generation, and a general assistant for personal admin like bills, reminders and translations.",
    capabilityTags: ["Real agent sandbox", "Image generation", "Personal admin"],
    suggestedPrompts: [
      "Remind me before this bill is due and file it once it's paid.",
      "Translate this message and draft a polite reply.",
      "Generate an image for my kid's birthday invite.",
    ],
    recipesId: "everyday-life",
  },
];

export function findPersonaPack(id: string): PersonaPack | undefined {
  return PERSONA_PACKS.find((p) => p.id === id);
}
