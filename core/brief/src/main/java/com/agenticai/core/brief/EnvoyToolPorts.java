package com.agenticai.core.brief;

import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolDescriptor;
import com.agenticai.core.agents.tools.code.CustomCodeTool;
import com.agenticai.core.agents.tools.code.CustomCodeToolLanguage;
import com.agenticai.core.agents.tools.code.CustomCodeToolService;
import com.agenticai.core.agents.tools.code.CustomCodeToolsDisabledException;
import com.agenticai.core.agents.tools.code.dto.CodeToolGenerationRequest;
import com.agenticai.core.agents.tools.code.dto.CodeToolGenerationResponse;
import com.agenticai.core.agents.tools.code.generate.CodeToolGenerationService;
import com.agenticai.core.agents.tools.openapi.OpenApiTool;
import com.agenticai.core.agents.tools.openapi.OpenApiToolSource;
import com.agenticai.core.agents.tools.openapi.OpenApiToolSourceService;
import com.agenticai.core.brief.EnvoyPorts.ApplySkipException;
import com.agenticai.core.brief.EnvoyPorts.ToolResult;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.mcp.McpCatalogService;
import com.agenticai.core.mcp.McpConnectDispatcher;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.dto.McpCatalogEntry;
import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.mcp.registry.OfficialMcpRegistryClient;
import com.agenticai.core.mcp.registry.RegistryEnvVarSpec;
import com.agenticai.core.mcp.registry.RegistrySearchResponse;
import com.agenticai.core.mcp.registry.RegistrySearchResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The tool and MCP half of {@link ServiceEnvoyPorts}: OpenAPI and generated code tools, MCP server registration
 * (catalog first, then the official registry) and MCP tool discovery. Secret values are never touched; a credential
 * is passed as a {@code {{secret.NAME}}} reference the platform resolves at call time.
 */
@Component
class EnvoyToolPorts {

    private static final Logger log = LoggerFactory.getLogger(EnvoyToolPorts.class);
    private static final long MCP_WAIT_MS = 8_000L;
    private static final long MCP_POLL_MS = 500L;
    static final String KIND_OPENAPI = "TOOL_OPENAPI";
    static final String KIND_CODE = "TOOL_CODE";

    private final AgentToolProvider toolProvider;
    private final OpenApiToolSourceService openApiService;
    private final CustomCodeToolService codeToolService;
    private final ObjectProvider<CodeToolGenerationService> codeGeneration;
    private final McpRegistryService mcpRegistryService;
    private final McpCatalogService mcpCatalogService;
    private final ObjectProvider<OfficialMcpRegistryClient> registryClient;
    private final ObjectProvider<McpDynamicConnector> mcpConnector;
    private final ObjectProvider<McpConnectDispatcher> mcpDispatcher;

    EnvoyToolPorts(
            AgentToolProvider toolProvider,
            OpenApiToolSourceService openApiService,
            CustomCodeToolService codeToolService,
            ObjectProvider<CodeToolGenerationService> codeGeneration,
            McpRegistryService mcpRegistryService,
            McpCatalogService mcpCatalogService,
            ObjectProvider<OfficialMcpRegistryClient> registryClient,
            ObjectProvider<McpDynamicConnector> mcpConnector,
            ObjectProvider<McpConnectDispatcher> mcpDispatcher) {
        this.toolProvider = toolProvider;
        this.openApiService = openApiService;
        this.codeToolService = codeToolService;
        this.codeGeneration = codeGeneration;
        this.mcpRegistryService = mcpRegistryService;
        this.mcpCatalogService = mcpCatalogService;
        this.registryClient = registryClient;
        this.mcpConnector = mcpConnector;
        this.mcpDispatcher = mcpDispatcher;
    }

    // ------------------------------------------------------------------ tools

