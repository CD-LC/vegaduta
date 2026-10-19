import { Globe, Layers, Route, Split, Telescope } from "lucide-react";
import type { WorkflowGraphEdge, WorkflowGraphNode } from "../../workflow/nodeDefs";
import { makeNode, makeEdge } from "./graphHelpers";
import type { WorkflowTemplate } from "./types";

// Static parallel fan-out: trigger -> parallel -> three agent branches that
// all converge on ONE join node -> output. The convergence is the runtime's
// documented convention, not optional decoration: WorkflowExecutionEngine
// resolves the post-join next hop off the LAST branch's outgoing edge, so a
// branch that pointed anywhere else would silently re-route the run. The
// merged input the join node receives is a JSON array of the branch outputs,
// in edge order.
function parallelSpecialistGraph(): { nodes: WorkflowGraphNode[]; edges: WorkflowGraphEdge[] } {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const fan = makeNode("parallel", 360, 160, "Fan out to specialists");
  const triage = makeNode("agent", 640, 40, "Triage specialist");
  const sentiment = makeNode("agent", 640, 160, "Sentiment & tone specialist");
  const priority = makeNode("agent", 640, 280, "Priority & SLA specialist");
  const merge = makeNode("agent", 920, 160, "Merge findings into one reply");
  const out = makeNode("output", 1200, 160, "Send consolidated reply", { channel: "log" });
  return {
    nodes: [trigger, fan, triage, sentiment, priority, merge, out],
    edges: [
      makeEdge(trigger.id, fan.id),
      makeEdge(fan.id, triage.id),
      makeEdge(fan.id, sentiment.id),
      makeEdge(fan.id, priority.id),
      makeEdge(triage.id, merge.id),
      makeEdge(sentiment.id, merge.id),
      makeEdge(priority.id, merge.id),
      makeEdge(merge.id, out.id),
    ],
  };
}

// Dynamic fan-out: trigger -> planner agent -> fan_out -> ONE worker agent ->
// output. fan_out's "exactly one outgoing edge" is a save-time validation
// rule (OrchestratorService#validate), and the worker's own outgoing edge is
// the post-merge next hop - the engine runs the worker once per item
// concurrently, merges per-item outputs into a JSON array in item order,
// then continues from the worker's edge. itemsField "tasks" = read the array
// from that field of the planner's JSON output (blank would mean the input
// itself must already be the array - same contract Loop uses).
function fanOutTaskListGraph(): { nodes: WorkflowGraphNode[]; edges: WorkflowGraphEdge[] } {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const planner = makeNode("agent", 360, 160, "Break request into a task list");
  const fan = makeNode("fan_out", 640, 160, "Fan out to workers", {
    itemsField: "tasks",
    maxItems: 25,
    maxConcurrency: 5,
  });
  const worker = makeNode("agent", 920, 160, "Complete one task");
  const out = makeNode("output", 1200, 160, "Return merged results", { channel: "log" });
  return {
    nodes: [trigger, planner, fan, worker, out],
    edges: [
      makeEdge(trigger.id, planner.id),
      makeEdge(planner.id, fan.id),
      makeEdge(fan.id, worker.id),
      makeEdge(worker.id, out.id),
    ],
  };
}

// Supervisor routing: trigger -> supervisor -> one agent per named route,
// each converging on the same output. The supervisor's edges MUST carry
// sourceHandle = the route handle - that's the whole routing contract (the
// engine resolves the next hop by matching the handle the agent picked at
// runtime against edge sourceHandle, the same way Condition's true/false
// ports work, and OrchestratorService requires every route to have a
// non-blank handle). Convergence on one output is the same join convention
// as the parallel template above.
function supervisorRoutingGraph(): { nodes: WorkflowGraphNode[]; edges: WorkflowGraphEdge[] } {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const router = makeNode("supervisor", 360, 160, "Route by intent", {
    agentId: "",
    routes: [
      { handle: "support", label: "Support" },
      { handle: "sales", label: "Sales" },
      { handle: "billing", label: "Billing" },
    ],
    instructions: "Read the inbound message and pick the route whose specialist should answer it.",
  });
  const support = makeNode("agent", 640, 40, "Support specialist");
  const sales = makeNode("agent", 640, 160, "Sales specialist");
  const billing = makeNode("agent", 640, 280, "Billing specialist");
  const out = makeNode("output", 920, 160, "Send reply", { channel: "log" });
  return {
    nodes: [trigger, router, support, sales, billing, out],
    edges: [
      makeEdge(trigger.id, router.id),
      makeEdge(router.id, support.id, "support"),
      makeEdge(router.id, sales.id, "sales"),
      makeEdge(router.id, billing.id, "billing"),
      makeEdge(support.id, out.id),
      makeEdge(sales.id, out.id),
      makeEdge(billing.id, out.id),
    ],
  };
}

