// Agent template gallery. The catalogue is DATA, one file per category under
// ./templates-data/agents (validated by templates-data/agents/registry.test.ts);
// this module keeps the original import path and the i18n key builders.
//
// Original design notes (still true):

//   Agent template gallery - answers "how does this actually help people day
//   to day" with something concrete to click, not just a blank system-prompt
//   textarea. Each template only pre-fills the existing create-agent form
//   (name, systemPrompt, suggestKnowledgeBase) - it never auto-submits, so the
//   person always reviews/edits before an agent is actually created, and
//   nothing here invents a capability the platform doesn't have (no template
//   claims access to real bank accounts, calendars, code execution, or social
//   media APIs - that would require this tenant's own OAuth/API/MCP
//   integration work first; these are conversational agents you brief with
//   your own documents/context via the Knowledge page, same as any other
//   agent here).
//
//   Direct feedback: "add all the general use cases that common people do and
//   organization/Developers and everyone" - templates are grouped into three
//   top-level audiences (this is what the dedicated /agents/templates page
//   renders as three sections), and each audience still has its own
//   sub-categories for scanability. AGENT_TEMPLATE_CATEGORIES keeps the
//   category -> audience mapping in one place so both the templates page and
//   anything else that needs to group by audience stay in sync.
//
//   i18n note (#1002): this is a plain data module, not a component, so it
//   cannot call useTranslations() itself. `label`/`tagline` on
//   AGENT_AUDIENCES, the display text for each AGENT_TEMPLATE_CATEGORIES
//   entry, and `name`/`tagline`/`knowledgeHint` on each AGENT_TEMPLATES entry
//   are therefore NOT stored here as literal English - consumers (the
//   /agents/templates page, AgentWorkbench.js) look the real text up from the
//   "agentTemplates" en.json namespace via the key-builder helpers below.
//   `systemPrompt` is a deliberate exception: it's the literal instruction
//   text fed to the LLM, not UI chrome, so translating it would change agent
//   behavior/quality per locale rather than just the display language - it
//   stays as plain English source text.

import type { AgentAudienceKey, AgentTemplateCategory } from "./templates-data/agents/categories";

export * from "./templates-data/agents/categories";
export * from "./templates-data/agents/mcpGuide";
export type { AgentTemplateEntry } from "./templates-data/agents/types";
export { AGENT_TEMPLATES, AGENT_TEMPLATES_BY_CATEGORY } from "./templates-data/agents/registry";

// en.json "agentTemplates" namespace key builders - see the i18n note above.
export function audienceLabelKey(audienceKey: AgentAudienceKey) {
  return `audience${audienceKey[0].toUpperCase()}${audienceKey.slice(1)}Label`;
}

export function audienceTaglineKey(audienceKey: AgentAudienceKey) {
  return `audience${audienceKey[0].toUpperCase()}${audienceKey.slice(1)}Tagline`;
}

const CATEGORY_KEYS: Record<AgentTemplateCategory, string> = {
  "For students": "categoryForStudents",
  "For families & elders": "categoryForFamiliesElders",
  "For work & career": "categoryForWorkCareer",
  "Everyday life": "categoryEverydayLife",
  "Customer & sales": "categoryCustomerSales",
  "Internal operations": "categoryInternalOperations",
  Engineering: "categoryEngineering",
  "E-commerce & retail": "categoryEcommerceRetail",
  "Healthcare & clinics": "categoryHealthcareClinics",
  "Real estate": "categoryRealEstate",
  "Hospitality & restaurants": "categoryHospitalityRestaurants",
  "Legal services": "categoryLegalServices",
  "Logistics & delivery": "categoryLogisticsDelivery",
  "Manufacturing & MSME": "categoryManufacturingMsme",
  "Wholesale & distribution": "categoryWholesaleDistribution",
  "Agriculture & agritech": "categoryAgricultureAgritech",
  "Banking & insurance": "categoryBankingInsurance",
  "Accounting, tax & CA": "categoryAccountingTaxCa",
  "Education & coaching": "categoryEducationCoaching",
  "Construction & development": "categoryConstructionDevelopment",
  "Events, catering & weddings": "categoryEventsCateringWeddings",
  Automotive: "categoryAutomotive",
  "Travel & tourism": "categoryTravelTourism",
  "Beauty, salon & fitness": "categoryBeautySalonFitness",
  "Textiles, apparel & jewellery": "categoryTextilesApparelJewellery",
  "Nonprofit & public services": "categoryNonprofitPublicServices",
  "Professional services": "categoryProfessionalServices",
  "Enterprise systems & modernization": "categoryEnterpriseSystemsModernization",
};

export function categoryLabelKey(category: AgentTemplateCategory) {
  return CATEGORY_KEYS[category] || category;
}

// "homework-helper" -> "homeworkHelper", used as the base for that
// template's Name/Tagline/KnowledgeHint keys below.
function templateKeyBase(key: string) {
  return key.replace(/-([a-z])/g, (_: string, c: string) => c.toUpperCase());
}

export function templateNameKey(key: string) {
  return `${templateKeyBase(key)}Name`;
}

export function templateTaglineKey(key: string) {
  return `${templateKeyBase(key)}Tagline`;
}

export function templateKnowledgeHintKey(key: string) {
  return `${templateKeyBase(key)}KnowledgeHint`;
}
