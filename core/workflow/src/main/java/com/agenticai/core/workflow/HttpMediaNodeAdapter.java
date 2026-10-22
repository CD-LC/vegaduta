package com.agenticai.core.workflow;

import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** "http" and "media": the HTTP Request and sidecar Media nodes. */
@Component
class HttpMediaNodeAdapter implements NodeHandler {

    private final WorkflowHttpMediaNodeHandler httpMediaNodeHandler;

    HttpMediaNodeAdapter(
            ObjectProvider<HttpRequestExecutor> httpRequestExecutor,
            ObjectProvider<SidecarClient> sidecarClient,
            ObjectMapper objectMapper) {
        this.httpMediaNodeHandler =
                new WorkflowHttpMediaNodeHandler(httpRequestExecutor, sidecarClient, objectMapper);
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("http", "media");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return "media".equalsIgnoreCase(inv.node().type())
                ? httpMediaNodeHandler.executeMediaNode(inv.node(), inv.input())
                : httpMediaNodeHandler.executeHttpNode(inv.node(), inv.input());
    }
}
