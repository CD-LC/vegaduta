package com.agenticai.core.agents.tools.code;

import com.agenticai.core.agents.tools.code.dto.SandboxExecuteRequest;
import com.agenticai.core.agents.tools.code.dto.SandboxExecuteResponse;
import com.agenticai.core.agents.tools.code.dto.SandboxValidateResponse;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.secrets.SecretRedactor;
import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * CRUD, validation, and execution for tenant-authored {@link CustomCodeTool} rows - see that
 * entity's javadoc for how this differs from the SpEL-only {@code UserToolScript} feature it is
 * modelled on.
 *
 * <p>{@link #test} runs through the exact same {@link #executeNode} path {@link
 * CustomCodeToolCallback} uses at real call time, for the same reason {@code
 * UserToolScriptService#test} shares its evaluator with the callback: "it worked in the tester" and
 * "it works for the agent" must mean the same thing, and they only do if there is literally one
 * code path.
 *
 * <p><strong>A rejected tool is still saved.</strong> When the sandbox refuses to compile a
 * program, the row persists with {@code status=REJECTED} and the diagnostics in {@code
 * validationError}, and simply never becomes runnable. Discarding it on save would leave the tenant
 * staring at an error toast with their work gone - the opposite of useful for a feature whose
 * entire point is iterating on code.
 */
@Service
public class CustomCodeToolService {

    private static final Logger log = LoggerFactory.getLogger(CustomCodeToolService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    // Identical to UserToolScriptService's - the shape every tool name in this
    // codebase already uses (google_drive, sarvam_translate, ask_agent_<slug>).
    // Copied rather than shared because these are two independent features that
    // happen to agree today; the constraint belongs to each one's own contract
    // with the models that call its tools.
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    /**
     * Mirrors SecretVaultService.VALID_NAME. Kept as its own constant rather than imported so this
     * module does not depend on a private detail of the vault, but the two must agree - a name that
     * passes here and fails there would be storable as a grant and never resolvable.
     */
    private static final Pattern SECRET_NAME_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final CustomCodeToolRepository repository;
    private final ToolSandboxProperties properties;
    // ObjectProvider, not a plain field: the client bean only exists when
    // tool-sandbox.enabled=true (see ToolSandboxClient's @ConditionalOnProperty),
    // so this service must be constructible - and its read paths usable - on a
    // deployment where the feature is off.
    private final ObjectProvider<ToolSandboxClient> sandboxClient;
    // ObjectProvider for the cross-module-optional reason every other call site
    // in this codebase uses one for AuditLogService: core/audit is on the
    // classpath in the real app, but slim test contexts have no such bean.
    private final ObjectProvider<AuditLogService> auditLogService;
    // Same ObjectProvider reasoning as the two above: core/secrets is on the
    // classpath in the real app, but the slim unit-test contexts for this
    // service construct it with mocks and no vault at all. A tool that declares
    // no secrets must keep working when the vault bean is absent.
    private final ObjectProvider<SecretVaultService> secretVaultService;
    private final ObjectProvider<SecretRedactor> secretRedactor;

    public CustomCodeToolService(
            CustomCodeToolRepository repository,
            ToolSandboxProperties properties,
            ObjectProvider<ToolSandboxClient> sandboxClient,
            ObjectProvider<AuditLogService> auditLogService,
            ObjectProvider<SecretVaultService> secretVaultService,
            ObjectProvider<SecretRedactor> secretRedactor) {
        this.repository = repository;
        this.properties = properties;
        this.sandboxClient = sandboxClient;
        this.auditLogService = auditLogService;
        this.secretVaultService = secretVaultService;
        this.secretRedactor = secretRedactor;
    }

    // L6: optional alternative execution backend (flag tool-sandbox.e2b-execution). Setter
    // injection so the constructor arity - and every test that calls it - is unchanged.
    private E2bCustomToolExecutor e2bExecutor;

    @Autowired(required = false)
    public void setE2bExecutor(E2bCustomToolExecutor e2bExecutor) {
        this.e2bExecutor = e2bExecutor;
    }

    public List<CustomCodeTool> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Only what {@code AgentToolProvider} may offer an agent: enabled AND accepted by the sandbox.
     * Note this is a repository-level filter, not a post-filter, so a REJECTED row costs nothing at
     * chat-turn time.
     */
    public List<CustomCodeTool> listRunnable() {
        return repository.findAllByEnabledTrueAndStatus(CustomCodeToolStatus.VALIDATED);
    }

    /**
     * What a chat turn offers: the runnable tools as immutable snapshots, read through the tenant
     * lookup cache. A write to {@link CustomCodeTool} on this pod evicts it, another pod sees it
     * within {@code app.chat.hotpath.config-lookup-ttl}, and a TTL of 0 reads the table every time.
     * The source code and secret names are not in a snapshot; {@link #executeById} re-reads the row
     * when the model calls the tool.
     */
    public List<CustomCodeToolSnapshot> listRunnableSnapshots() {
        return HotpathLookups.tenant(
                "tools.custom-code",
                () -> listRunnable().stream().map(CustomCodeToolSnapshot::from).toList());
    }

    /**
     * Runs the tool the model called, re-reading its row first so an edit, a disable or a delete
     * since the turn started takes effect on this call. A tool that is no longer runnable comes
     * back as an error JSON the model can act on, never an exception.
     */
    public String executeById(UUID id, String inputJson) {
        CustomCodeTool tool =
                repository.findById(id).filter(CustomCodeTool::isRunnable).orElse(null);
        if (tool == null) {
            return errorJson(
                    "This tool is no longer available: it was disabled, removed or no longer"
                            + " passes validation.",
                    "UNAVAILABLE",
                    null);
        }
        return execute(tool, inputJson);
    }

    public CustomCodeTool get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new CustomCodeToolNotFoundException(id));
    }

    @Transactional
    public CustomCodeTool create(
            String toolName,
            String displayName,
            String description,
            CustomCodeToolLanguage language,
            String sourceCode,
            String inputSchemaJson,
            String outputDescription,
            Integer timeoutMs,
            Integer memoryMb) {
        // Cap first: a tenant at their ceiling should be told so before we
        // spend a sandbox compile on code we're going to refuse anyway.
        long existing = repository.count();
        if (existing >= properties.getMaxToolsPerTenant()) {
            throw new TenantLimitExceededException(
                    "This workspace already has the maximum of "
                            + properties.getMaxToolsPerTenant()
                            + " custom code tools. Delete one before adding another.");
        }
        validateToolName(toolName, null);
        validateLanguage(language);
        validateSource(sourceCode);

        CustomCodeTool tool =
                new CustomCodeTool(
                        toolName,
                        displayName,
                        description,
                        language,
                        sourceCode,
                        inputSchemaJson,
                        outputDescription,
                        sha256Hex(sourceCode),
                        clampTimeout(timeoutMs),
                        clampMemory(memoryMb),
                        currentActor());
        applyValidationVerdict(tool);
        CustomCodeTool saved = repository.save(tool);
        audit(
                "custom_code_tool.create",
                saved,
                "language=" + language + " status=" + saved.getStatus());
        return saved;
    }

    @Transactional
    public CustomCodeTool update(
            UUID id,
            String displayName,
            String description,
            CustomCodeToolLanguage language,
            String sourceCode,
            String inputSchemaJson,
            String outputDescription,
            Integer timeoutMs,
            Integer memoryMb) {
        CustomCodeTool tool = get(id);
        validateLanguage(language);
        validateSource(sourceCode);
        // toolName is deliberately not updatable - an agent's allowed-tool
        // list and an LLM's learned call site both key off the literal name.
        // Same immutability UserToolScriptRequest documents.
        tool.update(
                displayName,
                description,
                language,
                sourceCode,
                inputSchemaJson,
                outputDescription,
                sha256Hex(sourceCode),
                clampTimeout(timeoutMs),
                clampMemory(memoryMb));
        applyValidationVerdict(tool);
        CustomCodeTool saved = repository.save(tool);
        audit(
                "custom_code_tool.update",
                saved,
                "version=" + saved.getVersion() + " status=" + saved.getStatus());
        return saved;
    }

    /**
     * Re-runs validation against the stored source without changing it - for the "the sandbox was
     * down when I saved" case, and after a sandbox upgrade changes what compiles. Never bumps
     * {@code version}: the code is identical.
     */
    @Transactional
    public CustomCodeTool revalidate(UUID id) {
        CustomCodeTool tool = get(id);
        applyValidationVerdict(tool);
        CustomCodeTool saved = repository.save(tool);
        audit("custom_code_tool.revalidate", saved, "status=" + saved.getStatus());
        return saved;
    }

    @Transactional
    public CustomCodeTool setEnabled(UUID id, boolean enabled) {
        CustomCodeTool tool = get(id);
        if (enabled && tool.getStatus() != CustomCodeToolStatus.VALIDATED) {
            // Refused rather than silently ignored: flipping the switch and
            // watching nothing happen is worse than being told why. 400 via
            // GlobalExceptionHandler's IllegalArgumentException handler.
            throw new IllegalArgumentException(
                    "This tool cannot be enabled until it passes validation (current status: "
                            + tool.getStatus()
                            + "). Fix the reported errors and save again.");
        }
        tool.setEnabled(enabled);
        CustomCodeTool saved = repository.save(tool);
        audit("custom_code_tool.enable", saved, "enabled=" + enabled);
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        CustomCodeTool tool = get(id);
        repository.delete(tool);
        audit("custom_code_tool.delete", tool, "toolName=" + tool.getToolName());
    }

    /**
     * Dry run from the editor's "Test" button - same sandbox call, same limits, same result shaping
     * an agent would get. Does not persist anything.
     */
    public String test(UUID id, JsonNode sampleInput) {
        return executeNode(get(id), sampleInput == null ? MAPPER.createObjectNode() : sampleInput);
    }

    /**
     * The agent-facing entry point, called by {@link CustomCodeToolCallback}. Returns the JSON
     * string to hand the model.
     *
     * <p>Throws {@link ToolSandboxUnavailableException} / {@link CustomCodeToolsDisabledException}
     * on infrastructure problems - the callback catches those and converts them, because a tool
     * callback must never blow up a chat turn; a plain failed execution comes back as an {@code
     * {"error": ...}} JSON string here rather than as an exception.
     */
    public String execute(CustomCodeTool tool, String inputJson) {
        JsonNode input;
        if (!StringUtils.hasText(inputJson)) {
            input = MAPPER.createObjectNode();
        } else {
            try {
                input = MAPPER.readTree(inputJson);
            } catch (Exception e) {
                // The model sent something that isn't JSON. Its own mistake and
                // its own to fix on the next turn - tell it plainly instead of
                // failing the request.
                return errorJson(
                        "Tool input was not valid JSON: " + e.getMessage(), "VALIDATION", null);
            }
        }
        return executeNode(tool, input);
    }

    /**
     * Replaces which vault secrets this tool may receive.
     *
     * <p>A separate operation rather than another parameter on create/update, mirroring {@code
     * AgentService#setAllowedToolNames}: granting a program access to a credential is a different
     * kind of decision from editing its code, it is made by a different person at a different time,
     * and keeping it separate means a routine code edit cannot silently widen access.
     *
     * <p>Names are validated for SHAPE only, not existence. A tool may declare a secret before
     * anyone has stored it - order should not matter, and failing here would force people to create
     * credentials in a particular sequence. A name that never resolves is reported at execution
     * time instead (see {@link #resolveSecretsFor}).
     */
    @Transactional
    public CustomCodeTool setAllowedSecretNames(UUID id, Set<String> names) {
        CustomCodeTool tool = get(id);
        Set<String> cleaned = new LinkedHashSet<>();
        if (names != null) {
            for (String raw : names) {
                String name = raw == null ? "" : raw.trim();
                if (!SECRET_NAME_PATTERN.matcher(name).matches()) {
                    throw new IllegalArgumentException(
                            "\""
                                    + name
                                    + "\" is not a valid secret name. Use capitals, digits and "
                                    + "underscores, starting with a letter.");
                }
                cleaned.add(name);
            }
        }
        tool.setAllowedSecretNames(cleaned);
        CustomCodeTool saved = repository.save(tool);
        audit(
                "custom_code_tool.secrets",
                saved,
                cleaned.isEmpty()
                        ? "Cleared the secret allow-list"
                        : "Allowed secrets: " + cleaned);
        return saved;
    }

    private String executeNode(CustomCodeTool tool, JsonNode input) {
        Map<String, String> secrets = resolveSecretsFor(tool);
        SandboxExecuteResponse response;
        if (properties.isE2bExecution()
                && e2bExecutor != null
                && e2bExecutor.supports(tool.getLanguage(), secrets)) {
            response = e2bExecutor.execute(tool.getSourceCode(), input, tool.getTimeoutMs());
        } else {
            response =
                    requireClient()
                            .execute(
                                    new SandboxExecuteRequest(
                                            tool.getLanguage().wireValue(),
                                            tool.getToolName(),
                                            tool.getSourceCode(),
                                            input,
                                            tool.getTimeoutMs(),
                                            tool.getMemoryMb(),
                                            secrets));
        }
        if (response == null) {
            throw new ToolSandboxUnavailableException(
                    "The code sandbox returned an empty response.");
        }
        if (!response.ok()) {
            // Redact BEFORE the error reaches the model. A failing program is
            // exactly the case that prints its own configuration: a stack trace
            // quoting the Authorization header it built, or a stderr line
            // echoing the key it was handed. This is where that leaks if it is
            // going to.
            return errorJson(
                    redact(
                            StringUtils.hasText(response.error())
                                    ? response.error()
                                    : "The program failed.",
                            secrets),
                    response.errorType(),
                    redact(response.stderr(), secrets));
        }
        JsonNode result = response.result();
        if (result != null && !result.isNull()) {
            // Hand the model the program's own JSON verbatim - wrapping it in an
            // envelope would make every tenant's tool harder for a model to read
            // for no benefit. Redacted all the same: a tool that echoes what it
            // sent upstream would otherwise put the key in the transcript.
            return redact(result.toString(), secrets);
        }
        // The program produced no return value. Say so explicitly rather than
        // returning "null", which models routinely misread as a failure.
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ok", true);
        node.put("result", (String) null);
        if (StringUtils.hasText(response.stdout())) {
            node.put("stdout", redact(response.stdout(), secrets));
        }
        return node.toString();
    }

    // ------------------------------------------------------------------ //

    /**
     * Asks the sandbox for a verdict and stamps it onto {@code tool}. Never catches {@link
     * ToolSandboxUnavailableException}: a sandbox outage must NOT be recorded as "your code is bad"
     * - see {@link ToolSandboxClient}'s javadoc. The save is therefore rolled back with the
     * caller's transaction and the tenant gets a 502 telling them to retry, which is the truth.
     */
    private void applyValidationVerdict(CustomCodeTool tool) {
        SandboxValidateResponse verdict =
                requireClient()
                        .validate(tool.getLanguage(), tool.getToolName(), tool.getSourceCode());
        if (verdict == null) {
            throw new ToolSandboxUnavailableException(
                    "The code sandbox returned an empty validation response.");
        }
        if (verdict.valid()) {
            tool.markValidated();
            return;
        }
        String joined =
                verdict.errorsOrEmpty().stream()
                        .map(e -> (e.line() != null ? "line " + e.line() + ": " : "") + e.message())
                        .collect(Collectors.joining("\n"));
        tool.markRejected(
                joined.isBlank()
                        ? "The sandbox rejected this program but reported no details."
                        : joined);
    }

    private ToolSandboxClient requireClient() {
        ToolSandboxClient client = sandboxClient.getIfAvailable();
        if (client == null) {
            throw new CustomCodeToolsDisabledException();
        }
        return client;
    }

    private void validateToolName(String toolName, UUID excludingId) {
        if (toolName == null || !TOOL_NAME_PATTERN.matcher(toolName).matches()) {
            throw new IllegalArgumentException(
                    "Tool name must be 3-64 lowercase letters, digits, or underscores, starting with a letter (e.g. my_custom_tool).");
        }
        boolean taken =
                excludingId == null
                        ? repository.existsByToolName(toolName)
                        : repository.existsByToolNameAndIdNot(toolName, excludingId);
        if (taken) {
            throw new IllegalArgumentException("A tool named \"" + toolName + "\" already exists.");
        }
    }

    private void validateLanguage(CustomCodeToolLanguage language) {
        if (language == null) {
            throw new IllegalArgumentException("Language is required (JAVA or PYTHON).");
        }
    }

    private void validateSource(String sourceCode) {
        if (!StringUtils.hasText(sourceCode)) {
            throw new IllegalArgumentException("Source code is required.");
        }
        // Bytes, not chars - the cap exists to bound what we ship over the wire
        // and what the sandbox has to compile, and a UTF-8 char is 1-4 bytes.
        int bytes = sourceCode.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > properties.getMaxSourceBytes()) {
            throw new IllegalArgumentException(
                    "Source code is too large ("
                            + bytes
                            + " bytes; the limit is "
                            + properties.getMaxSourceBytes()
                            + ").");
        }
    }

    /**
     * Clamps rather than rejects: an out-of-range timeout is a UI/API caller being optimistic, not
     * an error worth failing a save over, and silently getting the platform ceiling is what the
     * tenant would have picked anyway. The stored value is therefore always directly usable.
     */
    private int clampTimeout(Integer requested) {
        int value = requested == null ? properties.getDefaultTimeoutMs() : requested;
        return Math.max(1, Math.min(value, properties.getMaxTimeoutMs()));
    }

    private int clampMemory(Integer requested) {
        int value = requested == null ? properties.getDefaultMemoryMb() : requested;
        return Math.max(1, Math.min(value, properties.getMaxMemoryMb()));
    }

    private static String currentActor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("system");
    }

    private void audit(String actionType, CustomCodeTool tool, String detail) {
        AuditLogService service = auditLogService.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            service.record(
                    currentActor(),
                    actionType,
                    "custom_code_tool",
                    tool.getId() == null ? tool.getToolName() : tool.getId().toString(),
                    AuditOutcome.SUCCESS,
                    detail);
        } catch (Exception e) {
            // Never let an audit write fail the action it describes - same
            // per-call-site try/catch every other AuditLogService caller uses.
            log.warn("Failed to write audit entry for {}: {}", actionType, e.getMessage());
        }
    }

    /**
     * Jackson-built, never string-concatenated - the message can contain quotes, newlines, and
     * stack traces.
     */
    /**
     * Resolves the declared secret names into values for one execution.
     *
     * <p><b>TENANT scope only, and that is a security decision rather than a limitation.</b> The
     * program run here was authored by someone in the tenant - typically an admin - but it may be
     * invoked by any member during their own chat turn. If USER-scoped secrets resolved here, that
     * author could write a tool which quietly returns the personal password of whoever ran it, and
     * every member who used the agent would hand it over. Passing a null user id means
     * findResolvableByName matches TENANT rows only, which forecloses that.
     *
     * <p><b>Redaction is NOT a containment boundary here.</b> The scrubbing applied to the result
     * and stderr below guards against a program ACCIDENTALLY echoing a value; it cannot stop one
     * that wants to leak it, because a literal match is defeated by any transformation the program
     * chooses (codepoints, hex, reversal). Containment is instead the per-secret opt-in that {@code
     * resolveForCodeTool} enforces: by default NO vault secret is reachable from sandboxed code, so
     * a grant is an explicit decision to disclose that one value to everyone who can author a tool
     * here. Do not reason from the redaction as though it were a control.
     *
     * <p>A name that does not resolve is skipped, not faked: the program sees no entry for it and
     * can fail honestly. Substituting an empty string would make a missing secret look like an
     * empty credential, which several upstreams treat as anonymous access.
     */
    private Map<String, String> resolveSecretsFor(CustomCodeTool tool) {
        Set<String> declared = tool.getAllowedSecretNames();
        if (declared == null || declared.isEmpty()) {
            return Map.of();
        }
        SecretVaultService vault = secretVaultService.getIfAvailable();
        if (vault == null) {
            log.warn(
                    "Tool {} declares secrets but no vault is configured - running without them",
                    tool.getToolName());
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String name : declared) {
            vault.resolveForCodeTool(name, tool.getToolName())
                    .ifPresent(value -> resolved.put(name, value));
        }
        if (resolved.size() < declared.size()) {
            log.warn(
                    "Tool {} declares {} secret(s) but only {} resolved - the rest are absent, expired or revoked",
                    tool.getToolName(),
                    declared.size(),
                    resolved.size());
        }
        return resolved;
    }

    /** Scrubs resolved secret values out of anything heading back to the model. */
    private String redact(String text, Map<String, String> secrets) {
        if (text == null || secrets.isEmpty()) {
            return text;
        }
        SecretRedactor redactor = secretRedactor.getIfAvailable();
        return redactor == null ? text : redactor.redact(text, secrets.values());
    }

    private static String errorJson(String message, String errorType, String stderr) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", message);
        if (StringUtils.hasText(errorType)) {
            node.put("errorType", errorType);
        }
        if (StringUtils.hasText(stderr)) {
            node.put("stderr", stderr);
        }
        return node.toString();
    }

    private static String sha256Hex(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE - unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
