// Route tables for the dev-only preview mock (mock-api.ts).

import { labMockPatternRoutes } from "../../components/agents/lab/labMock";
import { probeFixture, recommendationFixture } from "../../components/localModel/fixtures";
import { account, adminUsers, agents, auditLog, channelsStatus, collections, documentsByCollection, e2bCredential, embeddingModels, invoices, knowledgeStats, llmTelemetry, llmUsageSummary, mcpCatalog, mcpRegistrySearch, mcpServers, notifications, openApiSources, openApiTools, pendingApprovals, providerModels, providers, referral, spendProtection, subscription, systemStatus, tenantSettings, toolCalls, toolClassifications, toolScripts, twilioCredential, usageQuota, workflows } from "./mock-sample-data";
import type { JsonBody } from "./mock-sample-data";
import { agentNameFor, approvePending, channelRouting, contactGroups, contactsStore, groupNameFor, nextContactSeq, parseJsonBody, pendingPairings } from "./mock-contacts";
import type { MockContact } from "./mock-contacts";

// ---- Router -------------------------------------------------------------
// Regex routes for paths with an id segment (checked after the exact table).
type PatternHandler = (m: RegExpMatchArray, url: URL, init?: RequestInit) => { status?: number; body: JsonBody } | null;
export const patternRoutes: Array<[RegExp, PatternHandler]> = [
  ...labMockPatternRoutes,
  [/^\/api\/providers\/([A-Z_]+)\/models$/, (m) => ({ body: providerModels[m[1]] ?? [] })],
  [/^\/api\/knowledge\/collections\/([^/]+)\/documents$/, (m) => ({ body: documentsByCollection[m[1]] ?? [] })],
  [/^\/api\/tools\/openapi\/([^/]+)\/tools$/, () => ({ body: openApiTools })],
  [/^\/api\/agents\/([^/]+)\/tool-calls$/, () => ({ body: toolCalls })],
  [/^\/api\/agents\/([^/]+)\/memories$/, () => ({ body: [] })],
  [
    /^\/api\/agents\/([^/]+)$/,
    (m) => {
      const agent = agents.find((a) => a.id === m[1]);
      return agent ? { body: agent } : { status: 404, body: { error: "Not found" } };
    },
  ],
  // Workbench side-cards: quiet, empty-but-well-formed bodies (a 404 here
  // raises the global "Not found" toast on every load).
  [/^\/api\/agents\/[^/]+\/skills$/, () => ({ body: { skillsEnabled: false, allowedSkillNames: [] as string[], skills: [] } })],
  [/^\/api\/agents\/[^/]+\/versions$/, () => ({ body: [] })],
  [/^\/api\/agents\/[^/]+\/specialists$/, () => ({ body: [] })],
  [
    /^\/api\/agents\/([^/]+)\/guardrails$/,
    (m) => ({
      body: { agentId: m[1], enabledGuardrails: [] as string[], availableGuardrails: [] as string[], lockedGuardrails: [] as string[], guardrailDescriptions: {}, locked: false },
    }),
  ],
  [/^\/api\/agents\/[^/]+\/(reliability|quality|rl)(\/.*)?$/, () => ({ body: {} })],
  [/^\/api\/skills$/, () => ({ body: [] })],
  // /admin/contacts mutations - see the contacts block above.
  [
    /^\/api\/channels\/pairing\/([^/]+)\/approve$/,
    (m) => {
      const contact = approvePending(m[1]);
      return contact ? { body: { sessionId: m[1], approved: true } } : { status: 404, body: { error: "No such pending session" } };
    },
  ],
  [
    /^\/api\/channels\/pairing\/([^/]+)$/,
    (m, _url, init) => {
      if ((init?.method || "GET").toUpperCase() !== "DELETE") return null;
      const idx = pendingPairings.findIndex((p) => p.sessionId === m[1]);
      if (idx < 0) return { status: 404, body: { error: "No such pending session" } };
      pendingPairings.splice(idx, 1);
      return { status: 204, body: null };
    },
  ],
  [
    /^\/api\/channels\/routing\/([^/]+)$/,
    (m, _url, init) => {
      const body = parseJsonBody(init);
      const row = channelRouting.find((r) => r.channel === m[1]);
      if (!row) return { status: 404, body: { error: "Unknown channel" } };
      row.defaultAgentId = (body.agentId as string | null) ?? null;
      row.defaultAgentName = agentNameFor(body.agentId);
      row.autoApproveNewSenders = Boolean(body.autoApproveNewSenders);
      return { body: row };
    },
  ],
  [
    /^\/api\/channels\/contacts\/([^/]+)$/,
    (m, _url, init) => {
      const method = (init?.method || "GET").toUpperCase();
      const idx = contactsStore.findIndex((c) => c.id === m[1]);
      if (idx < 0) return { status: 404, body: { error: "No such contact" } };
      if (method === "DELETE") {
        contactsStore.splice(idx, 1);
        return { status: 204, body: null };
      }
      if (method === "PUT") {
        const body = parseJsonBody(init);
        const c = contactsStore[idx];
        c.displayName = (body.displayName as string | null) ?? null;
        c.groupId = (body.groupId as string | null) ?? null;
        c.groupName = groupNameFor(body.groupId);
        c.agentId = (body.agentId as string | null) ?? null;
        c.agentName = agentNameFor(body.agentId);
        return { body: c };
      }
      return { body: contactsStore[idx] };
    },
  ],
  [
    /^\/api\/channels\/contact-groups\/([^/]+)$/,
    (m, _url, init) => {
      const method = (init?.method || "GET").toUpperCase();
      const idx = contactGroups.findIndex((g) => g.id === m[1]);
      if (idx < 0) return { status: 404, body: { error: "No such group" } };
      if (method === "DELETE") {
        contactGroups.splice(idx, 1);
        return { status: 204, body: null };
      }
      return { body: contactGroups[idx] };
    },
  ],
  [/^\/api\/(channels|elevenlabs|sarvam)\/[^/]+\/(credential|status|connection)(\/status)?$/, () => ({ status: 204, body: null })],
];


