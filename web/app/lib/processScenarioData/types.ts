// Shapes and the shared design prompt for the Process Theater scenarios (split out
// of ../processScenarios.ts, which re-exports the types).

export type ProcessNode =
  | "trigger"
  | "agent"
  | "supervisor"
  | "parallel"
  | "fan_out"
  | "loop"
  | "condition"
  | "approval"
  | "tool"
  | "http"
  | "code"
  | "knowledge"
  | "workflow"
  | "output";

export type ProcessStep = {
  node: ProcessNode;
  label: string;
  /** What happens technically, one line. */
  tech: string;
  /** Illustrative offset from the start of the run. */
  clock: string;
  /** The run is parked here with nothing executing (approval, reply, next cycle). */
  waits?: boolean;
};

/** A specialist the build spawns — derived from the scenario's real steps. */
export type ScenarioAgent = {
  name: string;
  /** What this agent is for, one line. */
  role: string;
  /** Connector/tool names it leans on (should match `connectors` or built-ins). */
  tools: string[];
};

/**
 * One thing the finished build talks to. `kind` is honest about how it
 * attaches: "mcp" = attach an MCP server, "channel" = an inbound/outbound
 * channel, "http" = an HTTP Request node against any REST API, "builtin" =
 * already in the box (knowledge, code sandbox, vision), "custom" = a small
 * MCP tool the visitor writes themselves.
 */
export type ScenarioConnector = {
  name: string;
  kind: "mcp" | "channel" | "http" | "builtin" | "custom";
  detail: string;
};

export type ProcessScenario = {
  id: string;
  audience: "Enterprise" | "Engineering" | "Operations" | "Small business";
  title: string;
  pitch: string;
  span: string;
  steps: ProcessStep[];
  /** What the visitor connects after sign-in to run it on real systems. */
  needs: string;
  /** The specialists the build theatre spawns for this process. */
  agents: ScenarioAgent[];
  /** The MCP servers / channels / tools the build wires (or marks "you write"). */
  connectors: ScenarioConnector[];
  /** Sent to the sandbox agent: design this for MY business, no credentials. */
  prompt: string;
};

export const designPrompt = (what: string) =>
  `Design an agentic workflow for this process: ${what}. Lay it out as numbered steps. For each step name the node type (trigger, agent, supervisor, parallel, fan-out, loop, condition, approval, tool, HTTP, code, knowledge, sub-workflow, output), what it does, and where the run pauses and waits for a person or an event. Then list which systems I would connect, and what stays under human approval. Keep it to one screen.`;
