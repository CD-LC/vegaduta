// Thin fetch wrapper around the core Spring Boot API - barrel. The client is
// split by domain under ./api/ (extract-only); this file re-exports everything
// so every existing `from "../lib/api"` import keeps working.
export { API_BASE, triggerBrowserDownload, voiceBlobExtension, clearApiGetCache } from "./api/core";
export type { AgentStepEvent } from "./api/core";
export * from "./api/types";
import { providersApi } from "./api/providers";
import { agentsApi } from "./api/agents";
import { chatApi } from "./api/chat";
import { sharingApi } from "./api/sharing";
import { mcpApi } from "./api/mcp";
import { mcpOauthApi } from "./api/mcpOauth";
import { mcpToolPinsApi } from "./api/mcpToolPins";
import { knowledgeApi } from "./api/knowledge";
import { mediaApi } from "./api/media";
import { governanceApi } from "./api/governance";
import { toolsApi } from "./api/tools";
import { channelsApi } from "./api/channels";
import { adminApi } from "./api/admin";
import { billingApi } from "./api/billing";
import { telemetryApi } from "./api/telemetry";
import { handoffApi } from "./api/handoff";
import { qualityApi } from "./api/quality";
import { accountApi } from "./api/account";
import { workflowsApi } from "./api/workflows";
import { devicesApi } from "./api/devices";
import { templatesApi } from "./api/templates";
import { notificationsApi } from "./api/notifications";
import { agentLifecycleApi } from "./api/agentLifecycle";
import { capabilitiesApi } from "./api/capabilities";
import { leadsApi } from "./api/leads";
import { paymentsApi } from "./api/payments";
import { briefsApi } from "./api/briefs";
import { studioAppsApi } from "./api/studioApps";
import { agentTrustApi } from "./api/agentTrust";
import { promptDirectivesApi } from "./api/promptDirectives";
import { promptRegistryApi } from "./api/promptRegistry";
import { memoryApi } from "./api/memory";
import { researchApi } from "./api/research";
import { tutorApi } from "./api/tutor";
import { agentImportApi } from "./api/agentImport";
import { agentBundlesApi } from "./api/agentBundles";
import { skillSecurityApi } from "./api/skillSecurity";
import { mcpExposureApi } from "./api/mcpExposure";
import { mcpAppsApi } from "./api/mcpApps";
import { dataTablesApi } from "./api/dataTables";
import { scheduledTasksApi } from "./api/scheduledTasks";
import { codingTasksApi } from "./api/codingTasks";
import { workflowPortabilityApi } from "./api/workflowPortability";
import { browserAutomationApi } from "./api/browserAutomation";
import { browserTakeoverApi } from "./api/browserTakeover";
import { desktopRecipesApi } from "./api/desktopRecipes";
import { chatBranchingApi } from "./api/chatBranching";
import { chatPortabilityApi } from "./api/chatPortability";
import { apiKeysApi } from "./api/apiKeys";
import { localModelsApi } from "./api/localModels";
import { a2aApi } from "./api/a2a";

export const api = {
  ...providersApi,
  ...agentsApi,
  ...chatApi,
  ...sharingApi,
  ...mcpApi,
  ...mcpOauthApi,
  ...mcpToolPinsApi,
  ...knowledgeApi,
  ...mediaApi,
  ...governanceApi,
  ...toolsApi,
  ...channelsApi,
  ...adminApi,
  ...billingApi,
  ...telemetryApi,
  ...handoffApi,
  ...qualityApi,
  ...accountApi,
  ...workflowsApi,
  ...devicesApi,
  ...templatesApi,
  ...notificationsApi,
  ...agentLifecycleApi,
  ...capabilitiesApi,
  ...leadsApi,
  ...paymentsApi,
  ...briefsApi,
  ...studioAppsApi,
  ...agentTrustApi,
  ...promptDirectivesApi,
  ...promptRegistryApi,
  ...memoryApi,
  ...researchApi,
  ...tutorApi,
  ...agentImportApi,
  ...agentBundlesApi,
  ...skillSecurityApi,
  ...mcpExposureApi,
  ...mcpAppsApi,
  ...dataTablesApi,
  ...scheduledTasksApi,
  ...codingTasksApi,
  ...workflowPortabilityApi,
  ...browserAutomationApi,
  ...browserTakeoverApi,
  ...desktopRecipesApi,
  ...chatBranchingApi,
  ...chatPortabilityApi,
  ...apiKeysApi,
  ...localModelsApi,
  ...a2aApi,
};