type Handler = (url: URL, init?: RequestInit) => { status?: number; body: JsonBody } | null;

// Exact pathname -> handler. Query strings are handled inside the handler.
export const routes: Record<string, Handler> = {
  "/api/system/status/live": () => ({ body: { status: "UP" } }),
  "/api/system/status": () => ({ body: systemStatus }),
  "/api/system/status/llm-telemetry": () => ({ body: llmTelemetry }),
  "/api/agents": () => ({ body: agents }),
  "/api/workflows": () => ({ body: workflows }),
  "/api/channels/status": () => ({ body: channelsStatus }),
  "/api/approvals": (url) => {
    const status = url.searchParams.get("status");
    return { body: !status || status === "PENDING" ? pendingApprovals : [] };
  },
  "/api/tenant/settings": () => ({ body: tenantSettings }),
  "/api/tenant/usage-quota": () => ({ body: usageQuota }),
  "/api/audit-log": () => ({ body: auditLog }),
  "/api/audit-log/action-types": () => ({ body: [...new Set(auditLog.map((e) => e.actionType))] }),
  "/api/notifications": () => ({ body: notifications }),
  "/api/notifications/unread-count": () => ({ body: 1 }),
  "/api/notifications/read-all": () => ({ status: 204, body: null }),
  "/api/providers/availability": () => ({
    body: { hasByokProvider: false, platformHostedAvailable: true, canCreateAgents: true },
  }),
  "/api/providers/platform-llm": () => ({
    body: { available: true, label: "Platform model", providerName: "GOOGLE", model: "gemini-2.5-flash" },
  }),
  "/api/account": () => ({ body: account }),
  "/api/account/avatar": () => ({ status: 404, body: { error: "No avatar" } }),
  "/api/mcp/registry": () => ({ body: mcpServers }),
  // Edge model manifest is feature-detected: a 404 = "route not shipped", so
  // the download manager stays inert instead of parsing an empty manifest.
  "/api/edge/manifest": () => ({ status: 404, body: { error: "Not found" } }),
  "/api/providers": () => ({ body: providers }),
  // The local-model wizard (/providers/local-model): a 16 GB machine with one model already pulled, so the catalog
  // shows "Fits your machine" and "Needs more memory" groups plus the installed/serving markers.
  "/api/local-models/recommendation": () => ({
    body: {
      ...recommendationFixture,
      servingModel: "qwen3:4b",
      ollama: { ...recommendationFixture.ollama, installedTags: ["qwen3:4b"] },
    },
  }),
  "/api/local-models/probe": () => ({ body: probeFixture }),
  "/api/local-models/pull": () => ({ status: 202, body: { jobId: "preview-pull" } }),
  "/api/local-models/pull/preview-pull": () => ({ body: { status: "done", percent: 100, message: "success" } }),
  "/api/local-models/use": () => ({ body: { tag: "qwen3:8b", rowId: 1, created: false, enabled: true, toolCallGrade: "STRUCTURED" } }),
  "/api/providers/default-model": () => ({
    body: { providerName: "PLATFORM_HOSTED", model: "platform-pool", source: "PLATFORM_POOL", needsAttention: false, message: null },
  }),
  "/api/providers/modalities": () => ({
    body: {
      speechToText: [{ provider: "OPENAI", label: "OpenAI Whisper", available: true, implemented: true, unavailabilityReason: null as string | null, models: ["whisper-1"] }],
      textToSpeech: [{ provider: "OPENAI", label: "OpenAI TTS", available: true, implemented: true, unavailabilityReason: null as string | null, models: ["tts-1"] }],
      videoToText: [{ provider: "GOOGLE", label: "Gemini video", available: false, implemented: true, unavailabilityReason: "No Google key activated (sample)", models: ["gemini-2.5-flash"] }],
    },
  }),
  "/api/me": () => ({ body: { subject: account.username, tenantId: "tnt_preview", roles: ["tenant-user"], permissions: [] } }),
  // ---- console-preview additions ----
  "/api/knowledge/collections": () => ({ body: collections }),
  "/api/knowledge/collections/embedding-models": () => ({ body: embeddingModels }),
  "/api/knowledge/stats": () => ({ body: knowledgeStats }),
  "/api/mcp/registry/catalog": () => ({ body: mcpCatalog }),
  "/api/mcp/registry/search": () => ({ body: mcpRegistrySearch }),
  "/api/tools/scripts": () => ({ body: toolScripts }),
  "/api/tools/openapi": () => ({ body: openApiSources }),
  "/api/tools/classifications": () => ({ body: toolClassifications }),
  "/api/tools/code": () => ({ status: 400, body: { error: "Custom code tools are not enabled on this deployment (sample)." } }),
  "/api/admin/users": () => ({ body: adminUsers }),
  "/api/tenant/subscription": () => ({ body: subscription }),
  "/api/tenant/referral": () => ({ body: referral }),
  "/api/tenant/invoices": () => ({ body: invoices }),
  "/api/tenant/spend-protection": () => ({ body: spendProtection }),
  "/api/tenant/trial/status": () => ({ body: { eligible: false, claimed: false } }),
  "/api/admin/llm-usage/summary": () => ({ body: llmUsageSummary }),
  "/api/channels/twilio/credential": () => ({ body: twilioCredential }),
  "/api/sdlc/credential/status": () => ({ body: e2bCredential }),
  "/api/sdlc/credential": () => ({ status: 204, body: null }),
  "/api/sdlc/credential/code-execution-template": () => ({ status: 204, body: null }),
  "/api/channels/google/status": () => ({ body: { connected: false } }),
  // /admin/contacts (ContactsPanel) - reads + creates; see the contacts block.
  "/api/channels/routing": () => ({ body: channelRouting }),
  "/api/channels/pairing/pending": () => ({ body: pendingPairings }),
  "/api/channels/contacts": (url, init) => {
    if ((init?.method || "GET").toUpperCase() === "POST") {
      const body = parseJsonBody(init);
      const contact: MockContact = {
        id: `ct_${nextContactSeq()}`,
        channel: String(body.channel || "whatsapp"),
        identifier: String(body.identifier || ""),
        displayName: (body.displayName as string | null) ?? null,
        groupId: (body.groupId as string | null) ?? null,
        groupName: groupNameFor(body.groupId),
        agentId: (body.agentId as string | null) ?? null,
        agentName: agentNameFor(body.agentId),
        source: "MANUAL",
        sessionId: null,
        paired: false,
        lastMessageAt: null,
        lastSeenAt: null,
        createdAt: new Date().toISOString(),
      };
      contactsStore.unshift(contact);
      return { body: contact };
    }
    const channel = url.searchParams.get("channel");
    const groupId = url.searchParams.get("groupId");
    return { body: contactsStore.filter((c) => (!channel || c.channel === channel) && (!groupId || c.groupId === groupId)) };
  },
  "/api/channels/contacts/import-rows": (_url, init) => {
    const body = parseJsonBody(init);
    const lines = String(body.text || "").split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
    let created = 0;
    for (const line of lines) {
      const [identifier, name] = line.split(",").map((x) => x.trim());
      if (!identifier) continue;
      contactsStore.unshift({
        id: `ct_${nextContactSeq()}`,
        channel: String(body.channel || "whatsapp"),
        identifier,
        displayName: name || null,
        groupId: (body.groupId as string | null) ?? null,
        groupName: groupNameFor(body.groupId),
        agentId: null,
        agentName: null,
        source: "IMPORT",
        sessionId: null,
        paired: false,
        lastMessageAt: null,
        lastSeenAt: null,
        createdAt: new Date().toISOString(),
      });
      created += 1;
    }
    return { body: { created, updated: 0, errors: [] as string[] } };
  },
  "/api/channels/contacts/import": () => ({ body: { created: 0, updated: 0, errors: ["File import is not available in the preview (sample)."] } }),
  "/api/channels/contact-groups": (_url, init) => {
    if ((init?.method || "GET").toUpperCase() === "POST") {
      const body = parseJsonBody(init);
      const group = {
        id: `grp_${nextContactSeq()}`,
        name: String(body.name || "New group"),
        description: (body.description as string | null) ?? null,
        agentId: (body.agentId as string | null) ?? null,
        agentName: agentNameFor(body.agentId),
        memberCount: 0,
      };
      contactGroups.push(group as (typeof contactGroups)[number]);
      return { body: group };
    }
    return { body: contactGroups };
  },
  "/api/session/config": () => ({ body: { idleTimeoutSeconds: 1800, idleWarningSeconds: 120 } }),
};
