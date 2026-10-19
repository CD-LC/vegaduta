// Process Theater scenarios for the "Engineering" audience. Engineering processes: incident response, migrations and PR review.
// Assembled, in display order, by ../processScenarios.ts (see its honesty rules).
import { designPrompt, type ProcessScenario } from "./types";

export const incidentScenario: ProcessScenario = {
  id: "incident",
  audience: "Engineering",
  title: "Production incident, first 30 minutes",
  pitch:
    "An alert fires at 3 a.m. Specialist agents investigate in parallel and hand the on-call engineer a diagnosis, not a dashboard.",
  span: "Minutes, then a follow-up days later",
  needs: "Alerting that can call an API, logs/metrics API, repository (Code Intelligence), Slack",
  steps: [
    {
      node: "trigger",
      label: "Alert fires",
      tech: "Your alerting calls the workflow run API; the payload becomes the run input",
      clock: "T+0s",
    },
    {
      node: "supervisor",
      label: "Incident commander",
      tech: "Supervisor node plans the investigation and delegates to specialists",
      clock: "T+2s",
    },
    {
      node: "parallel",
      label: "Investigate in parallel",
      tech: "Parallel branches: logs agent, recent-deploys agent, code agent",
      clock: "T+3s",
    },
    {
      node: "knowledge",
      label: "Check runbooks",
      tech: "Retrieval over your runbooks and past post-mortems",
      clock: "T+6s",
    },
    {
      node: "agent",
      label: "Write the diagnosis",
      tech: "Merges branch findings into likely cause, blast radius, suggested fix",
      clock: "T+40s",
    },
    {
      node: "approval",
      label: "Roll back?",
      tech: "Approval node: a human authorises any production action",
      clock: "T+1 min",
      waits: true,
    },
    {
      node: "workflow",
      label: "Post-mortem",
      tech: "Sub-workflow drafts the post-mortem and opens follow-up tasks",
      clock: "T+2 days",
    },
  ],
  agents: [
    {
      name: "Incident Commander",
      role: "Supervisor that plans the investigation and delegates to the specialists.",
      tools: ["Alerting webhook"],
    },
    {
      name: "Logs Investigator",
      role: "Pulls the error window from your logs and metrics.",
      tools: ["Logs & metrics API"],
    },
    {
      name: "Deploys Investigator",
      role: "Correlates the alert with what shipped in the last 24 hours.",
      tools: ["Code Intelligence"],
    },
    {
      name: "Post-mortem Writer",
      role: "Turns the diagnosis into a post-mortem draft and follow-up tasks.",
      tools: ["Slack"],
    },
  ],
  connectors: [
    {
      name: "Alerting webhook",
      kind: "http",
      detail: "Your alerting calls the workflow run API — the payload is the run input.",
    },
    {
      name: "Logs & metrics API",
      kind: "custom",
      detail: "A small MCP tool you write: query_logs(service, window), get_metrics(...).",
    },
    { name: "Code Intelligence", kind: "builtin", detail: "Queryable graph of your repositories — built in." },
    { name: "Slack", kind: "channel", detail: "Diagnosis and approval requests land in your incident channel." },
    { name: "Runbooks & post-mortems", kind: "builtin", detail: "Knowledge retrieval over documents you upload." },
  ],
  prompt: designPrompt(
    "a production alert fires; investigate logs, recent deploys and code in parallel, check runbooks, produce a diagnosis, require human approval before any rollback, then draft a post-mortem"
  ),
};

