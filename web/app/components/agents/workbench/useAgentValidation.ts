"use client";

import type { WorkbenchProps } from "./types";
import type { useAgentDraft } from "./useAgentDraft";
import type { useWorkbenchAccess } from "./useWorkbenchAccess";
import type { useWorkbenchBase } from "./useWorkbenchBase";
import type { useWorkbenchRouting } from "./useWorkbenchRouting";
import type { useWorkbenchTenant } from "./useWorkbenchTenant";

export function useAgentValidation(
  _p: WorkbenchProps,
  prev: ReturnType<typeof useWorkbenchBase> &
    ReturnType<typeof useWorkbenchTenant> &
    ReturnType<typeof useAgentDraft> &
    ReturnType<typeof useWorkbenchAccess> &
    ReturnType<typeof useWorkbenchRouting>
) {
  const { name, t, systemPrompt, needsProvider, atAgentLimit, agentLimit } = prev;

  function validate() {
    if (!name.trim()) return t("nameRequired");
    if (!systemPrompt.trim()) return t("systemPromptRequired");
    // FREE tenants never activate a BYOK provider - AgentChatService always
    // routes their chat through the platform-hosted pool regardless (same
    // isFreePlan gate the "no active provider" banner below and
    // ProvidersPanel.tsx already use), so this validation must not block a
    // FREE tenant from creating an agent just because activeProviders is empty.
    // Same for a tenant on a complimentary grant, or one running on-device -
    // see needsProvider, the single decision this and the banner share.
    if (needsProvider) return t("activateProviderFirst");
    if (atAgentLimit) return t("agentLimitReached", { limit: agentLimit });
    return null;
  }

  return { validate };
}