// Deep research: trigger -> planner -> fan_out -> researcher -> synthesizer ->
// output. Same fan_out contract as fanOutTaskListGraph above (exactly one
// outgoing edge; the worker's own edge is the post-merge next hop) - the
// difference is semantic, not structural: itemsField "questions" is the
// planner's sub-question list, the worker is a researcher agent expected to
// carry web tools, and the synthesizer is a second agent hop after the merge
// so branch findings get written up rather than dumped raw to the output.
function deepResearchGraph(): { nodes: WorkflowGraphNode[]; edges: WorkflowGraphEdge[] } {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const planner = makeNode("agent", 360, 160, "Decompose into sub-questions");
  const fan = makeNode("fan_out", 640, 160, "Research each sub-question", {
    itemsField: "questions",
    maxItems: 10,
    maxConcurrency: 5,
  });
  const researcher = makeNode("agent", 920, 160, "Researcher (one per sub-question)");
  const synthesizer = makeNode("agent", 1200, 160, "Synthesize cited report");
  const out = makeNode("output", 1480, 160, "Deliver report", { channel: "log" });
  return {
    nodes: [trigger, planner, fan, researcher, synthesizer, out],
    edges: [
      makeEdge(trigger.id, planner.id),
      makeEdge(planner.id, fan.id),
      makeEdge(fan.id, researcher.id),
      makeEdge(researcher.id, synthesizer.id),
      makeEdge(synthesizer.id, out.id),
    ],
  };
}

// External API enrichment: trigger -> http -> agent -> output. Deliberately
// ships a BLANK url: activation-time validation (OrchestratorService#validate)
// rejects an HTTP node with no URL with a clear error, which is the honest
// out-of-the-box posture - the person must point it at a real endpoint, same
// as every template here requires assigning its agents. A placeholder URL
// would pass validation and then fail at runtime against a host that does
// not exist.
function apiEnrichmentGraph(): { nodes: WorkflowGraphNode[]; edges: WorkflowGraphEdge[] } {
  const trigger = makeNode("trigger", 80, 160, "Trigger", { channel: "WEBHOOK" });
  const http = makeNode("http", 360, 160, "Call enrichment API", { method: "GET", url: "" });
  const agent = makeNode("agent", 640, 160, "Interpret the API response");
  const out = makeNode("output", 920, 160, "Send reply", { channel: "log" });
  return {
    nodes: [trigger, http, agent, out],
    edges: [makeEdge(trigger.id, http.id), makeEdge(http.id, agent.id), makeEdge(agent.id, out.id)],
  };
}

// Workflow templates: Orchestration & integrations.
export const orchestrationAndIntegrationsTemplates: WorkflowTemplate[] = [
  {
    key: "parallel-specialist-review-workflow",
    category: "Orchestration & integrations",
    icon: Split,
    description:
      "Fans one inbound request out to three specialist agents at the same time, then merges their findings " +
      "into a single reply. Assign each specialist its agent on the canvas before activating.",
    triggerChannel: "WEBHOOK",
    buildGraph: parallelSpecialistGraph,
  },
  {
    key: "fan-out-task-list-workflow",
    category: "Orchestration & integrations",
    icon: Layers,
    description:
      "Turns one request into a task list, runs a worker agent on every item concurrently (maxConcurrency " +
      "at a time), and returns the merged results as a JSON array. The planner agent's reply must be a JSON " +
      "object with a \"tasks\" array - that's the fan-out node's itemsField.",
    triggerChannel: "WEBHOOK",
    buildGraph: fanOutTaskListGraph,
  },
  {
    key: "supervisor-smart-routing-workflow",
    category: "Orchestration & integrations",
    icon: Route,
    description:
      "A supervisor agent reads each inbound message and routes it to the right specialist - support, sales " +
      "or billing - then that specialist's answer goes out. Assign the supervisor and each specialist their " +
      "agent on the canvas before activating.",
    triggerChannel: "WEBHOOK",
    buildGraph: supervisorRoutingGraph,
  },
  {
    key: "deep-research-workflow",
    category: "Orchestration & integrations",
    icon: Telescope,
    description:
      "Plan, search, synthesize: a planner agent decomposes the research question into sub-questions " +
      "(reply must be a JSON object with a \"questions\" array - that's the fan-out node's itemsField), " +
      "a researcher agent answers each sub-question concurrently (give it web_search/fetch_web_page tools), " +
      "and a synthesizer agent merges all findings into one cited report.",
    triggerChannel: "WEBHOOK",
    buildGraph: deepResearchGraph,
  },
  {
    key: "api-enrichment-workflow",
    category: "Orchestration & integrations",
    icon: Globe,
    description:
      "Calls an external REST API on each inbound request, hands the response to an agent to interpret, then " +
      "sends the answer on. Point the HTTP Request node's URL at your endpoint before activating.",
    triggerChannel: "WEBHOOK",
    buildGraph: apiEnrichmentGraph,
  },
];
