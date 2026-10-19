import type { PageGuideEntry } from "./pageGuideTypes";

// Page guides for providers, plans, channels, tools, build, edge, playground,
// usage/billing and the first admin surfaces (guardrails, MCP, branding). Part of
// the ordered ENTRIES list in pageGuides.ts: first match wins, so specific routes
// sit above their parents.
export const TOOLS_GUIDE_ENTRIES: PageGuideEntry[] = [
  {
    match: /^\/providers/,
    guide: {
      id: "providers",
      title: "Power your agents",
      purpose: "The model providers your agents run on — add a key, and its models become available.",
      steps: [
        "Add a provider and paste its key. Keys are stored encrypted.",
        "Pick which model each agent uses from that agent's own page.",
      ],
      learnLessonId: "l3-2",
    },
  },
  {
    match: /^\/features/,
    guide: {
      id: "features",
      title: "What each plan includes",
      purpose: "The plan comparison — Free, Premium and Unlimited side by side, with your own live limits marked.",
      steps: ["Compare the columns to see what each tier unlocks.", "Your own plan's column shows your live limits."],
      next: { label: "Open Billing", href: "/billing" },
      learnLessonId: "l4-9",
    },
  },
  {
    match: /^\/data-usage/,
    guide: {
      id: "data-usage",
      title: "Where your data lives",
      purpose: "Each system under your workspace — what it stores for you and how much of it there is.",
      steps: [
        "Read each card: the system, what it holds, and its live stat where one exists.",
        "A system marked available but not engaged is provisioned for you and simply unused.",
      ],
      learnLessonId: "l22-7",
    },
  },
  {
    match: /^\/add-ons/,
    guide: {
      id: "add-ons",
      title: "Add extras to your install",
      purpose: "Optional extras such as web search, document reading, the Assistant's browser and free local models.",
      steps: [
        "Read a card: what it adds, how much disk and memory it needs, and whether it is free.",
        "Press Turn on and confirm. Models download by themselves; the others are started by VegaDūta Setup on your computer.",
        "Watch the status badge: On means it is running. Waiting means your choice is saved but not applied yet.",
      ],
      reassurance: "Nothing is installed until you confirm, and you can change your mind later.",
      next: { label: "Set up a local model", href: "/providers/local-model" },
    },
  },
  {
    match: /^\/capabilities\/decision/,
    guide: {
      id: "decision-providers",
      title: "Decision providers",
      purpose: "The decision layer this workspace uses — pick which provider is active.",
      steps: ["Read what each provider does.", "Activate the one you want; its settings sit beside it."],
      learnLessonId: "l18-5",
    },
  },
  {
    match: /^\/admin\/channels/,
    guide: {
      id: "channels",
      title: "Let people reach your agent",
      purpose: "A channel connects an agent to a place people already message, such as WhatsApp, Telegram or email.",
      steps: [
        "Make sure the agent answers well in the Playground first.",
        "Pick a channel and follow its setup steps.",
        "Choose which agent answers on it, then send a test message from your own phone or inbox.",
      ],
      adminOnly: true,
      next: { label: "Test in Playground first", href: "/playground" },
      learnLessonId: "l3-10",
    },
  },
  {
    // Specific: /tools/credentials is its own surface and must sit above the
    // /tools parent — first match wins.
    match: /^\/tools\/credentials/,
    guide: {
      id: "tool-credentials",
      title: "Keys for your MCP servers",
      purpose: "The API keys and tokens for each connected MCP server, one card per server.",
      steps: [
        "Press Edit credentials on a server's card to set or rotate what it sends.",
        "Clear credentials removes the stored values — the server stays registered and enabled.",
        "Built-in servers are listed read-only: their credentials are wired at deployment, not here.",
      ],
      learnLessonId: "l17-3",
    },
  },
  {
    match: /^\/tools/,
    guide: {
      id: "tools",
      title: "Give an agent abilities",
      purpose: "Tools let an agent do things, such as search the web or call another system, instead of only talking.",
      steps: [
        "Browse what is available. You do not need any tool for a first agent.",
        "When you need one, switch it on for that agent from the agent's own page.",
        "Tools that reach your own systems ask for a key. Store it under Secrets.",
      ],
      learnLessonId: "l3-6",
    },
  },
  {
    match: /^\/secrets/,
    guide: {
      id: "secrets",
      title: "Store keys safely",
      purpose: "Passwords and API keys your tools need. They are stored encrypted.",
      steps: [
        "Add a secret with a name you will recognise.",
        "Pick that secret when a tool or connector asks for a key.",
      ],
      learnLessonId: "l17-3",
    },
  },
  {
    match: /^\/code-intel/,
    guide: {
      id: "code-intel",
      title: "Ask questions about a codebase",
      purpose: "Connect a repository so agents can answer questions about its code.",
      steps: ["Add a repository.", "Wait until indexing finishes.", "Ask your question."],
      learnLessonId: "l6-1",
    },
  },
  // /build is the Build Center (a brief in, an envoy out); the App Builder is
  // its second tab and keeps /build/github. Specific routes first.
  {
    match: /^\/build\/github/,
    guide: {
      id: "build",
      title: "Build an app",
      purpose: "Describe an app and have it generated for you.",
      steps: ["Describe what you want in plain words.", "Review what was generated.", "Ask for changes until it fits."],
      learnLessonId: "l18-3",
    },
  },
  {
    match: /^\/build\/[^/]+/,
    guide: {
      id: "build-envoy",
      title: "Read your envoy",
      purpose: "One envoy you applied: what it does, what it still needs, and how far it is trusted.",
      steps: [
        "Check what it still needs. A missing item has a button that takes you to the exact control to fix it.",
        "Rehearse it to see how it behaves before it talks to anyone. Rehearsals are labelled as such.",
        "Watch the proof ledger to see how it scores as it changes.",
        "Trust moves up one step at a time and only when the evidence supports it. A problem moves it back down.",
      ],
      next: { label: "Back to the Build Center", href: "/build" },
      learnLessonId: "l16-9",
    },
  },
  {
    match: /^\/build/,
    guide: {
      id: "build-center",
      title: "Build with a brief",
      purpose: "Say what you want done in your own words. You get a plan you can read before anything is created.",
      steps: [
        "Write what you want in the box, in any language. Add who it is for or where it should talk only if you want to.",
        "Read the plan. It shows what it would set up and what it still needs from you, such as keys or a channel.",
        "Apply it when it looks right. Nothing is created before that.",
        "Want code instead of an assistant? Use the App Builder tab.",
      ],
      reassurance: "Planning creates nothing. Only applying does.",
      next: { label: "Start from a template instead", href: "/templates" },
      learnLessonId: "l16-9",
    },
  },
  {
    match: /^\/edge/,
    guide: {
      id: "edge",
      title: "Run AI on this device",
      purpose: "Download a model so replies are produced on this device instead of a server.",
      steps: [
        "Start with the smallest model. It downloads fastest and runs on most devices.",
        "Keep this tab open until the download finishes.",
        "If this device cannot run a model, nothing breaks. The hosted model keeps answering.",
      ],
      edgeOnly: true,
      learnLessonId: "l5-14",
    },
  },
  {
    match: /^\/code(\/|$)/,
    guide: {
      id: "code",
      title: "Work on a folder with a local assistant",
      purpose: "An assistant that reads and edits a folder you choose. Files stay on this device.",
      steps: [
        "Choose a folder and allow access when the browser asks.",
        "Ask for a change in plain words.",
        "Review every edit before you accept it.",
      ],
      edgeOnly: true,
      learnLessonId: "l18-4",
    },
  },
  {
    match: /^\/playground/,
    guide: {
      id: "playground",
      title: "Test an agent privately",
      purpose: "Chat with your agent before anyone else can. Nothing here is sent to customers.",
      steps: [
        "Pick an agent. If the list is empty, create one first.",
        "Ask it the questions your customers would really ask.",
        "If an answer is off, change the agent's instructions and try again.",
      ],
      next: { label: "Edit the agent", href: "/agents" },
      learnLessonId: "l22-3",
    },
  },
  {
    match: /^\/clips/,
    guide: {
      id: "clips",
      title: "Turn a still image into a clip",
      purpose: "Clip Studio renders a pan-and-zoom video clip from an image, entirely in your browser.",
      steps: [
        "Pick a generated image or upload one — an upload is read locally and never leaves your device.",
        "Choose the camera move, then set duration, frame rate and output size.",
        "Press Create clip — it records in real time, so keep this tab visible — then Download the result.",
      ],
      learnLessonId: "l18-6",
    },
  },
  {
    match: /^\/files/,
    guide: {
      id: "files",
      title: "Files your agents made",
      purpose: "Documents and other files produced by agents and workflow runs.",
      steps: ["Open or download a file.", "Remove the ones you no longer need."],
      learnLessonId: "l18-6",
    },
  },
  {
    match: /^\/usage/,
    guide: {
      id: "usage",
      title: "See what you have used",
      purpose: "How many tokens your agents have used.",
      steps: [
        "See what your agents used over the last 30 days.",
        "Where a limit applies, the bar shows how close you are to it.",
      ],
      learnLessonId: "l17-4",
    },
  },
  {
    match: /^\/(billing|admin\/billing)/,
    guide: {
      id: "billing",
      title: "Plans and payment",
      purpose: "Your current plan, what it includes and how to change it.",
      steps: [
        "Compare the plans.",
        "Pick one and complete the payment.",
        "The new limits apply as soon as payment succeeds.",
      ],
      learnLessonId: "l4-9",
    },
  },
  {
    match: /^\/admin\/guardrails/,
    guide: {
      id: "guardrails",
      title: "Set safety rules",
      purpose: "Rules that check what goes into and comes out of your agents.",
      steps: [
        "Start with one rule that matters to you.",
        "Test the agent in the Playground to see the rule act.",
        "Add more only when you see a need.",
      ],
      adminOnly: true,
      learnLessonId: "l4-8",
    },
  },
  {
    match: /^\/admin\/mcp-servers/,
    guide: {
      id: "mcp-servers",
      title: "Connect an MCP server",
      purpose: "MCP servers give agents extra tools from other systems.",
      steps: [
        "Add the server's address.",
        "Check that its tools are listed.",
        "Switch those tools on for the agents that need them.",
      ],
      adminOnly: true,
      learnLessonId: "l4-1",
    },
  },
  {
    match: /^\/admin\/branding/,
    guide: {
      id: "branding",
      title: "Make it look like yours",
      purpose: "Your logo and colours for this workspace.",
      steps: ["Upload a logo.", "Pick your colours.", "Save your changes."],
      adminOnly: true,
      learnLessonId: "l17-8",
    },
  },
];