export const migrationScenario: ProcessScenario = {
  id: "migration",
  audience: "Engineering",
  title: "Codebase migration across many services",
  pitch:
    "Upgrade a framework across dozens of repositories: plan once, fan out, review every change, keep a human on the merge button.",
  span: "Runs for weeks",
  needs: "Repositories indexed in Code Intelligence, your Git host",
  steps: [
    {
      node: "trigger",
      label: "Kick off the migration",
      tech: "Manual trigger with the target version as input",
      clock: "Day 0",
    },
    {
      node: "knowledge",
      label: "Read the upgrade guide",
      tech: "Retrieval over the framework's migration notes you uploaded",
      clock: "Day 0",
    },
    {
      node: "fan_out",
      label: "One run per service",
      tech: "Fan-out node spawns a branch per repository",
      clock: "Day 0",
    },
    {
      node: "agent",
      label: "Find every call site",
      tech: "Agent queries the code graph for affected symbols and their callers",
      clock: "Day 0",
    },
    {
      node: "loop",
      label: "Fix, test, repeat",
      tech: "Loop node: propose change, read test output, retry until green or out of attempts",
      clock: "Day 1",
    },
    {
      node: "approval",
      label: "Engineer reviews",
      tech: "Approval node per service; the run waits as long as review takes",
      clock: "Day 1 to 14",
      waits: true,
    },
    {
      node: "output",
      label: "Progress report",
      tech: "Rolls up which services are done, blocked or waiting",
      clock: "Weekly",
    },
  ],
  agents: [
    {
      name: "Migration Planner",
      role: "Reads the upgrade guide and decides the per-service order of attack.",
      tools: ["Migration notes", "Code Intelligence"],
    },
    {
      name: "Service Fixers",
      role: "One agent per repository — find call sites, propose changes, run the tests.",
      tools: ["Code Intelligence", "CI API"],
    },
    {
      name: "Progress Reporter",
      role: "Rolls up done / blocked / waiting into the weekly summary.",
      tools: ["Git host"],
    },
  ],
  connectors: [
    { name: "Git host", kind: "mcp", detail: "Attach your Git host's MCP server, or call its API over HTTP." },
    {
      name: "Code Intelligence",
      kind: "builtin",
      detail: "Code graph queries — affected symbols and their callers.",
    },
    {
      name: "Migration notes",
      kind: "builtin",
      detail: "Upload the framework's upgrade guide; retrieval does the rest.",
    },
    { name: "CI API", kind: "http", detail: "HTTP node triggers builds and reads test output per service." },
  ],
  prompt: designPrompt(
    "upgrade a framework across many code repositories; read the upgrade guide, fan out per service, find affected call sites, loop on fix-and-test, require engineer approval per service, and report progress"
  ),
};

export const prreviewScenario: ProcessScenario = {
  id: "prreview",
  audience: "Engineering",
  title: "Pull request review with the code graph",
  pitch:
    "Every pull request read against the whole codebase, not just the diff: who calls this, what breaks, which tests cover it.",
  span: "Minutes per pull request",
  needs: "Repository indexed in Code Intelligence, your Git host",
  steps: [
    {
      node: "trigger",
      label: "Pull request opened",
      tech: "Your Git host calls the workflow run API with the diff reference",
      clock: "T+0s",
    },
    {
      node: "agent",
      label: "Map the blast radius",
      tech: "Agent queries the code graph: changed symbols, their callers, dependents",
      clock: "T+6s",
    },
    {
      node: "parallel",
      label: "Review in parallel",
      tech: "Parallel branches: correctness, security, test-coverage reviewers",
      clock: "T+8s",
    },
    {
      node: "knowledge",
      label: "Check team conventions",
      tech: "Retrieval over your engineering guidelines and ADRs",
      clock: "T+12s",
    },
    {
      node: "supervisor",
      label: "Merge the findings",
      tech: "Supervisor dedupes, ranks by severity, drops low-confidence noise",
      clock: "T+35s",
    },
    {
      node: "output",
      label: "Post the review",
      tech: "Structured findings with file and line, back to the pull request",
      clock: "T+40s",
    },
  ],
  agents: [
    {
      name: "Review Coordinator",
      role: "Supervisor that dedupes findings and ranks them by severity.",
      tools: ["Git host"],
    },
    {
      name: "Correctness Reviewer",
      role: "Reads the diff against callers and dependents in the code graph.",
      tools: ["Code Intelligence"],
    },
    {
      name: "Security Reviewer",
      role: "Looks for the change's blast radius on auth, data and secrets.",
      tools: ["Code Intelligence", "Team guidelines"],
    },
    {
      name: "Coverage Reviewer",
      role: "Checks which tests exercise the changed symbols.",
      tools: ["Code Intelligence"],
    },
  ],
  connectors: [
    {
      name: "Git host webhook",
      kind: "http",
      detail: "PR opened → your Git host calls the workflow run API with the diff.",
    },
    {
      name: "Code Intelligence",
      kind: "builtin",
      detail: "Changed symbols, callers, dependents — queryable, built in.",
    },
    { name: "Team guidelines", kind: "builtin", detail: "Retrieval over your engineering guidelines and ADRs." },
    { name: "Git host", kind: "mcp", detail: "Optional MCP server for richer PR reads and inline comments." },
  ],
  prompt: designPrompt(
    "a pull request is opened; map its blast radius using a code graph, review correctness, security and test coverage in parallel, check team conventions, merge and rank findings, and post a structured review"
  ),
};
