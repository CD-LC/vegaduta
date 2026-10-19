import type { LucideIcon } from "lucide-react";
import type { AgentTemplateCategory } from "./categories";
import type { McpSuggestion } from "./mcpGuide";

/**
 * One entry of the agent template catalogue. Only i18n KEYS are derived from
 * `key` (name/tagline/knowledgeHint live in the "agentTemplates" namespace);
 * `systemPrompt` is the literal English instruction text fed to the LLM.
 */
export type AgentTemplateEntry = {
  key: string;
  category: AgentTemplateCategory;
  /** Persona override (developer, student, freelancer, ...) used by the persona filters; not an AGENT_AUDIENCES key. */
  audience?: string;
  icon: LucideIcon;
  suggestKnowledgeBase: boolean;
  /** Flag only: the hint text is the `<key>KnowledgeHint` i18n string. */
  knowledgeHint?: boolean;
  customerFacing?: boolean;
  draftOnly?: boolean;
  mcp?: readonly McpSuggestion[];
  capabilities?: readonly string[];
  systemPrompt: string;
};
