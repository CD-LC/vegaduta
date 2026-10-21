import type { PageGuideEntry } from "./pageGuideTypes";

// Page guides for the /admin sub-surfaces, the /admin catch-all, /learn and /help.
// Part of the ordered ENTRIES list in pageGuides.ts: this chunk comes last, so
// the /admin catch-all stays below every more specific route.
export const ADMIN_GUIDE_ENTRIES: PageGuideEntry[] = [
  // Admin sub-surfaces, most-specific first — the /admin catch-all sits last.
  // All are adminOnly like the existing admin guides; the pages themselves
  // are permission-gated, so the guide only adds context for whoever reached it.
  {
    match: /^\/admin\/contacts/,
    guide: {
      id: "contacts",
      title: "Your directory of people",
      purpose: "Everyone who has reached your agents — the people behind the channels.",
      steps: [
        "Search or filter the directory.",
        "Open a contact to see the conversations and details attached to them.",
      ],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/inbox/,
    guide: {
      id: "inbox",
      title: "The operator inbox",
      purpose: "Requests your agents raised when they needed a human decision.",
      steps: [
        "Open a request to see exactly what the agent is asking.",
        "Approve or answer it — the agent resumes with your reply.",
      ],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/leads/,
    guide: {
      id: "leads",
      title: "Your pipeline",
      purpose: "Prospects captured from channels and workflows, in one pipeline.",
      steps: ["Review new leads and where they came from.", "Move a lead along the pipeline or hand it to an agent."],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/audit/,
    guide: {
      id: "audit",
      title: "The evidence log",
      purpose: "Every action in this workspace, recorded in order — including the live tail.",
      steps: ["Filter by person, action or time.", "Open a row to see what actually changed."],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/issues/,
    guide: {
      id: "issues",
      title: "Flagged for a look",
      purpose: "Things the system or your agents surfaced that need a human eye.",
      steps: ["Open an issue to see what flagged it.", "Resolve it or hand it back with a note."],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/(access|users)/,
    guide: {
      id: "access",
      title: "Who can do what",
      purpose: "The people on this workspace and what each of them is allowed to do.",
      steps: ["Invite a teammate and pick their role.", "Grant or revoke a permission from their row."],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/integrations/,
    guide: {
      id: "integrations",
      title: "Connect other systems",
      purpose: "Third-party connections such as Google Workspace that give your agents more reach.",
      steps: [
        "Connect an integration and complete its consent flow.",
        "Its tools become available to agents once connected.",
      ],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/approvals/,
    guide: {
      id: "approvals",
      title: "Actions waiting on a yes",
      purpose: "Actions an agent or workflow proposed that only run after a person approves them.",
      steps: [
        "Open a pending approval to see exactly what it would do.",
        "Approve or reject — the workflow waits for your answer.",
      ],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/input-requests/,
    guide: {
      id: "input-requests",
      title: "Questions from your agents",
      purpose: "Questions an agent paused to ask a person before continuing.",
      steps: [
        "Open a request to read the question in context.",
        "Answer it — the paused work picks up from your reply.",
      ],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/data-privacy/,
    guide: {
      id: "data-privacy",
      title: "Privacy requests",
      purpose: "Data requests and privacy controls for the people in this workspace.",
      steps: ["Review open requests and their deadlines.", "Export or erase a person's data from their row."],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/maintenance/,
    guide: {
      id: "maintenance",
      title: "Maintenance windows",
      purpose: "Planned downtime and the notice your users see during it.",
      steps: ["Schedule a window and write the notice users will see.", "The status surface updates from here."],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/payments/,
    guide: {
      id: "payments",
      title: "Payments and transactions",
      purpose: "The payment records behind this workspace's plan and purchases.",
      steps: ["Review recent transactions.", "Plan changes themselves happen under Billing."],
      next: { label: "Open Billing", href: "/billing" },
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/agent-trust/,
    guide: {
      id: "agent-trust",
      title: "Prove who an agent is",
      purpose: "Delegation grants, the signed manifest and the public directory listing for one agent.",
      steps: [
        "Pick an agent at the top. Every card below applies to that agent.",
        "New grant opens the wizard: audience, scopes, token TTL and a grant expiry. A pay:* scope also asks for a bound wallet and a spend cap.",
        "Mint token issues a credential against a grant; Revoke ends it.",
        "Publish to directory lists the agent publicly. View signed manifest shows what callers verify.",
      ],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin\/commerce/,
    guide: {
      id: "agent-commerce",
      title: "Let an agent hold money",
      purpose: "Agent wallets, AP2 mandates and the x402 paid endpoints this agent sells.",
      steps: [
        "Pick an agent at the top. Open wallet creates a wallet, Credit tops it up, and Ledger expands its transactions.",
        "New intent mandate sets a spending envelope. A cart moves through Authorize, Capture, Refund or Revoke, and Events shows its trail.",
        "New offer publishes a paid endpoint at /x402/<slug>. Disable takes it offline.",
      ],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    match: /^\/admin(\/|$)/,
    guide: {
      id: "admin",
      title: "Workspace administration",
      purpose: "Workspace-wide settings: model keys, channels, guardrails and access.",
      steps: [
        "Set up model keys and channels for every agent here.",
        "Review settings before you connect a public channel.",
      ],
      adminOnly: true,
      learnInAcademy: true,
    },
  },
  {
    // Above /learn: first match wins. Labels are tutorPage.* in en.json
    // ("Build my course", "Check answer", "Start review").
    match: /^\/learn\/tutor/,
    guide: {
      id: "learn-tutor",
      title: "Learn anything with a personal tutor",
      purpose: "Type any topic and get a course made for you, with a quiz after each lesson.",
      steps: [
        "Type what you want to learn, pick your level and language, then press Build my course.",
        "Open a lesson, read it, and answer its questions with Check answer.",
        "Questions you miss come back later. Press Start review when some are due.",
      ],
      next: { label: "Back to Help", href: "/help" },
    },
  },
  {
    match: /^\/(help|learn)/,
    guide: {
      id: "help",
      title: "Find an answer",
      purpose: "Written guides and lessons for every part of the product.",
      steps: ["Search for what you are trying to do.", "Follow the guide next to the page it describes."],
      learnInAcademy: true,
    },
  },
];
