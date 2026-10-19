package com.agenticai.core.agents.tools.e2b;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.sdlc.E2bAccess;
import com.agenticai.core.sdlc.E2bAccessResolver;
import com.agenticai.core.sdlc.E2bSandboxCreateException;
import com.agenticai.core.sdlc.SdlcSandboxService;
import com.agenticai.core.sdlc.dto.RunCodeRequest;
import com.agenticai.core.sdlc.dto.RunCodeResponse;
import com.agenticai.core.sdlc.dto.SandboxArtifact;
import com.agenticai.core.sdlc.dto.SandboxFile;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Master Roadmap 2026 item #14 fast-follow (#1071 - "E2B: agent tool-callback + frontend UI").
 * Exposes {@link SdlcSandboxService#runCode} as a native Spring AI tool - the piece {@link
 * SdlcSandboxService}'s own javadoc named as "not built in this scaffold pass" when #849 shipped
 * the credential storage + manual REST surface. Same "plain in-process ToolCallback, not an MCP
 * server" choice every other native tool in this package makes (see {@code
 * SarvamTranslateToolCallback}'s javadoc for the shared reasoning).
 *
 * <p>Deliberately thin: every real precondition (active E2B credential, configured code-execution
 * template) is already enforced by {@link SdlcSandboxService#runCode} itself via {@code
 * E2bNotConfiguredException} - this callback's only job is the ToolCallback shape + turning that
 * exception into the same {@code {"error": "..."}} JSON convention every other tool callback in
 * this codebase uses, not re-deciding whether the tool should be offered at all (that's {@code
 * AgentToolProvider#resolveE2bTools}'s job, one layer up).
 *
 * <p>Runs the tenant's own code in the tenant's own BYOK-metered sandbox - the same "spend the
 * tenant's quota, not the platform's" posture every other BYOK integration (Sarvam, Google,
 * ElevenLabs) already has. stdout/ stderr are returned verbatim (capped by the sandbox template's
 * own output limits, not this callback) so a model can read compiler/runtime errors and
 * self-correct on a follow-up call, same "give the model the real error, not a summary" posture
 * {@code UserToolScriptCallback} already established for SpEL scripts.
 */