    public ToolResult createTool(Brief.ToolSpec spec, String secretName, String nameSuffix) {
        String authType =
                spec.auth() == null || spec.auth().type() == null ? "NONE" : spec.auth().type();
        if ("OPENAPI".equals(spec.kind())) {
            if ("OAUTH2_CLIENT_CREDENTIALS".equals(authType)) {
                throw new ApplySkipException(
                        "OAuth2 client-credentials tools need a token URL and client id that a brief "
                                + "does not carry - add this API in Tools.");
            }
            if (!"NONE".equals(authType) && secretName == null) {
                throw new ApplySkipException(
                        "Its credential is not declared, so the tool cannot be authorised.");
            }
            String secret = "NONE".equals(authType) ? null : "{{secret." + secretName + "}}";
            OpenApiToolSource source =
                    openApiService.create(
                            spec.name(),
                            null,
                            spec.specUrl(),
                            null,
                            null,
                            authType,
                            null,
                            secret,
                            null,
                            null,
                            null,
                            null,
                            null);
            return new ToolResult(KIND_OPENAPI, source.getId().toString());
        }
        CodeToolGenerationService generator = codeGeneration.getIfAvailable();
        if (generator == null) {
            throw new ApplySkipException("Custom code tools are not enabled on this deployment.");
        }
        String toolName = codeToolName(spec.name(), nameSuffix);
        try {
            CodeToolGenerationResponse drafted =
                    generator.generate(
                            new CodeToolGenerationRequest(
                                    spec.codeDescription() == null
                                            ? spec.name()
                                            : spec.codeDescription(),
                                    null,
                                    CustomCodeToolLanguage.PYTHON,
                                    toolName));
            if (!drafted.valid()) {
                throw new ApplySkipException(
                        "The generated code did not pass the sandbox check, so it was not saved. "
                                + "Describe the tool in Tools and adjust it there.");
            }
            CustomCodeTool tool =
                    codeToolService.create(
                            toolName,
                            spec.name(),
                            spec.codeDescription(),
                            CustomCodeToolLanguage.PYTHON,
                            drafted.sourceCode(),
                            null,
                            null,
                            null,
                            null);
            if (secretName != null) {
                codeToolService.setAllowedSecretNames(tool.getId(), Set.of(secretName));
            }
            return new ToolResult(KIND_CODE, tool.getId().toString());
        } catch (CustomCodeToolsDisabledException e) {
            throw new ApplySkipException("Custom code tools are not enabled on this deployment.");
        }
    }

    public void refreshTool(String bindingKind, String id) {
        if (KIND_OPENAPI.equals(bindingKind)) {
            openApiService.refresh(UUID.fromString(id));
        }
    }

    public void deleteTool(String bindingKind, String id) {
        if (KIND_OPENAPI.equals(bindingKind)) {
            openApiService.delete(UUID.fromString(id));
        } else {
            codeToolService.delete(UUID.fromString(id));
        }
    }

    public List<String> toolNames(String bindingKind, String id) {
        if (KIND_OPENAPI.equals(bindingKind)) {
            return openApiService.listTools(UUID.fromString(id)).stream()
                    .filter(OpenApiTool::isEnabled)
                    .map(OpenApiTool::getToolName)
                    .toList();
        }
        return List.of(codeToolService.get(UUID.fromString(id)).getToolName());
    }

