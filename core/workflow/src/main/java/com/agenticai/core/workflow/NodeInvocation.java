package com.agenticai.core.workflow;

import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.List;

/**
 * Everything one node execution needs, handed to a {@link NodeHandler}.
 *
 * @param node the node with its {{ }} config already resolved
 * @param unresolvedNode the stored node (some operations decide on what the author actually wrote)
 * @param input the upstream output flowing into this node
 * @param expressions the expression resolver, for the few handlers that evaluate expressions at run
 *     time (condition, bulk email)
 * @param evalInfo identifies the run for expression evaluation
 * @param scope the bound expression scope, may be null
 * @param whatsAppTemplatesEnabled the {@code app.omnichannel.whatsapp.templates.enabled} flag
 */
public record NodeInvocation(
        WorkflowNodeDto node,
        WorkflowNodeDto unresolvedNode,
        String input,
        String workflowDefinitionId,
        String workflowDefinitionName,
        String runId,
        String requestedBy,
        WorkflowIntelligenceSettings intelligenceSettings,
        List<ChatInlineAttachment> attachments,
        WorkflowExpressionResolver expressions,
        WorkflowExpressionResolver.NodeEvalInfo evalInfo,
        ExpressionScope scope,
        boolean whatsAppTemplatesEnabled) {}
