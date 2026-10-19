"use client";

import type { ComponentType } from "react";
import { SetupHint } from "../../setup/SetupLinks";
import WaitNodeConfig from "../WaitNodeConfig";
import DataTableNodeConfig from "../DataTableNodeConfig";
import DataNodeConfig from "../DataNodeConfigs";
import AiNodeConfigForm from "../AiNodeConfig";
import { isDataNodeType } from "../dataNodes";
import { isAiNodeType } from "../aiNodes";
import type { NodeFormProps } from "./registryTypes";
import AgentForm from "./forms/AgentForm";
import KnowledgeForm from "./forms/KnowledgeForm";
import ConditionForm from "./forms/ConditionForm";
import OutputForm from "./forms/OutputForm";
import DeviceControlForm from "./forms/DeviceControlForm";
import ApprovalForm from "./forms/ApprovalForm";
import SimpleFieldsForm from "./forms/SimpleFieldsForm";
import { TriggerNodeConfig } from "./forms/TriggerForm";
import { ToolNodeConfig } from "./forms/ToolForm";
import { SupervisorNodeConfig } from "./forms/SupervisorForm";
import { WorkflowTriggerNodeConfig } from "./forms/WorkflowForm";
import { CodeNodeConfig } from "./forms/CodeForm";
import { HttpNodeConfig } from "./forms/HttpForm";
import { HumanInputNodeConfig } from "./forms/HumanInputForm";
import { VoiceMenuNodeConfig } from "./forms/VoiceMenuForm";
import { BulkEmailNodeConfig } from "./forms/BulkEmailForm";
import { SubFlowNodeConfig } from "./forms/SubFlowForm";
import { CrmLeadNodeConfig } from "./forms/CrmLeadForm";

type NodeForm = ComponentType<NodeFormProps>;

const TriggerForm: NodeForm = ({ workflowId, nodeId, config, setConfig }) => (
  <>
    <TriggerNodeConfig workflowId={workflowId} nodeId={nodeId} config={config} setConfig={setConfig} />
    <SetupHint targets={["channels"]} lead="Connect the channel this trigger listens on:" newTab />
  </>
);

const ToolForm: NodeForm = ({ agents, config, setConfig }) => (
  <>
    <ToolNodeConfig agents={agents} config={config} setConfig={setConfig} />
    <SetupHint targets={["tools", "mcp", "secrets", "integrations"]} lead="Connect tools and credentials:" newTab />
  </>
);

/**
 * Registry of per-node forms keyed by node type. Adding a node's config UI =
 * one file under forms/ plus one line here; nodes whose config is only a few
 * plain inputs can instead add a descriptor to SIMPLE_FORMS (descriptors.ts)
 * and map the type to SimpleFieldsForm.
 */
export const NODE_FORMS: Record<string, NodeForm> = {
  trigger: TriggerForm,
  agent: AgentForm,
  knowledge: KnowledgeForm,
  tool: ToolForm,
  supervisor: ({ agents, config, setConfig }) => <SupervisorNodeConfig agents={agents} config={config} setConfig={setConfig} />,
  parallel: SimpleFieldsForm,
  condition: ConditionForm,
  loop: SimpleFieldsForm,
  fan_out: SimpleFieldsForm,
  code: ({ config, setConfig }) => <CodeNodeConfig config={config} setConfig={setConfig} />,
  http: ({ config, setConfig }) => <HttpNodeConfig config={config} setConfig={setConfig} />,
  workflow: ({ workflowId, config, setConfig }) => (
    <WorkflowTriggerNodeConfig workflowId={workflowId} config={config} setConfig={setConfig} />
  ),
  output: OutputForm,
  device_control: DeviceControlForm,
  approval: ApprovalForm,
  human_input: ({ config, setConfig }) => <HumanInputNodeConfig config={config} setConfig={setConfig} />,
  wait: ({ config, setConfig }) => <WaitNodeConfig config={config} setConfig={setConfig} />,
  sub_flow: ({ config, setConfig }) => <SubFlowNodeConfig config={config} setConfig={setConfig} />,
  voice_menu: ({ config, setConfig }) => <VoiceMenuNodeConfig config={config} setConfig={setConfig} />,
  bulk_email: ({ config, setConfig }) => <BulkEmailNodeConfig config={config} setConfig={setConfig} />,
  crm_lead: ({ config, setConfig }) => <CrmLeadNodeConfig config={config} setConfig={setConfig} />,
  data_table: ({ config, setConfig }) => <DataTableNodeConfig config={config} setConfig={setConfig} />,
};

const AiForm: NodeForm = ({ nodeType, config, setConfig }) =>
  isAiNodeType(nodeType) ? <AiNodeConfigForm type={nodeType} config={config} setConfig={setConfig} /> : null;
const DataForm: NodeForm = ({ nodeType, config, setConfig }) => (
  <DataNodeConfig type={nodeType} config={config} setConfig={setConfig} />
);

/** The form for a node type: explicit registry entry, else the AI / data node families. */
export function resolveNodeForm(type: string): NodeForm | null {
  if (NODE_FORMS[type]) return NODE_FORMS[type];
  if (isAiNodeType(type)) return AiForm;
  if (isDataNodeType(type)) return DataForm;
  return null;
}
