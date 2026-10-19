import {
  LayoutDashboard,
  LayoutTemplate,
  Workflow,
  Bot,
  BookOpen,
  Blocks,
  Radio,
  CalendarClock,
  FileDiff,
  GitBranch,
  Vault,
  Wand2,
  CheckCircle2,
  ClipboardList,
  Database,
  Users,
  Clapperboard,
  Cpu,
  FolderCode,
  Search,
  Hammer,
  Cable,
  Plug,
  Settings2,
  BookMarked,
  BookUser,
  Inbox,
  Target,
  BrainCircuit,
  Globe,
  Network,
  Table2,
  Library,
  Sparkles,
  LifeBuoy,
  PackagePlus,
} from "lucide-react";

import type { NavSection } from "./navTypes";

// The authoring half of the primary navigation (HOME, BUILD, Library, CONNECT,
// AI & Engine, Configuration). Assembled with the operating half into NAV_SECTIONS
// in navSections.ts, which documents the blueprint's conventions.

export const AUTHOR_NAV_SECTIONS: NavSection[] = [
  {
    labelKey: null,
    cat: "var(--cat-data)",
    adminOnly: false,
    items: [
      {
        href: "/home",
        labelKey: "railHome",
        icon: LayoutDashboard,
        exact: true,
        badge: null,
        badgeVariant: null,
        adminHidden: true,
        adminOnly: false,
      },
    ],
  },
  // 1. BUILD — what you author. Mirrors the tour's "1. Build - Workflows,
  // Agents, Knowledge Base" step, which is why Knowledge lives here now
  // rather than in the old catch-all Resources group.
  {
    labelKey: "navGroupBuild",
    cat: "var(--cat-assistant)",
    adminOnly: false,
    icon: Hammer,
    items: [
      // /assistant - one box for a laptop (docs/architecture/PERSONAL-ASSISTANT-SURFACE-2026-10-17.md):
      // the per-user Assistant agent behind a single composer. Leads BUILD because it is the shortest
      // path from "signed in" to "something done"; the Agents page below still shows the same agent.
      {
        href: "/assistant",
        labelKey: "railAssistant",
        icon: Sparkles,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      {
        href: "/agents",
        labelKey: "railAgents",
        icon: Bot,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // /connect - where an agent is reached (Telegram, WhatsApp, email, ...) and what it can read.
      {
        href: "/connect",
        labelKey: "railConnect",
        icon: Plug,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      {
        href: "/workflows",
        labelKey: "railFlows",
        icon: Workflow,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Public agent directory (/directory, core/agents/directory) - the
      // A2A-discoverable listing of agents this deployment's tenants chose
      // to publish, each with its signed capability manifest. Same "browse
      // what exists" shape as Community Templates directly above it; the
      // page is in PUBLIC_ROUTES so it renders for signed-out visitors too.
      {
        href: "/directory",
        labelKey: "railDirectory",
        icon: Globe,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // What the assistant remembers about the signed-in user - editable (core /api/memory).
      {
        href: "/memory",
        labelKey: "railMemory",
        icon: BrainCircuit,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Agent Skills (/skills, core com.agenticai.core.skills) - packaged expert
      // instructions agents load on demand. Directly under Knowledge because it
      // is the same kind of thing from the other side: Knowledge is WHAT an
      // agent can look up, Skills are HOW it should do a task. Not adminOnly -
      // every member can browse what their agents know how to do; writes are
      // workspace-admin only, enforced server-side and mirrored in the page.
      {
        href: "/skills",
        labelKey: "railSkills",
        icon: BookMarked,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Skill library (/skills/library) - review queue for learned skills, import/export, per-agent enablement.
      {
        href: "/skills/library",
        labelKey: "railSkillLibrary",
        icon: BookMarked,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Data Tables (/data-tables, core com.agenticai.core.tables) - spreadsheet-like tables workflows and agents
      // read and write to keep state between runs. The backend flag defaults OFF; the page then says so.
      {
        href: "/data-tables",
        labelKey: "railDataTables",
        icon: Table2,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_WORKFLOWS_ADMIN",
      },
      // Scheduled tasks (/scheduled, core agents/scheduledtask) - prompts an agent runs by itself on a schedule
      // ("every weekday at 8am summarise my email"). Next to Jobs: same background-run family. Flag-gated (see the
      // scheduledTasksEnabled filter below), so a flag-off deployment has no dead link.
      {
        href: "/scheduled",
        labelKey: "railScheduledTasks",
        icon: CalendarClock,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Coding tasks (/coding, core agents/coding) - an agent's model edits a repository in an isolated sandbox until
      // the tests pass, then a person approves the diff. Flag-gated (see the codingTasksEnabled filter below), so a
      // flag-off deployment has no dead link.
      {
        href: "/coding",
        labelKey: "railCodingTasks",
        icon: FileDiff,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      {
        href: "/research",
        labelKey: "railResearch",
        icon: Search,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Studio hub (Edge Wave 1 surfacing, 2026-08-05) - the discoverable
      // front door for everything the platform generates: on-device Clip
      // Studio (/clips previously had NO nav entry at all), image generation
      // via agent chat, and the honest paid-only AI-video story. Lives in
      // BUILD because it's a creation surface ("what you author"), right
      // after Quick Task, the other one-shot creation entry.
      {
        href: "/studio",
        labelKey: "railStudio",
        icon: Clapperboard,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // App Builder — "say it, watch it build" console for the app-builder
      // microservice (non-technical prompt-to-running-app). Placed right
      // after Code Intel: same shape (a console for a sibling backend
      // service), same section.
      {
        href: "/build",
        labelKey: "railBuild",
        icon: Wand2,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
    ],
  },
  // Library (2026-10-04 nav split) - reusable things you browse and pull in,
  // split out of Build so Build is only what you author.
  {
    labelKey: "navGroupLibrary",
    cat: "var(--cat-data)",
    adminOnly: false,
    icon: Library,
    items: [
      // Community template gallery (core/templates) - admin-approved
      // agent/workflow templates, one-click import into this tenant.
      {
        href: "/templates",
        labelKey: "railTemplates",
        icon: LayoutTemplate,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      {
        href: "/knowledge",
        labelKey: "railKnow",
        icon: BookOpen,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Cross-user sharing (core/sharing) - agents/workflows teammates have
      // granted the current user access to, across both resource types.
      // Discovery surface for the receiving side; the granting side lives
      // in a "Sharing" section on each agent's own detail view.
      {
        href: "/shared-with-me",
        labelKey: "railShared",
        icon: Users,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
    ],
  },
  // 2. CONNECT — how what you built reaches the outside world and plugs into
  // other systems. Channels leads the group: it is the one step in this
  // product that literally every tenant has to complete before a real person
  // can talk to their agent, and it was previously the LAST row of a
  // grab-bag section.
  {
    labelKey: "navGroupConnect",
    cat: "var(--cat-comms)",
    adminOnly: false,
    icon: Cable,
    items: [
      // MANAGE_CHANNELS-gated (granular RBAC): a member without the channel scope would only
      // hit a dead end. Not role-gated, connecting a channel is a universal journey step.
      {
        href: "/admin/channels",
        labelKey: "railChan",
        icon: Radio,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_CHANNELS",
      },
      // Contacts & request mapping (/admin/contacts, V238) - directly under
      // Channels because it IS the inbound half of channels: who the senders
      // are, which agent they reach, and whether new senders need approval.
      {
        href: "/admin/contacts",
        labelKey: "railContacts",
        icon: BookUser,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_CHANNELS",
      },
      // Conversations inbox (/admin/inbox) - the operator surface for the
      // same inbound channel traffic: every session across all channels,
      // two-way transcripts, pairing approval and live takeover. Same
      // MANAGE_CHANNELS scope as Channels/Contacts.
      {
        href: "/admin/inbox",
        labelKey: "railInbox",
        icon: Inbox,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_CHANNELS",
      },
      // Leads CRM (/admin/leads, V337) - pipeline board + table over the
      // built-in lead store that workflows and the bulk-email stream feed.
      {
        href: "/admin/leads",
        labelKey: "railLeads",
        icon: Target,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_LEADS",
      },
      // Approval decisions & policy (/admin/approvals) - restored as a real
      // scoped page in the granular-RBAC pass so a delegated
      // MANAGE_APPROVALS holder has somewhere to work (the panel also lives
      // on /admin/maintenance for full admins).
      {
        href: "/admin/approvals",
        labelKey: "railApprovals",
        icon: CheckCircle2,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_APPROVALS",
      },
      // Workflow input requests (Tier-3) - the structured mid-run form queue
      // Human Input nodes write to. Same human-in-the-loop scope as
      // approvals: a paused run waiting on a person is the same class of
      // surface, so it shares the MANAGE_APPROVALS rail.
      {
        href: "/admin/input-requests",
        labelKey: "railInputRequests",
        icon: ClipboardList,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_APPROVALS",
      },
    ],
  },
  // AI & Engine (2026-10-04 nav split) - the decision/code/on-device runtimes,
  // split out of Configuration.
  {
    labelKey: "navGroupAI",
    cat: "var(--cat-automation)",
    adminOnly: false,
    icon: Cpu,
    items: [
      // LLM providers (/providers) - the tenant's BYOK chat-provider keys. Leads the group
      // (the Agents 'no active providers' state links here). Not adminOnly; POST is
      // MANAGE_INTEGRATIONS-gated server-side.
      {
        href: "/providers",
        labelKey: "providers",
        icon: Network,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Decision providers (/capabilities/decision) - BYOK + playground for the DECISION
      // capability. Next to Providers: activating one changes WHOSE key the decision layer
      // spends. Not adminOnly; writes are MANAGE_INTEGRATIONS-gated server-side.
      {
        href: "/capabilities/decision",
        labelKey: "railDecisionProviders",
        icon: BrainCircuit,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      {
        href: "/code-intel",
        labelKey: "railCode",
        icon: GitBranch,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Edge AI (Edge Wave 1 surfacing, 2026-08-05) - the on-device model
      // marketplace / downloaded-models manager at /edge. CONNECT is the
      // right group: like Tools and Code Intel it's a console for plugging
      // an external runtime (here, the user's own device) into the
      // platform. Unlike App Builder above there's no deployment gate - the
      // page itself explains when this device can't run models locally.
      {
        href: "/edge",
        labelKey: "railEdge",
        icon: Cpu,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Local code (/code) - browser-local code assistant (nothing leaves the browser).
      // Under Edge AI, ungated like /edge; the page renders its own state when the browser
      // lacks the File System Access API. labelKey resolves against the 'localCode'
      // namespace, not 'nav'.
      {
        href: "/code",
        labelKey: "navLabel",
        icon: FolderCode,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
    ],
  },
  // Configuration (2026-09-24, direct instruction: group setup screens by category):
  // channels, integrations, secrets, code intelligence, on-device AI, local code, data usage.
  {
    labelKey: "navGroupConfig",
    cat: "var(--cat-automation)",
    adminOnly: false,
    icon: Settings2,
    items: [
      {
        href: "/tools",
        labelKey: "railTools",
        icon: Blocks,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Secrets vault (/secrets, core/secrets). Under Integrations because a vault entry is
      // what an integration authenticates WITH. Not adminOnly: members store USER-scoped
      // secrets; TENANT-scoped writes are gated server-side.
      {
        href: "/secrets",
        labelKey: "railSecrets",
        icon: Vault,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // "How does our infrastructure use your data" transparency page - one
      // card per underlying system (Postgres/MinIO/Redis/Langfuse/Kafka/
      // Temporal/Keycloak), each system's isolation fact called out
      // explicitly. Visible to every tenant member, not admin-gated - the
      // whole point is a plain, trust-building answer any user can read.
      {
        href: "/data-usage",
        labelKey: "railData",
        icon: Database,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // /add-ons - optional extras of a single-box install (web search, documents, the Assistant's
      // browser, local model packs). Personal and lite only: PERSONAL_ONLY_ROUTES in
      // lib/personalInstall.ts keeps it off the full stack's rail (its service set is fixed at
      // deploy time and the catalog's commands name the single-box tiers).
      {
        href: "/add-ons",
        labelKey: "railAddons",
        icon: PackagePlus,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // /troubleshoot - the owner's "something is wrong" panel for a
      // personal/local install: is the model server reachable, is the
      // recommended model pulled, do tool calls work - with one-click fixes.
      {
        href: "/troubleshoot",
        labelKey: "railTroubleshoot",
        icon: LifeBuoy,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
    ],
  },
];
