import type { PageGuideEntry } from "./pageGuideTypes";

// Page guides for the everyday workspace pages (home, agents, workflows,
// templates, knowledge, memory, jobs, insights, studio, sharing, skills).
// Part of the ordered ENTRIES list in pageGuides.ts: first match wins, so
// specific routes sit above their parents.
export const WORKSPACE_GUIDE_ENTRIES: PageGuideEntry[] = [
  {
    match: /^\/(home)?$/,
    guide: {
      id: "home",
      title: "Start here",
      purpose: "This is your home screen. The live panel shows the workspace running - each dot is one of your agents.",
      steps: [
        "Read the Live activity panel: each dot is an agent, and calls and conversations stream in as they happen.",
        "Open Agents and create your first agent. It only needs a name.",
        "Open Playground and chat with it privately to see how it answers.",
        "When you are happy with it, connect a channel so real people can reach it.",
      ],
      next: { label: "Create your first agent", href: "/agents" },
      learnInAcademy: true,
      academyTopic: "/orientation",
    },
  },
  {
    match: /^\/agents\/templates/,
    guide: {
      id: "agent-templates",
      title: "Start from a template",
      purpose: "Ready-made agents for common jobs. Picking one fills in the name and instructions for you.",
      steps: [
        "Pick the template closest to what you need.",
        "Change the wording so it talks about your business.",
        "Press Save draft, then test it in the Playground.",
      ],
      next: { label: "Test in Playground", href: "/playground" },
      learnInAcademy: true,
      academyTopic: "/agents",
    },
  },
  {
    // Specific agent sub-surfaces sit above the /agents parent — first match wins.
    match: /^\/agents\/learning/,
    guide: {
      id: "agent-learning",
      title: "Teach an agent from feedback",
      purpose: "Where an agent learns from rewards and retries — and where you control how it does.",
      steps: [
        "Pick an agent to see what it has learned so far.",
        "Read the policies and reward signals before changing anything.",
        "Adjust only after you can say what the change teaches the agent.",
      ],
      learnInAcademy: true,
      academyTopic: "/memory",
    },
  },
  {
    match: /^\/agents\/quality/,
    guide: {
      id: "agent-quality",
      title: "Prove an agent is good",
      purpose: "The evaluation surfaces: datasets, simulations, statistics and the eval gate.",
      steps: [
        "Save a good real run as an eval case — production wins become regression tests.",
        "Read a score with its interval, not as a point — the noise floor says what counts as a real change.",
        "Let the eval gate decide whether a new version promotes.",
      ],
      learnInAcademy: true,
      academyTopic: "/quality",
    },
  },
  {
    match: /^\/agents(\/|$)/,
    guide: {
      id: "agents",
      title: "Create your first agent",
      purpose: "An agent is an AI assistant with a job you describe in plain words.",
      steps: [
        "Type a name. That is the only thing you must fill in.",
        "In the instructions box, say what it should do, the way you would brief a new teammate. You can also leave the default for now.",
        "Press Save draft. Everything else on this page is optional and can wait.",
        "Open Playground and chat with it. Come back and adjust the instructions until the answers sound right.",
      ],
      reassurance: "Nothing goes public when you save. People can only reach an agent after you connect a channel.",
      next: { label: "Test it in Playground", href: "/playground" },
      learnInAcademy: true,
      academyTopic: "/agents",
    },
  },
  {
    match: /^\/workflows\/templates/,
    guide: {
      id: "workflow-templates",
      title: "Start from a workflow template",
      purpose: "Ready-made workflows you can open, read and change.",
      steps: [
        "Pick a template that matches your situation.",
        "Give it a name and create it. It opens on the canvas.",
        "Press Run now to watch it work before you change anything.",
      ],
      learnInAcademy: true,
      academyTopic: "/workflows",
    },
  },
  {
    match: /^\/workflows\/[^/]+\/runs/,
    guide: {
      id: "workflow-runs",
      title: "Read a run",
      purpose: "Every time this workflow ran, with what each step did — and the metrics behind it.",
      steps: [
        "Open a run to see its steps in order.",
        "A failed step shows its error. Fix that node on the canvas and run again.",
      ],
      learnInAcademy: true,
      academyTopic: "/workflows",
    },
  },
  {
    match: /^\/workflows\/[^/]+/,
    guide: {
      id: "workflow-canvas",
      title: "Build your first workflow",
      purpose: "A workflow is a chain of steps. It starts at the Trigger and follows the lines.",
      steps: [
        "Easiest: describe what you want in the describe box and press Build with AI. It draws the steps for you.",
        "Or by hand: drag a node from the left, then drag from the dot on one node's right edge to the dot on the next node's left edge.",
        "Press Save Draft, then Run now. Watch each step light up on the right.",
        "Press Validate if something is wrong. It lists exactly what to fix. Activate is only needed for a webhook or a schedule.",
      ],
      reassurance: "Run now is a test run. Nothing runs by itself until you Activate the workflow.",
      learnInAcademy: true,
      academyTopic: "/workflows",
    },
  },
  {
    match: /^\/workflows$/,
    guide: {
      id: "workflows",
      title: "Create your first workflow",
      purpose: "Workflows chain several steps together: agents, conditions, approvals and more.",
      steps: [
        "Press + New Workflow.",
        "Type a name. That is the only thing you must fill in.",
        "Press Create workflow. It opens on the canvas with a Trigger already in place.",
      ],
      reassurance: "If you have not made an agent yet, do that first. Most workflows hand work to an agent.",
      next: { label: "Browse workflow templates", href: "/workflows/templates" },
      learnInAcademy: true,
      academyTopic: "/workflows",
    },
  },
  {
    match: /^\/templates/,
    guide: {
      id: "templates",
      title: "Use a community template",
      purpose: "Agents and workflows shared by other people, reviewed before they appear here.",
      steps: [
        "Open a template to read what it does.",
        "Add it to your workspace. You get your own copy.",
        "Edit your copy freely. The original is not affected.",
      ],
      learnInAcademy: true,
    },
  },
  {
    match: /^\/knowledge/,
    guide: {
      id: "knowledge",
      title: "Teach an agent your documents",
      purpose: "Upload your own files so an agent answers from them instead of guessing.",
      steps: [
        "Create a collection. Think of it as a folder.",
        "Upload your documents into it and wait until they finish processing.",
        "Open your agent and attach the collection to it.",
      ],
      next: { label: "Open Agents", href: "/agents" },
      learnInAcademy: true,
      academyTopic: "/knowledge",
    },
  },
  {
    match: /^\/memory/,
    guide: {
      id: "memory",
      title: "What the assistant remembers",
      purpose: "The memory your workspace has saved — review and edit what is remembered.",
      steps: [
        "Edit the fixed blocks — persona, your profile, project notes — and save. They go into every conversation.",
        "A named project note only joins the conversation when the project is mentioned.",
        "Facts below are things agents saved with remember_fact. Edit or delete any of them.",
      ],
      learnInAcademy: true,
      academyTopic: "/memory",
    },
  },
  {
    match: /^\/quick-task/,
    guide: {
      id: "quick-task",
      title: "Run a one-off task",
      purpose: "Get one job done without building an agent first.",
      steps: ["Describe the task in plain words.", "Run it and read the result.", "Past tasks are kept in History."],
      learnInAcademy: true,
      academyTopic: "/scheduled",
    },
  },
  {
    match: /^\/jobs/,
    guide: {
      id: "jobs",
      title: "Background work",
      purpose: "Long-running and scheduled work shows up here, with its status.",
      steps: [
        "Open a row to see its progress and result.",
        "A failed job shows why it failed, so you can fix the agent or workflow behind it.",
      ],
      learnInAcademy: true,
      academyTopic: "/scheduled",
    },
  },
  {
    match: /^\/insights/,
    guide: {
      id: "insights",
      title: "See how your AI workers are doing",
      purpose: "AI calls, how many succeeded, what they cost (an estimate) and which jobs are running, in one place.",
      steps: [
        "Pick 7, 30 or 90 days at the top.",
        "Read the sentence above each chart, or switch a chart to a table.",
        "Open Jobs from the job status card to see each run.",
      ],
      reassurance: "Cost is an estimate from model list prices, not an invoice.",
      next: { label: "Open Jobs", href: "/jobs" },
    },
  },
  {
    match: /^\/studio/,
    guide: {
      id: "studio",
      title: "Your generated media",
      purpose: "Images, audio and other media your agents produced, in one place.",
      steps: ["Open an item to preview it.", "Download the ones you want to keep."],
      learnInAcademy: true,
    },
  },
  {
    match: /^\/shared-with-me/,
    guide: {
      id: "shared",
      title: "Shared with you",
      purpose: "Agents, workflows and other items teammates have shared with you.",
      steps: ["Open an item to use it.", "What you can change depends on the access the owner gave you."],
      learnInAcademy: true,
    },
  },
  {
    match: /^\/skills/,
    guide: {
      id: "skills",
      title: "Packaged expertise",
      purpose: "Skills are ready-made know-how an agent loads when it needs it.",
      steps: [
        "Browse the skills installed in this workspace.",
        "Attach one to the agent that needs it from the agent's own page.",
      ],
      learnInAcademy: true,
      academyTopic: "/memory",
    },
  },
];
