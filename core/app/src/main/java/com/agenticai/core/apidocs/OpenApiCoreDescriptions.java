package com.agenticai.core.apidocs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Curated descriptions for the routes an integration starts with (agents, chat, workflows,
 * webhooks, API keys, audit). Same keying and drift test as {@link OpenApiDescriptions}, which
 * consults this map after its own; kept apart so neither file grows past the size cap.
 */
final class OpenApiCoreDescriptions {

    static final Map<String, String> BY_VERB_AND_PATH;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(
                "POST /api/auth/local/reset-request",
                "Ask for a password reset on a personal install. The one-time reset link is never"
                        + " returned here: core writes it to password-reset.txt in the install's"
                        + " auth data directory (scripts/vegaduta reset-password prints it), so"
                        + " only someone with access to the machine itself can use it. The answer"
                        + " is the same for an unknown e-mail - it reveals nothing about the"
                        + " account. A blank e-mail resolves to the install's only account.");
        m.put(
                "GET /api/agents",
                "The tenant's agents the caller can see, newest first. Pass limit (and offset)"
                        + " for a page with X-Total-Count and X-Has-More.");
        m.put("POST /api/agents", "Create an agent. Answers 201 with the new agent.");
        m.put(
                "GET /api/agents/{agentId}",
                "One agent with its settings. The ETag header is its current version: send it"
                        + " as If-Match on a settings PATCH to avoid overwriting a newer change.");
        m.put(
                "DELETE /api/agents/{agentId}",
                "Delete the agent. Refused with 409 while a workflow or another agent still uses"
                        + " it.");
        m.put(
                "POST /api/agents/{agentId}/chat",
                "Run one chat turn and stream the reply as Server-Sent Events: each event's data"
                        + " is the next text chunk. Send the same sessionId to continue a"
                        + " conversation; the server keeps the memory.");
        m.put(
                "GET /api/workflows",
                "The tenant's workflows the caller can see. Pass limit (and offset) for a page.");
        m.put("POST /api/workflows", "Create a workflow from a name, description and graph.");
        m.put(
                "GET /api/workflows/{workflowId}",
                "One workflow definition with its graph. The ETag header is its current version"
                        + " for If-Match on PUT.");
        m.put(
                "PUT /api/workflows/{workflowId}",
                "Replace the workflow's name, description, graph and status. Send If-Match with"
                        + " the ETag from GET to refuse the write (412) when someone else changed"
                        + " it first.");
        m.put(
                "POST /api/workflows/{workflowId}/run",
                "Start a run. Idempotency-Key here keeps its original meaning: a repeated key"
                        + " answers 409 rather than replaying; use clientRunId to find the first"
                        + " run.");
        m.put(
                "GET /api/workflows/{workflowId}/runs",
                "Run history, newest first (startedAt, then id). Page with limit and offset, or"
                        + " follow X-Next-Cursor with cursor for deep history.");
        m.put(
                "GET /api/workflows/{workflowId}/runs/{runId}/events",
                "Live run progress as Server-Sent Events named snapshot, run.*, node.* and error;"
                        + " each data is {seq, type, nodeId, ts, data}. Reconnect with"
                        + " Last-Event-ID to resume after the last seq seen.");
        m.put(
                "GET /api/webhooks/event-types",
                "The documented event types a subscription can name; \"*\" and \"prefix.*\" also"
                        + " work. Delivery format: the webhooks section of this document.");
        m.put(
                "POST /api/webhooks",
                "Subscribe a URL to platform events. The response carries the signing secret"
                        + " (whsec_...) once; verify X-VegaDuta-Signature with it.");
        m.put(
                "POST /api/webhooks/{id}/test",
                "Queue a webhook.test delivery to the subscription's URL.");
        m.put(
                "POST /api/webhooks/{id}/rotate-secret",
                "Issue a new signing secret; deliveries are signed with it from now on.");
        m.put(
                "GET /api/webhooks/{id}/deliveries",
                "Recent delivery attempts with status, so a receiver can see what failed and why.");
        m.put(
                "POST /api/tenant/api-keys",
                "Mint a tenant API key (vdak_...) for server-to-server calls. The secret is in"
                        + " this response only; store it. The key acts with the PERM_* scopes it"
                        + " is given.");
        m.put(
                "DELETE /api/tenant/api-keys/{id}",
                "Revoke the key; requests that present it are refused from now on.");
        m.put(
                "GET /api/audit-log",
                "The tenant's audit trail, newest first, filtered by actionType, targetType,"
                        + " actor, q and a from/to window. Without limit or offset: the newest 200."
                        + " With limit: a page with X-Total-Count; follow X-Next-Cursor with cursor"
                        + " for deep history.");
        BY_VERB_AND_PATH = Collections.unmodifiableMap(m);
    }

    private OpenApiCoreDescriptions() {}
}
