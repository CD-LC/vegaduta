import type { TourStep } from "../types";


// The core tenant walk: agents, workflows, knowledge, shared-with-me.
export const WALK_STEPS: TourStep[] = [
  // --- the tenant walk -------------------------------------------------
  {
    id: "agents",
    titleKey: "agents",
    title: "Tune the AI behind the experience",
    body: "Shape one custom agent with instructions, model, tone, knowledge, tools and guardrails, then improve it from real tests.",
    points: ["Ask AI drafts instructions; you stay in control", "Attach only the knowledge and tools it needs"],
    target: "nav-agents",
    placement: "right",
    illustration: "agents",
    href: "/agents",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  {
    id: "workflows",
    titleKey: "workflows",
    title: "Turn the agent into a working system",
    body: "Arrange triggers, agents, knowledge searches, conditions, approvals and outputs on a visual canvas.",
    points: ["Run it by hand, on a schedule, or by webhook", "Every run is logged step by step"],
    target: "nav-workflows",
    placement: "right",
    illustration: "workflows",
    href: "/workflows",
    audiences: ["member", "tenant-admin"],
    // `simple` since the launch-story rebuild: agent -> workflow ->
    // app/channel is the spine a new B2C signup is being walked through.
    simple: true,
  },
  {
    id: "knowledge",
    titleKey: "knowledge",
    title: "Ground it in what your business knows",
    body: "Upload PDFs, Word files, notes, images or recordings so the agent can search your facts before it answers.",
    points: ["Collections keep internal and public docs apart", "Attach a collection to the agents that need it"],
    target: "nav-knowledge",
    placement: "right",
    illustration: "knowledge",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  {
    id: "shared-with-me",
    // titleKey is "shared", not the id: the id matches the route
    // (/shared-with-me) and the nav marker, while the key stem has to be a
    // legal en.json identifier stem — "shared-with-meTitle" is not one.
    titleKey: "shared",
    // Member-only, and the one step that exists BECAUSE of the audience
    // split rather than in spite of it. A member who was invited into a
    // workspace typically owns nothing on their first login; the honest
    // answer to "where is my work" is this row, and the admin walk never
    // mentioned it. Claims verified against ShareController's API surface
    // in lib/api.ts:2133-2145: grantResourceAccess takes a permissionLevel
    // (RUNNER / VIEWER / EDITOR — hence "run, view or edit"),
    // revokeResourceAccess deletes a grant, and getSharedWithMe is what the
    // page itself lists.
    title: "Work your teammates shared with you",
    body: "Agents and workflows a teammate granted you access to show up here. Nothing appears unless someone invites you.",
    points: ["Run, view or edit, depending on the grant", "Open one straight from this list"],
    target: "nav-shared",
    placement: "right",
    // Drawn FOR this step: one dashed flow running INTO a list of granted
    // rows, the deliberate reverse of channels/tools fanning OUT of an agent
    // you own. It previously carried "agents", which shows an agent you
    // authored - the opposite of what this step is about.
    illustration: "shared-with-me",
    href: "/shared-with-me",
    audiences: ["member"],
    simple: true,
  },
];
