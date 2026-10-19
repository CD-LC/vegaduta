// The Blueprint: ONE object that holds every setting of what /build is about to
// create. This module is the stable public surface; the implementation is split into
// blueprintCore (model, defaults, flows, validation, readiness), blueprintFields
// (field catalogue), blueprintCompile (Brief and API payloads) and blueprintWiring
// (wiring text parsers). See blueprintCore.ts for the design rules.

export {
  TONES,
  genericFlow,
  FLOW_KIND_LABEL,
  APP_TEMPLATES,
  pickTemplateId,
  CHANNEL_KINDS,
  defaultWiring,
  defaultAppSpec,
  blueprintFromRecipe,
  blueprintFromGoal,
  blueprintFromRecipeId,
  composeInstructions,
  workflowInstruction,
  setSource,
  withAgent,
  withApp,
  withWiring,
  withOutput,
  withWorkflow,
  moveStep,
  removeStep,
  addStep,
  editStep,
  toAppPrompt,
  validate,
  readiness,
} from "./blueprintCore";
export type {
  Level,
  FieldSource,
  Tone,
  ReasoningDepth,
  AutonomyTier,
  FlowKind,
  OutputKind,
  FlowStep,
  AppSpec,
  WiringSpec,
  Blueprint,
  AppTemplateChoice,
  WorkspaceFacts,
  ReadinessItem,
} from "./blueprintCore";
export {
  SECTIONS,
  FIELDS,
  fieldsForLevel,
  fieldCount,
} from "./blueprintFields";
export type {
  FieldControl,
  FieldSpec,
} from "./blueprintFields";
export {
  toCreateAgentPayload,
  needsAutonomyPatch,
  starterEvalCases,
  toWorkflowGraph,
  toBrief,
  postApplyAgentPatches,
} from "./blueprintCompile";
export {
  SECRET_NAME_RE,
  parseSecrets,
  parseMcps,
  parseTools,
} from "./blueprintWiring";
