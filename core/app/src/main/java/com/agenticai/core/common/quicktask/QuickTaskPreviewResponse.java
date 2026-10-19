package com.agenticai.core.common.quicktask;

import com.agenticai.core.agents.tools.ToolDescriptor;
import java.util.List;
import java.util.UUID;

/**
 * "System should detect the underlying workflow if specified, select defined tools/MCP tools" -
 * shown BEFORE the person commits to running anything (see SimpleTaskRunner.js's "Check what this
 * will do" step), using the exact same resolution path a real run takes ({@link
 * AgentToolProvider#resolveToolsFor}) so this is never a guess or a separate, potentially-drifting
 * prediction - it's a dry read of what {@link QuickTaskService#run} would actually bind for this
 * message, with nothing executed and nothing dispatched.
 *
 * @param agentName which agent (of the tenant's agents) will run this - always the same one {@link
 *     QuickTaskService#run} picks, so the preview and the real run can never disagree.
 * @param detectedTools every tool this specific message would cause the agent to have available, in
 *     relevance order - empty just means "no tools matched, the agent will answer from its own
 *     knowledge/model," not an error.
 * @param thirdPartyNumber #598 follow-up ("let Quick Task detect & send to a typed number") -
 *     non-null only when the task's own text names a phone number other than the requester's own,
 *     in which case the real run will route delivery through {@code HumanApprovalGateService}
 *     instead of sending straight to the requester's own number - see {@code
 *     QuickTaskService#dispatchToThirdParty}'s javadoc. Lets the UI warn "this will need approval"
 *     before the person commits, rather than surprising them after the fact.
 * @param agentId the resolved agent's id - lets the UI (or a caller that pinned agentId in the
 *     request) correlate the preview with the agent record without a second lookup.
 * @param providerName the AI provider the resolved agent is configured to run on (ProviderName enum
 *     name, e.g. "PLATFORM_HOSTED" for the included platform pool) - so the confirmation card can
 *     disclose "what will power this" before the person commits, not just afterwards. Nullable only
 *     for malformed agents.
 * @param model the model id the resolved agent is configured to use, or null when none is pinned (a
 *     PLATFORM_HOSTED agent with no explicit model lets the pool pick).
 */
public record QuickTaskPreviewResponse(
        String agentName,
        List<ToolDescriptor> detectedTools,
        String thirdPartyNumber,
        UUID agentId,
        String providerName,
        String model) {}
