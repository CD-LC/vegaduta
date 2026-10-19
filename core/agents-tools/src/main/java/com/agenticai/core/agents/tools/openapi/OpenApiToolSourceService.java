package com.agenticai.core.agents.tools.openapi;

import static com.agenticai.core.agents.tools.openapi.OpenApiSourceSettings.describeAuth;
import static com.agenticai.core.agents.tools.openapi.OpenApiSourceSettings.parseAuthType;
import static com.agenticai.core.agents.tools.openapi.OpenApiSourceSettings.requireBaseUrl;
import static com.agenticai.core.agents.tools.openapi.OpenApiSourceSettings.resolveHeaderName;
import static com.agenticai.core.agents.tools.openapi.OpenApiSourceSettings.resolveOauth2ClientId;
import static com.agenticai.core.agents.tools.openapi.OpenApiSourceSettings.resolveOauth2TokenStyle;
import static com.agenticai.core.agents.tools.openapi.OpenApiSourceSettings.resolveOauth2TokenUrl;
import static com.agenticai.core.agents.tools.openapi.OpenApiSourceSettings.suggestBaseUrl;

import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.security.TenantContext;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Import, refresh and lifecycle of {@link OpenApiToolSource}s and the {@link OpenApiTool}s
 * generated from them - see the entity javadocs for the feature's shape. Tenant scoping is the
 * schema (schema-per-tenant; every repository call here runs against the request's tenant schema
 * exactly like {@code UserToolScriptService}), so there is no tenant id to thread through.
 *
 * <p>{@link #test} runs a tool through the very same {@link OpenApiToolExecutor} path {@link
 * OpenApiToolCallback} uses at chat time, for the reason both sibling features state: "it worked in
 * the tester" and "it works for the agent" must be the same statement.
 *
 * <p>Classification: every generated tool is declared to the {@link ToolClassificationRegistry} as
 * UNTRUSTED_EXTERNAL (its output is third-party content) with sensitivity/determinism derived from
 * the HTTP method - done in {@link #declareClassifications}, which the controller calls AFTER the
 * import transaction commits so a registry hiccup can never roll back an otherwise good import (and
 * a nested rollback-only mark cannot poison the outer transaction).
 */
@Service
public class OpenApiToolSourceService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiToolSourceService.class);

    // A prefix is the leading part of every generated tool name, so it must
    // itself be a valid tool-name stem (^[a-z][a-z0-9_]{2,63}$ overall).
    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final int MAX_TOOL_NAME_LENGTH = 64;
    private static final String CLASSIFICATION_SOURCE = "TENANT_DECLARED";

    private final OpenApiToolSourceRepository sourceRepository;
    private final OpenApiToolRepository toolRepository;
    private final OpenApiSpecParser parser;
    private final OpenApiToolExecutor executor;
    // ObjectProviders for the slim-test-context reason CustomCodeToolService
    // gives: real beans in the app, optional in unit-test wiring.
    private final ObjectProvider<EncryptionService> encryptionService;
    private final ObjectProvider<ToolClassificationRegistry> classificationRegistry;

    public OpenApiToolSourceService(
            OpenApiToolSourceRepository sourceRepository,
            OpenApiToolRepository toolRepository,
            OpenApiSpecParser parser,
            OpenApiToolExecutor executor,
            ObjectProvider<EncryptionService> encryptionService,
            ObjectProvider<ToolClassificationRegistry> classificationRegistry) {
        this.sourceRepository = sourceRepository;
        this.toolRepository = toolRepository;
        this.parser = parser;
        this.executor = executor;
        this.encryptionService = encryptionService;
        this.classificationRegistry = classificationRegistry;
    }

    /**
     * The parsed document plus the exact text and URL that produced it - what a create needs to
     * persist.
     */
    public record Preview(
            String specUrl,
            String specText,
            String toolPrefix,
            String suggestedBaseUrl,
            OpenApiSpecParser.ParsedSpec spec) {}

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public List<OpenApiToolSource> listAll() {
        return sourceRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Only what {@code AgentToolProvider} needs at chat-turn time: enabled tools of enabled
     * sources, source pre-fetched.
     */
    public List<OpenApiTool> listRunnable() {
        return toolRepository.findRunnable();
    }

    /**
     * What a chat turn offers: the runnable tools as immutable snapshots, read through the tenant
     * lookup cache. The query used to join-fetch every source including its spec text on each turn.
     * A write to {@link OpenApiTool} or {@link OpenApiToolSource} on this pod evicts it, another
     * pod sees it within {@code app.chat.hotpath.config-lookup-ttl}, and a TTL of 0 reads the
     * tables every time.
     */
    public List<OpenApiToolSnapshot> listRunnableSnapshots() {
        return HotpathLookups.tenant(
                "tools.openapi",
                () -> listRunnable().stream().map(OpenApiToolSnapshot::from).toList());
    }

    /**
     * The runnable tool by id with its source fetched, re-read when the model calls the tool so an
     * edit or a disable since the turn started takes effect on this call.
     */
    public java.util.Optional<OpenApiTool> findRunnable(UUID id) {
        return toolRepository.findRunnableById(id);
    }

    public OpenApiToolSource get(UUID id) {
        return sourceRepository
                .findById(id)
                .orElseThrow(() -> new OpenApiToolSourceNotFoundException(id));
    }

    public List<OpenApiTool> listTools(UUID sourceId) {
        get(sourceId);
        return toolRepository.findAllBySourceId(sourceId);
    }

    public long countTools(UUID sourceId) {
        return toolRepository.countBySourceId(sourceId);
    }

    public long countEnabledTools(UUID sourceId) {
        return toolRepository.countEnabledBySourceId(sourceId);
    }

    // ------------------------------------------------------------------
    // Preview / create
    // ------------------------------------------------------------------

    /**
     * Fetches (or takes) the document and parses it without persisting anything. The tool prefix is
     * derived from {@code name} when not given so the preview's suggested tool names are the ones a
     * subsequent create will actually use.
     */
    public Preview preview(String name, String toolPrefix, String specUrl, String specText) {
        String prefix = resolvePrefix(toolPrefix, name, null);
        String url = StringUtils.hasText(specUrl) ? specUrl.strip() : null;
        String text = url != null ? executor.fetchSpecText(url) : specText;
        if (!StringUtils.hasText(text)) {
            throw new OpenApiToolException("Provide a spec URL or paste the OpenAPI document.");
        }
        OpenApiSpecParser.ParsedSpec spec = parser.parse(text, prefix);
        return new Preview(url, text, prefix, suggestBaseUrl(spec.serverUrl(), url), spec);
    }

    @Transactional
    public OpenApiToolSource create(
            String name,
            String toolPrefix,
            String specUrl,
            String specText,
            String baseUrl,
            String authTypeRaw,
            String authHeaderName,
            String secret,
            String oauth2TokenUrl,
            String oauth2ClientId,
            String oauth2Scope,
            String oauth2TokenStyle,
            List<String> enabledOperationKeys) {
        if (!StringUtils.hasText(name)) {
            throw new OpenApiToolException("Name is required.");
        }
        Preview preview = preview(name, toolPrefix, specUrl, specText);
        String prefix = preview.toolPrefix();
        if (sourceRepository.existsByToolPrefix(prefix)) {
            throw new OpenApiToolException(
                    "The tool prefix \"" + prefix + "\" is already used by another imported API.");
        }
        String resolvedBaseUrl =
                requireBaseUrl(StringUtils.hasText(baseUrl) ? baseUrl : preview.suggestedBaseUrl());
        OpenApiAuthType authType = parseAuthType(authTypeRaw);
        String headerName = resolveHeaderName(authType, authHeaderName);
        String tokenUrl = resolveOauth2TokenUrl(authType, oauth2TokenUrl);
        String clientId = resolveOauth2ClientId(authType, oauth2ClientId);
        String tokenStyle = resolveOauth2TokenStyle(authType, oauth2TokenStyle);
        String scope =
                authType == OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS
                                && StringUtils.hasText(oauth2Scope)
                        ? oauth2Scope.strip()
                        : null;
        String[] stored = encryptSecret(authType, secret, true);

        OpenApiToolSource source =
                new OpenApiToolSource(
                        name.strip(),
                        prefix,
                        preview.specUrl(),
                        preview.specText(),
                        preview.spec().title(),
                        preview.spec().version(),
                        resolvedBaseUrl,
                        authType,
                        headerName,
                        tokenUrl,
                        clientId,
                        scope,
                        tokenStyle,
                        stored[0],
                        stored[1],
                        currentActor());
        source = sourceRepository.save(source);

        Set<String> enabledKeys =
                enabledOperationKeys == null ? null : new HashSet<>(enabledOperationKeys);
        Set<String> namesInBatch = new HashSet<>();
        for (OpenApiSpecParser.ParsedOperation op : preview.spec().operations()) {
            boolean enabled = enabledKeys == null || enabledKeys.contains(op.operationKey());
            String toolName = uniqueAcrossTenant(op.suggestedToolName(), namesInBatch);
            source.getTools().add(new OpenApiTool(source, toolName, op, enabled));
        }
        source = sourceRepository.save(source);
        log.info(
                "Imported OpenAPI source '{}' ({} operations, prefix {})",
                source.getName(),
                source.getTools().size(),
                prefix);
        return source;
    }

    // ------------------------------------------------------------------
    // Update / refresh / toggle / delete
    // ------------------------------------------------------------------

    /**
     * Settings only - the document itself changes through {@link #refresh}. A blank {@code secret}
     * keeps the stored one.
     */
    @Transactional
    public OpenApiToolSource updateSettings(
            UUID id,
            String name,
            String baseUrl,
            String authTypeRaw,
            String authHeaderName,
            String secret,
            String oauth2TokenUrl,
            String oauth2ClientId,
            String oauth2Scope,
            String oauth2TokenStyle) {
        OpenApiToolSource source = get(id);
        if (!StringUtils.hasText(name)) {
            throw new OpenApiToolException("Name is required.");
        }
        String resolvedBaseUrl =
                requireBaseUrl(StringUtils.hasText(baseUrl) ? baseUrl : source.getBaseUrl());
        OpenApiAuthType authType = parseAuthType(authTypeRaw);
        String headerName = resolveHeaderName(authType, authHeaderName);
        String tokenUrl = resolveOauth2TokenUrl(authType, oauth2TokenUrl);
        String clientId = resolveOauth2ClientId(authType, oauth2ClientId);
        String tokenStyle = resolveOauth2TokenStyle(authType, oauth2TokenStyle);
        String scope =
                authType == OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS
                                && StringUtils.hasText(oauth2Scope)
                        ? oauth2Scope.strip()
                        : null;
        source.updateSettings(
                name.strip(),
                resolvedBaseUrl,
                authType,
                headerName,
                tokenUrl,
                clientId,
                scope,
                tokenStyle);
        if (authType == OpenApiAuthType.NONE) {
            source.replaceSecret(null, null);
        } else if (StringUtils.hasText(secret)) {
            String[] stored = encryptSecret(authType, secret, true);
            source.replaceSecret(stored[0], stored[1]);
        } else if (!source.hasSecret()) {
            throw new OpenApiToolException(
                    "A credential is required for " + describeAuth(authType) + ".");
        }
        return sourceRepository.save(source);
    }

    /**
     * Re-fetches (URL sources) or re-parses (pasted sources) the document and reconciles the
     * generated tools by {@code operationKey}: existing tools keep their name and enabled flag and
     * get the new schema, new operations are added enabled, vanished operations are removed. A
     * fetch/parse failure is RECORDED on the source ({@code lastError}) rather than thrown, so the
     * console can show "refresh failed: ..." next to a source whose existing tools keep working
     * unchanged.
     */
    @Transactional
    public OpenApiToolSource refresh(UUID id) {
        OpenApiToolSource source = get(id);
        OpenApiSpecParser.ParsedSpec spec;
        String text;
        try {
            text =
                    StringUtils.hasText(source.getSpecUrl())
                            ? executor.fetchSpecText(source.getSpecUrl())
                            : source.getSpecText();
            spec = parser.parse(text, source.getToolPrefix());
        } catch (IllegalArgumentException e) {
            source.recordRefreshFailure(e.getMessage());
            return sourceRepository.save(source);
        }

        Map<String, OpenApiTool> existing = new LinkedHashMap<>();
        for (OpenApiTool tool : source.getTools()) {
            existing.put(tool.getOperationKey(), tool);
        }
        Set<String> seen = new HashSet<>();
        Set<String> namesInBatch = new HashSet<>();
        int added = 0;
        for (OpenApiSpecParser.ParsedOperation op : spec.operations()) {
            seen.add(op.operationKey());
            OpenApiTool tool = existing.get(op.operationKey());
            if (tool != null) {
                tool.applyOperation(op);
                namesInBatch.add(tool.getToolName());
            }
        }
        for (OpenApiSpecParser.ParsedOperation op : spec.operations()) {
            if (existing.containsKey(op.operationKey())) {
                continue;
            }
            String toolName = uniqueAcrossTenant(op.suggestedToolName(), namesInBatch);
            source.getTools().add(new OpenApiTool(source, toolName, op, true));
            added++;
        }
        int before = source.getTools().size();
        source.getTools().removeIf(tool -> !seen.contains(tool.getOperationKey()));
        int removed = before - source.getTools().size();
        source.recordRefresh(source.getSpecUrl(), text, spec.title(), spec.version());
        OpenApiToolSource saved = sourceRepository.save(source);
        log.info(
                "Refreshed OpenAPI source '{}': {} added, {} removed, {} total",
                saved.getName(),
                added,
                removed,
                saved.getTools().size());
        return saved;
    }

    @Transactional
    public OpenApiToolSource setEnabled(UUID id, boolean enabled) {
        OpenApiToolSource source = get(id);
        source.setEnabled(enabled);
        return sourceRepository.save(source);
    }

    @Transactional
    public OpenApiTool setToolEnabled(UUID sourceId, UUID toolId, boolean enabled) {
        OpenApiTool tool = getTool(sourceId, toolId);
        tool.setEnabled(enabled);
        return toolRepository.save(tool);
    }

    @Transactional
    public void delete(UUID id) {
        OpenApiToolSource source = get(id);
        sourceRepository.delete(source);
        log.info("Deleted OpenAPI source '{}' and its tools", source.getName());
    }

    /**
     * Console "Try it": same executor, same result shape the agent would see. Never persists
     * anything.
     */
    public String test(UUID sourceId, UUID toolId, String inputJson) {
        OpenApiTool tool = getTool(sourceId, toolId);
        return executor.execute(tool, inputJson);
    }

    /**
     * Declares a classification for every tool of {@code sourceId} that does not already have one
     * recorded by this call's convention. NOT transactional on purpose (see class javadoc): each
     * {@code declare} is its own transaction and a failure only logs. Idempotent - the registry
     * upserts by tool name.
     */
    public void declareClassifications(UUID sourceId) {
        ToolClassificationRegistry registry = classificationRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        OpenApiToolSource source;
        List<OpenApiTool> tools;
        try {
            source = get(sourceId);
            tools = toolRepository.findAllBySourceId(sourceId);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not load OpenAPI source {} to classify its tools: {}",
                    sourceId,
                    e.getMessage());
            return;
        }
        String actor = currentActor();
        for (OpenApiTool tool : tools) {
            String method =
                    tool.getHttpMethod() == null
                            ? "GET"
                            : tool.getHttpMethod().toUpperCase(Locale.ROOT);
            boolean read =
                    method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS");
            ToolSensitivity sensitivity;
            if (!read) {
                sensitivity = ToolSensitivity.EGRESS;
            } else if (source.getAuthType() != null
                    && source.getAuthType() != OpenApiAuthType.NONE) {
                sensitivity = ToolSensitivity.CREDENTIALED;
            } else {
                sensitivity = ToolSensitivity.READ_PUBLIC;
            }
            ToolDeterminism determinism = read ? ToolDeterminism.PURE : ToolDeterminism.SIDE_EFFECT;
            boolean irreversible = method.equals("DELETE");
            try {
                registry.declare(
                        new ToolClassification(
                                tool.getToolName(),
                                ToolTrustTier.UNTRUSTED_EXTERNAL,
                                sensitivity,
                                determinism,
                                null,
                                irreversible,
                                true),
                        CLASSIFICATION_SOURCE,
                        "Imported from OpenAPI source \""
                                + source.getName()
                                + "\" ("
                                + tool.getOperationKey()
                                + "). "
                                + "Auto-classified by HTTP method; tighten in the classification panel if this operation does more than its verb suggests.",
                        actor);
            } catch (RuntimeException e) {
                log.warn(
                        "Could not declare a classification for OpenAPI tool {}: {}",
                        tool.getToolName(),
                        e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private OpenApiTool getTool(UUID sourceId, UUID toolId) {
        OpenApiTool tool =
                toolRepository
                        .findById(toolId)
                        .orElseThrow(() -> new OpenApiToolNotFoundException(toolId));
        if (tool.getSource() == null || !tool.getSource().getId().equals(sourceId)) {
            throw new OpenApiToolNotFoundException(toolId);
        }
        return tool;
    }

    /**
     * The given prefix (validated) or one derived from the name; {@code excludingId} unused today
     * but kept for a future rename path.
     */
    private String resolvePrefix(String toolPrefix, String name, UUID excludingId) {
        String prefix;
        if (StringUtils.hasText(toolPrefix)) {
            prefix = toolPrefix.strip().toLowerCase(Locale.ROOT);
            if (!PREFIX_PATTERN.matcher(prefix).matches()) {
                throw new OpenApiToolException(
                        "Tool prefix must be 2-32 lowercase letters, digits, or underscores, starting with a letter (e.g. petstore).");
            }
            return prefix;
        }
        String slug = OpenApiSpecParser.slugify(name);
        if (slug.isBlank() || !Character.isLetter(slug.charAt(0))) {
            slug = "api" + (slug.isBlank() ? "" : "_" + slug);
        }
        if (slug.length() > 32) {
            slug = slug.substring(0, 32).replaceAll("_+$", "");
        }
        if (slug.length() < 2) {
            slug = slug + "_api";
        }
        return slug;
    }

    /**
     * A tool name unique across every imported source in this tenant (the DB constraint is the
     * backstop).
     */
    private String uniqueAcrossTenant(String suggested, Set<String> namesInBatch) {
        String candidate = suggested;
        for (int n = 2;
                namesInBatch.contains(candidate) || toolRepository.existsByToolName(candidate);
                n++) {
            String suffix = "_" + n;
            String base =
                    suggested.length() + suffix.length() > MAX_TOOL_NAME_LENGTH
                            ? suggested
                                    .substring(0, MAX_TOOL_NAME_LENGTH - suffix.length())
                                    .replaceAll("_+$", "")
                            : suggested;
            candidate = base + suffix;
        }
        namesInBatch.add(candidate);
        return candidate;
    }

    /** {ciphertext, preview} - both null for NONE. The plaintext never leaves this method. */
    private String[] encryptSecret(OpenApiAuthType authType, String secret, boolean required) {
        if (authType == OpenApiAuthType.NONE) {
            return new String[] {null, null};
        }
        if (!StringUtils.hasText(secret)) {
            if (required) {
                throw new OpenApiToolException(
                        "A credential is required for " + describeAuth(authType) + ".");
            }
            return new String[] {null, null};
        }
        EncryptionService encryption = encryptionService.getIfAvailable();
        if (encryption == null) {
            throw new OpenApiToolException(
                    "Credential encryption is not configured on this deployment - cannot store an API credential.");
        }
        String value = secret.strip();
        // Same rule as the vault: never a prefix of a short secret.
        String preview =
                value.length() >= 8 && !value.contains("{{")
                        ? "..." + value.substring(value.length() - 4)
                        : null;
        return new String[] {encryption.encrypt(value), preview};
    }

    private static String currentActor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("system");
    }
}