public class E2bRunCodeToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(E2bRunCodeToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private static final int MAX_STORED_ARTIFACTS = 8;

    private final SdlcSandboxService sandboxService;
    // Data-analysis sandbox (gap-analysis item 9). All three null = the classic stateless run_code.
    private final SandboxFileRegistry fileRegistry;
    private final FileStorageService fileStorage;
    private final UUID agentId;
    private final String sessionId;
    private final SandboxArtifactRetention retention;
    // Platform-managed E2B fallback (D1). Null resolver = classic tenant-key-only run_code.
    private final E2bAccessResolver accessResolver;
    private final PlatformSandboxRunQuota platformQuota;

    public E2bRunCodeToolCallback(SdlcSandboxService sandboxService) {
        this(sandboxService, null, null, null, null);
    }

    public E2bRunCodeToolCallback(
            SdlcSandboxService sandboxService,
            SandboxFileRegistry fileRegistry,
            FileStorageService fileStorage,
            UUID agentId,
            String sessionId) {
        this(sandboxService, fileRegistry, fileStorage, null, agentId, sessionId);
    }

    /**
     * @param fileRegistry where this session's chat-uploaded data files live (mounted into the
     *     sandbox working dir on every call); null disables mounting
     * @param fileStorage where output files (charts, xlsx, scripts) are persisted for download;
     *     null disables artifact return
     */
    public E2bRunCodeToolCallback(
            SdlcSandboxService sandboxService,
            SandboxFileRegistry fileRegistry,
            FileStorageService fileStorage,
            SandboxArtifactRetention retention,
            UUID agentId,
            String sessionId) {
        this(sandboxService, fileRegistry, fileStorage, retention, agentId, sessionId, null, null);
    }

    /**
     * @param accessResolver decides per call whether the run uses the tenant's own E2B key or the
     *     platform-managed fallback; null = tenant key only (the pre-fallback behaviour)
     * @param platformQuota meters runs on the platform key; a platform run without it is refused
     */
    public E2bRunCodeToolCallback(
            SdlcSandboxService sandboxService,
            SandboxFileRegistry fileRegistry,
            FileStorageService fileStorage,
            SandboxArtifactRetention retention,
            UUID agentId,
            String sessionId,
            E2bAccessResolver accessResolver,
            PlatformSandboxRunQuota platformQuota) {
        this.accessResolver = accessResolver;
        this.platformQuota = platformQuota;
        this.retention = retention;
        this.sandboxService = sandboxService;
        // run_code only exists for an agent with Code execution attached, hence availableFor(true):
        // flag +
        // tenant E2B key/template, the same predicate as upload and chat mounting.
        this.fileRegistry =
                fileRegistry != null && fileRegistry.availableFor(true) ? fileRegistry : null;
        this.fileStorage = this.fileRegistry == null ? null : fileStorage;
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String description =
                "Runs a short snippet of code in a fresh, disposable sandbox and returns stdout/stderr/exit code. "
                        + "Use this to verify logic, run a calculation, test a regex, or check a script actually works "
                        + "before telling the user it does - do not use this for anything that needs to persist state "
                        + "across calls (each call gets a brand-new sandbox) or that touches the tenant's real systems. "
                        + "language must be one of 'python', 'node', or 'bash'.";
        if (fileRegistry != null) {
            description += dataAnalysisNote();
        }
        return ToolDefinition.builder()
                .name("run_code")
                .description(description)
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "code": {"type": "string", "description": "The source code to run"},
                        "language": {"type": "string", "enum": ["python", "node", "bash"], "description": "Which interpreter to run it with"}
                    },
                    "required": ["code", "language"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String code = asString(args.get("code"));
            String language = asString(args.get("language"));
            if (code == null || code.isBlank() || language == null || language.isBlank()) {
                return errorJson("code and language are required");
            }
            List<SandboxFile> mounted =
                    fileRegistry == null ? List.of() : fileRegistry.filesFor(agentId, sessionId);
            RunCodeResponse result;
            if (accessResolver == null) {
                result =
                        sandboxService.runCode(
                                new RunCodeRequest(
                                        code, language, null, mounted.isEmpty() ? null : mounted));
            } else {
                E2bAccess access = accessResolver.resolveForCodeExecution();
                Integer timeoutSeconds = null;
                boolean metered = access.platformManaged();
                if (metered) {
                    // Platform spend: count the run and force the wall-clock ceiling BEFORE
                    // anything boots.
                    if (platformQuota == null) {
                        return errorJson("Code execution is not available yet for this workspace.");
                    }
                    String denied = platformQuota.tryConsume();
                    if (denied != null) {
                        return errorJson(denied);
                    }
                    timeoutSeconds = platformQuota.maxTimeoutSeconds();
                }
                try {
                    result =
                            sandboxService.runCode(
                                    access,
                                    new RunCodeRequest(
                                            code,
                                            language,
                                            timeoutSeconds,
                                            mounted.isEmpty() ? null : mounted));
                } catch (E2bSandboxCreateException e) {
                    // Refund ONLY when no sandbox was ever created. Any failure after creation
                    // (runner
                    // killed or unreachable, runner error status) has already cost platform sandbox
                    // time
                    // and can be forced by the submitted code, so it stays counted.
                    if (metered) {
                        platformQuota.refund();
                    }
                    throw e;
                }
            }
            Map<String, Object> response = new HashMap<>();
            response.put("stdout", result.stdout() == null ? "" : result.stdout());
            response.put("stderr", result.stderr() == null ? "" : result.stderr());
            response.put("exitCode", result.exitCode());
            response.put("timedOut", result.timedOut());
            if (!mounted.isEmpty()) {
                response.put("mountedFiles", mounted.stream().map(SandboxFile::name).toList());
            }
            // Files the user attached that could not be mounted (too big, over a cap, unreadable)
            // are reported here so neither the model nor the user is left guessing.
            List<String> warnings =
                    fileRegistry == null
                            ? List.of()
                            : fileRegistry.drainWarnings(agentId, sessionId);
            if (!warnings.isEmpty()) {
                response.put("warnings", warnings);
            }
            List<Map<String, Object>> files = storeArtifacts(result.artifacts());
            if (!files.isEmpty()) {
                response.put("files", files);
                response.put(
                        "instructions",
                        "To give the user these files you MUST put each url on its own line "
                                + "in your reply, verbatim - the chat renders images inline and the rest as downloadable "
                                + "files. Do not invent any other file link.");
            }
            return MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("run_code tool call failed", e);
            return errorJson("run_code call failed: " + e.getMessage());
        }
    }

    /**
     * Static on purpose: tool descriptions are built while tools resolve, BEFORE this turn's
     * uploads are registered, so a file list here would be stale on the first turn. The actual
     * names come back in each result's {@code mountedFiles}. Only present when the data-analysis
     * sandbox is enabled (opt-in; the E2B template must have been rebuilt first).
     */
    private static String dataAnalysisNote() {
        return " DATA ANALYSIS: files the user attached (CSV/XLSX/JSON/PDF/TXT) are placed in the working"
                + " directory; the result's mountedFiles lists their names (empty = nothing attached). Any chart"
                + " (.png/.svg), .xlsx/.csv or script (.py) you SAVE in the working directory comes back as a download"
                + " url in the result's files."
                + " Python has pandas, matplotlib, openpyxl, xlsxwriter and pypdf; use the matplotlib Agg backend"
                + " and plt.savefig (never plt.show).";
    }

    /**
     * Persists the run's output files and returns {filename, mimeType, url, bytes} per stored file.
     */
    private List<Map<String, Object>> storeArtifacts(List<SandboxArtifact> artifacts) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (fileStorage == null || artifacts == null) {
            return out;
        }
        for (SandboxArtifact artifact : artifacts) {
            if (out.size() >= MAX_STORED_ARTIFACTS) {
                break;
            }
            String ext = SandboxArtifacts.allowedExtension(artifact.name());
            if (ext == null || artifact.content() == null || artifact.content().length == 0) {
                continue;
            }
            try {
                UUID id = UUID.randomUUID();
                String mime = SandboxArtifacts.mimeFor(ext);
                fileStorage.put(SandboxArtifacts.dataKey(id, ext), artifact.content(), mime);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("name", artifact.name());
                meta.put("ext", ext);
                meta.put("agentId", agentId == null ? null : agentId.toString());
                // Uploader scope: downloads are limited to this user when one is authenticated; an
                // artifact without userId (no subject at write time) falls back to the agent-owner
                // check.
                String subject =
                        TenantContext.current()
                                .map(TenantContext::getSubject)
                                .filter(sub -> sub != null && !sub.isBlank())
                                .orElse(null);
                if (subject != null) {
                    meta.put("userId", subject);
                }
                fileStorage.put(
                        SandboxArtifacts.metaKey(id),
                        MAPPER.writeValueAsBytes(meta),
                        "application/json");
                if (retention != null) {
                    retention.track(
                            TenantContext.current().map(TenantContext::getTenantId).orElse(null),
                            id,
                            ext);
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("filename", artifact.name());
                entry.put("mimeType", mime);
                entry.put("url", SandboxArtifacts.urlFor(id));
                entry.put("bytes", artifact.content().length);
                out.add(entry);
            } catch (Exception e) {
                log.warn(
                        "Could not persist sandbox artifact '{}': {}",
                        artifact.name(),
                        e.getMessage());
            }
        }
        return out;
    }

    // Same bug class fixed in KnowledgeSearchToolCallback/SarvamTranslateToolCallback -
    // hand-built "{\"error\": \"...\"}" strings break if the exception message
    // contains a raw '"'.
    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