    static String codeToolName(String name, String suffix) {
        String base =
                (name == null ? "tool" : name)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "_")
                        .replaceAll("^_+|_+$", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = "tool_" + base;
        }
        if (base.length() > 48) {
            base = base.substring(0, 48);
        }
        return base + "_" + suffix;
    }

    // ------------------------------------------------------------------ MCP

    public String connectMcp(
            String serverName, Brief.McpServerSpec spec, Map<String, String> envSecretNames) {
        McpServerType type;
        String command = null;
        List<String> args = null;
        String url = null;
        String description;
        List<String> secretEnvNames = new ArrayList<>();

        Optional<McpCatalogEntry> catalog =
                mcpCatalogService
                        .find(spec.registryId())
                        .or(() -> mcpCatalogService.find(spec.name()));
        if (catalog.isPresent()) {
            McpCatalogEntry e = catalog.get();
            type = e.type();
            command = e.dynamicCommand();
            args = e.dynamicArgs();
            url = e.dynamicUrl();
            description = e.description();
            if (e.requiredEnvVars() != null) {
                secretEnvNames.addAll(e.requiredEnvVars());
            }
            if (command == null && url == null) {
                throw new ApplySkipException(
                        "This catalog server is wired at deployment, not connected per workspace - "
                                + "enable it in Tools.");
            }
        } else {
            OfficialMcpRegistryClient client = registryClient.getIfAvailable();
            if (client == null) {
                throw new ApplySkipException(
                        "The MCP registry is not available in this environment.");
            }
            RegistrySearchResponse response = client.search(spec.registryId(), null);
            RegistrySearchResult found =
                    response == null || response.unavailable() || response.results() == null
                            ? null
                            : response.results().stream()
                                    .filter(r -> spec.registryId().equals(r.name()))
                                    .findFirst()
                                    .orElse(null);
            if (found == null) {
                throw new ApplySkipException(
                        "The MCP registry could not be reached or does not list '"
                                + spec.registryId()
                                + "'.");
            }
            if (!found.runnable()) {
                throw new ApplySkipException(
                        "This server cannot be run from here"
                                + (found.notRunnableReason() == null
                                        ? "."
                                        : ": " + found.notRunnableReason()));
            }
            type = found.type();
            command = found.command();
            args = found.args();
            url = found.url();
            description = found.description();
            if (found.envVars() != null) {
                for (RegistryEnvVarSpec v : found.envVars()) {
                    if (v.required() && v.secret()) {
                        secretEnvNames.add(v.name());
                    }
                }
            }
        }

        Map<String, String> env = new LinkedHashMap<>();
        for (String envName : secretEnvNames) {
            String secret = envSecretNames.get(envName);
            if (secret == null) {
                throw new ApplySkipException(
                        "Needs " + envName + " stored in the vault before it can connect.");
            }
            env.put(envName, "{{secret." + secret + "}}");
        }
        envSecretNames.forEach((k, v) -> env.putIfAbsent(k, "{{secret." + v + "}}"));

        McpServerRegistrationResponse saved =
                mcpRegistryService.register(
                        new McpServerRegistrationRequest(
                                serverName, type, description, true, command, args, env, url));
        McpDynamicConnector connector = mcpConnector.getIfAvailable();
        McpConnectDispatcher dispatcher = mcpDispatcher.getIfAvailable();
        if (connector != null && dispatcher != null) {
            dispatcher.dispatch(connector, saved.id());
        }
        return saved.id().toString();
    }

    public void deleteMcp(String id) {
        UUID uuid = UUID.fromString(id);
        McpDynamicConnector connector = mcpConnector.getIfAvailable();
        if (connector != null) {
            try {
                connector.disconnect(uuid);
            } catch (RuntimeException e) {
                log.debug("Disconnect of MCP registration {} failed - deleting anyway", id, e);
            }
        }
        mcpRegistryService.delete(uuid);
    }

    public List<String> discoverMcpTools(String agentId, String serverName) {
        String prefix = "mcp__" + serverName + "__";
        long deadline = System.currentTimeMillis() + MCP_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<String> names =
                        toolProvider.listAvailableToolsFor(UUID.fromString(agentId)).stream()
                                .map(ToolDescriptor::name)
                                .filter(n -> n.startsWith(prefix))
                                .sorted()
                                .toList();
                if (!names.isEmpty()) {
                    return names;
                }
                Thread.sleep(MCP_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (RuntimeException e) {
                log.debug("Tool discovery for MCP server {} failed - retrying", serverName, e);
            }
        }
        return List.of();
    }
}
