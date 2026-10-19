// Big, long-running processes for /try's Process Theater.
//
// Honesty rules (same spirit as lib/capabilities.ts):
//   - Every step's `node` is a REAL workflow node type (WorkflowNodeType /
//     components/workflow/nodeDefs). No invented building blocks.
//   - The animation is an ILLUSTRATION of how such a process is modelled on the
//     platform, and the UI says so. The live part is `prompt`: the sandbox agent
//     designs the process for the visitor, no credentials needed.
//   - Connecting the real systems (ERP, pager, repo...) needs the visitor's own
//     credentials, so it happens after sign-in. `needs` says which.
//   - No numbers about outcomes. `clock` values are illustrative offsets only.
//
// The scenarios themselves live in ./processScenarioData/, one file per audience;
// this module owns the display order and NODE_LABEL.

import type { ProcessNode, ProcessScenario } from "./processScenarioData/types";
import { invoiceScenario, claimsScenario, mainframeScenario } from "./processScenarioData/enterprise";
import { incidentScenario, migrationScenario, prreviewScenario } from "./processScenarioData/engineering";
import { supportScenario, onboardingScenario } from "./processScenarioData/operations";
import { bookingsScenario } from "./processScenarioData/smallBusiness";

export type {
  ProcessNode,
  ProcessScenario,
  ProcessStep,
  ScenarioAgent,
  ScenarioConnector,
} from "./processScenarioData/types";

export const NODE_LABEL: Record<ProcessNode, string> = {
  trigger: "Trigger",
  agent: "Agent",
  supervisor: "Supervisor",
  parallel: "Parallel",
  fan_out: "Fan-out",
  loop: "Loop",
  condition: "Condition",
  approval: "Approval",
  tool: "Tool",
  http: "HTTP",
  code: "Code",
  knowledge: "Knowledge",
  workflow: "Sub-workflow",
  output: "Output",
};

export const PROCESS_SCENARIOS: ProcessScenario[] = [
  invoiceScenario,
  incidentScenario,
  migrationScenario,
  prreviewScenario,
  supportScenario,
  onboardingScenario,
  claimsScenario,
  bookingsScenario,
  mainframeScenario,
];
