package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowAiNodeSupport.fence;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.intConfig;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.isEmptyValue;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.listOfStrings;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.oneLine;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.str;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.unitConfig;

import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.agents.tools.web.HttpRequestResult;
import com.agenticai.core.agents.tools.web.WebPageFetchException;
import com.agenticai.core.knowledge.InvalidKnowledgeUploadException;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.workflow.WorkflowDocumentText.DocumentExtractionException;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Dify-style AI-native workflow nodes, in one handler so {@link WorkflowNodeExecutor} needs
 * only one injected collaborator and one added {@code case} line per type:
 *
 * <ul>
 *   <li>{@code question_classifier} - a constrained, temperature-0 JSON model call that picks
 *       exactly ONE of the author's named classes and routes down the matching outgoing edge
 *       through the same {@code branch()} / edge-{@code sourceHandle} mechanism {@code condition}
 *       and {@code supervisor} use (the class {@code handle} is the edge's {@code sourceHandle};
 *       the engine is untouched). The reply is validated strictly: it must be a JSON object whose
 *       {@code class} is one of the configured handles. Anything else - a provider failure,
 *       unparseable text, an unknown class, a confidence below the threshold - routes to the
 *       configured default class; free text never reaches the router.
 *   <li>{@code parameter_extractor} - typed JSON extraction (string / number / boolean / array /
 *       enum) with strict validation, ONE repair retry on an unusable or ill-typed reply, and
 *       required-but-absent parameters listed under {@code _missing}. The prompt demands {@code
 *       null} for anything the text does not state; nothing is invented.
 *   <li>{@code variable_aggregator} - pure, no model: the first non-empty of N resolved source
 *       values. See the limitation note on {@link #executeVariableAggregator}.
 *   <li>{@code document_extractor} - an uploaded file reference / text to plain text through the
 *       knowledge module's existing extractors ({@link WorkflowDocumentText}), with size, page and
 *       row caps.
 * </ul>
 *
 * <p><b>Untrusted data.</b> The text being classified or extracted is DATA. It is wrapped in the
 * project's {@code <<<TEXT ... TEXT>>>} fence ({@link WorkflowAiNodeSupport#fence}, which breaks
 * any marker inside the data), the system prompt says so, and the model's output is only ever
 * parsed and validated - never executed, never used as a template, never as a branch name that was
 * not on the author's list.
 *
 * <p><b>Cost, budgets, plan gate.</b> Every model call goes through {@link WorkflowAiModel}
 * (production: {@link WorkflowAiModelClient}), i.e. the platform pool with its tenant token quota /
 * per-user budget for pool tenants and {@code LlmGatewayService} (gateway budget + rate limit +
 * metrics advisor) for BYOK tenants - the same accounting as the Agent node. Quota / budget
 * exceptions are re-thrown so the run stops visibly (the engine never retries them); the run's
 * {@code maxStepsPerRun} is charged by the engine per node as for every other node type. The
 * classifier makes one call; the extractor at most two (the repair).
 *
 * <p>Kill switch: {@code app.workflow.ai-nodes.enabled} (default true - the node types are inert
 * until a workflow uses one). Off: the classifier routes to its default class and the others report
 * SKIPPED, never calling a model.
 */
@Component
class WorkflowAiNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAiNodeHandler.class);

    static final int MAX_CLASSES = 20;
    static final int MAX_PARAMS = 30;
    static final int MAX_SOURCES = 20;
    static final int MAX_ENUM_OPTIONS = 50;
    static final int MAX_ARRAY_ITEMS = 100;
    static final int MAX_HANDLE_CHARS = 64;
    static final int CLASSIFIER_MAX_TOKENS = 256;
    static final int DEFAULT_EXTRACTOR_MAX_TOKENS = 1024;

    /** Reserved: the shared failure port every node has. A class may not use it as its handle. */
    static final String RESERVED_HANDLE = "error";

    private static final Set<String> DOC_OUTPUT_MODES = Set.of("text", "json");

    /** Registry keys of the node prompts (default text: {@code prompts/en/workflow.ai_*.md}). */
    static final String CLASSIFIER_KEY = "workflow.ai_classifier";

    static final String EXTRACTOR_KEY = "workflow.ai_extractor";
    static final String EXTRACTOR_REPAIR_KEY = "workflow.ai_extractor.repair";

    /**
     * The shipped wording of the two system prompts, for tests only: a node run resolves the keys
     * through the registry so a tenant override applies.
     */
    static final String CLASSIFIER_SYSTEM = PromptResolver.defaultsOnly().catalog().defaultText(CLASSIFIER_KEY);

    static final String EXTRACTOR_SYSTEM = PromptResolver.defaultsOnly().catalog().defaultText(EXTRACTOR_KEY);

    /** Shared with {@link AiClassifierHandler} and {@link AiExtractorHandler}, which this class builds. */
    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so a tenant override of the node prompts applies; absent = shipped text. */
    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    private final ObjectProvider<WorkflowAiModel> modelProvider;
    private final ObjectProvider<SidecarClient> sidecarProvider;
    private final ObjectProvider<FileStorageService> storageProvider;
    private final ObjectProvider<HttpRequestExecutor> httpProvider;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int maxInputChars;
    private final double defaultMinConfidence;
    private final int docMaxBytes;
    private final int docMaxChars;
    private final int docMaxPages;
    private final int docMaxRows;
    private final AiClassifierHandler classifier;
    private final AiExtractorHandler extractor;
    private final AiReferenceResolver resolver;

    @Autowired
    WorkflowAiNodeHandler(
            ObjectProvider<WorkflowAiModel> modelProvider,
            ObjectProvider<SidecarClient> sidecarProvider,
            ObjectProvider<FileStorageService> storageProvider,
            ObjectProvider<HttpRequestExecutor> httpProvider,
            ObjectMapper objectMapper,
            @Value("${app.workflow.ai-nodes.enabled:true}") boolean enabled,
            @Value("${app.workflow.ai-nodes.max-input-chars:12000}") int maxInputChars,
            @Value("${app.workflow.ai-nodes.min-confidence:0.5}") double defaultMinConfidence,
            @Value("${app.workflow.ai-nodes.document.max-bytes:10485760}") int docMaxBytes,
            @Value("${app.workflow.ai-nodes.document.max-chars:60000}") int docMaxChars,
            @Value("${app.workflow.ai-nodes.document.max-pages:50}") int docMaxPages,
            @Value("${app.workflow.ai-nodes.document.max-rows:2000}") int docMaxRows) {
        this.modelProvider = modelProvider;
        this.sidecarProvider = sidecarProvider;
        this.storageProvider = storageProvider;
        this.httpProvider = httpProvider;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.maxInputChars = Math.max(200, maxInputChars);
        this.defaultMinConfidence =
                defaultMinConfidence < 0.0 || defaultMinConfidence > 1.0
                        ? 0.5
                        : defaultMinConfidence;
        this.docMaxBytes = Math.max(1024, docMaxBytes);
        this.docMaxChars = Math.max(500, docMaxChars);
        this.docMaxPages = Math.max(1, docMaxPages);
        this.docMaxRows = Math.max(1, docMaxRows);
        this.classifier =
                new AiClassifierHandler(
                        modelProvider,
                        objectMapper,
                        enabled,
                        this.maxInputChars,
                        this.defaultMinConfidence);
        this.extractor =
                new AiExtractorHandler(modelProvider, objectMapper, enabled, this.maxInputChars);
        this.classifier.usePrompts(prompts);
        this.extractor.usePrompts(prompts);
        this.resolver =
                new AiReferenceResolver(storageProvider, httpProvider, objectMapper, this.docMaxBytes);
    }

    NodeExecutionResult executeQuestionClassifier(WorkflowNodeDto node, String input) {
        return classifier.execute(node, input);
    }

    NodeExecutionResult executeParameterExtractor(WorkflowNodeDto node, String input) {
        return extractor.execute(node, input);
    }

    static Classification classify(
            String rawReply,
            List<AiClass> classes,
            String defaultHandle,
            double minConfidence,
            ObjectMapper mapper) {
        return AiClassifierHandler.classify(rawReply, classes, defaultHandle, minConfidence, mapper);
    }

    static List<AiClass> parseClasses(Object raw, ObjectMapper mapper, List<String> problems) {
        return AiClassifierHandler.parseClasses(raw, mapper, problems);
    }

    static List<AiParam> parseParams(Object raw, ObjectMapper mapper, List<String> problems) {
        return AiExtractorHandler.parseParams(raw, mapper, problems);
    }

    static Extraction validate(JsonNode obj, List<AiParam> params, ObjectMapper mapper) {
        return AiOutputValidator.validate(obj, params, mapper);
    }

    static Coerced coerce(AiParam p, JsonNode v, JsonNodeFactory f, ObjectMapper mapper) {
        return AiOutputValidator.coerce(p, v, f, mapper);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Save-time validation (called from OrchestratorService#validateNodeConfig)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Config errors for one of the four AI node types, in the validator's message style. Empty =
     * valid.
     */
    static List<String> validateConfig(WorkflowNodeDto node, ObjectMapper mapper) {
        String type = node.type() == null ? "" : node.type().toLowerCase(Locale.ROOT);
        Map<String, Object> config = node.config();
        List<String> errors = new ArrayList<>();
        String label = node.label();
        switch (type) {
            case "question_classifier" -> {
                List<String> problems = new ArrayList<>();
                List<AiClass> classes = parseClasses(config.get("classes"), mapper, problems);
                String prefix = "Question Classifier node '" + label + "' ";
                for (String problem : problems) {
                    errors.add(prefix + problem);
                }
                if (classes.size() < 2) {
                    errors.add(prefix + "needs at least two classes, each with a distinct handle.");
                }
                String defaultClass = str(config.get("defaultClass"));
                if (!defaultClass.isEmpty()
                        && classes.stream()
                                .noneMatch(c -> c.handle().equalsIgnoreCase(defaultClass))) {
                    errors.add(
                            prefix
                                    + "has a default class '"
                                    + defaultClass
                                    + "' that is not one of its classes.");
                }
                Object min = config.get("minConfidence");
                if (min != null
                        && !min.toString().isBlank()
                        && unitConfig(config, "minConfidence", -1.0) < 0.0) {
                    errors.add(prefix + "has a minimum confidence outside 0 to 1.");
                }
            }
            case "parameter_extractor" -> {
                List<String> problems = new ArrayList<>();
                List<AiParam> params = parseParams(config.get("parameters"), mapper, problems);
                String prefix = "Parameter Extractor node '" + label + "' ";
                for (String problem : problems) {
                    errors.add(prefix + problem);
                }
                if (params.isEmpty()) {
                    errors.add(prefix + "needs at least one parameter to extract.");
                }
            }
            case "variable_aggregator" -> {
                List<String> sources = listOfStrings(config.get("sources"), mapper);
                String prefix = "Variable Aggregator node '" + label + "' ";
                if (sources.stream().allMatch(s -> s == null || s.isBlank())) {
                    errors.add(
                            prefix
                                    + "needs at least one source value (for example {{ $node[\"Branch A\"].reply }}).");
                }
                if (sources.size() > MAX_SOURCES) {
                    errors.add(prefix + "has more than " + MAX_SOURCES + " sources.");
                }
            }
            case "document_extractor" -> {
                String prefix = "Document Extractor node '" + label + "' ";
                try {
                    WorkflowDocumentText.parseFormat(str(config.get("format")));
                } catch (DocumentExtractionException e) {
                    errors.add(prefix + "has an " + e.getMessage() + ".");
                }
                String mode = str(config.get("outputMode"));
                if (!mode.isEmpty() && !DOC_OUTPUT_MODES.contains(mode.toLowerCase(Locale.ROOT))) {
                    errors.add(prefix + "has an unknown output mode (expected text or json).");
                }
            }
            default -> {
                // not one of ours
            }
        }
        return errors;
    }

    // ------------------------------------------------------------------------------------------------------------
    // question_classifier
    // ------------------------------------------------------------------------------------------------------------

    /**
     * One author-defined class; {@code handle} is the outgoing edge's sourceHandle and the value
     * the model must return.
     */
    record AiClass(String handle, String label, String description) {}

    /**
     * The outcome of one classification; {@code fellBack} means the default class was used and
     * {@code reason} says why.
     */
    record Classification(String handle, Double confidence, boolean fellBack, String reason) {}







    // ------------------------------------------------------------------------------------------------------------
    // parameter_extractor
    // ------------------------------------------------------------------------------------------------------------

    enum ParamType {
        STRING,
        NUMBER,
        BOOLEAN,
        ARRAY,
        ENUM;

        static ParamType of(String raw) {
            if (raw == null) {
                return null;
            }
            return switch (raw.strip().toLowerCase(Locale.ROOT)) {
                case "string" -> STRING;
                case "number" -> NUMBER;
                case "boolean" -> BOOLEAN;
                case "array" -> ARRAY;
                case "enum" -> ENUM;
                default -> null;
            };
        }
    }

    record AiParam(
            String name,
            ParamType type,
            String description,
            boolean required,
            List<String> options) {}

    /**
     * A coerced value (null = absent) or the reason the model's value was unusable for the declared
     * type.
     */
    record Coerced(JsonNode value, String problem) {
        static Coerced absent() {
            return new Coerced(null, null);
        }

        static Coerced of(JsonNode value) {
            return new Coerced(value, null);
        }

        static Coerced bad(String problem) {
            return new Coerced(null, problem);
        }
    }

    /**
     * Typed values per parameter (JSON null when absent or unusable) and the per-parameter
     * problems.
     */
    record Extraction(ObjectNode values, Map<String, String> problems) {}












    // ------------------------------------------------------------------------------------------------------------
    // variable_aggregator
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Pure, no model call: the first non-empty value among the node's {@code sources} (already
     * resolved by {@link ExpressionFields}, so each is typically {@code {{ $node["Label"].field }}}
     * reading an earlier node's output), in author order. "Empty" is blank text, {@code null},
     * {@code []}, {@code {}}, or an expression that did not resolve. When every source is empty the
     * node passes its own input through ({@code fallback: "input"}, the default) or outputs empty
     * text ({@code fallback: "empty"}).
     *
     * <p><b>Limitation, stated plainly:</b> this engine walks ONE path through the graph - a {@code
     * condition}, {@code supervisor} or {@code question_classifier} sends the run down a single
     * edge, and a node only receives the output of the node that ran immediately before it. So when
     * two branches rejoin at this node, its own input is already "whichever branch ran"; this
     * node's value is in reading outputs of nodes that ran EARLIER on the path (or in a parallel
     * fan-out) by label, and in picking between them in a stated order. A source naming a node that
     * did not run in this run resolves to empty and is skipped, which is exactly the "first one
     * that produced something" rule. It does not wait for, or merge, branches that execute
     * concurrently - use a parallel node's merged array for that.
     */
    NodeExecutionResult executeVariableAggregator(WorkflowNodeDto node, String input) {
        Map<String, Object> config = node.config();
        List<String> sources = listOfStrings(config.get("sources"), objectMapper);
        List<String> logLines = new ArrayList<>();
        int limit = Math.min(sources.size(), MAX_SOURCES);
        for (int i = 0; i < limit; i++) {
            String candidate = sources.get(i);
            if (isEmptyValue(candidate)) {
                logLines.add("Source #" + (i + 1) + " is empty.");
                continue;
            }
            String summary = "Used source #" + (i + 1) + " of " + limit + ".";
            logLines.add(summary);
            return NodeExecutionResult.ok(candidate, summary, null, logLines);
        }
        boolean useInput = !"empty".equalsIgnoreCase(str(config.get("fallback")));
        String summary =
                "All "
                        + limit
                        + " source(s) were empty - "
                        + (useInput ? "passed the input through." : "output is empty.");
        logLines.add(summary);
        return NodeExecutionResult.ok(useInput ? input : "", summary, null, logLines);
    }

    // ------------------------------------------------------------------------------------------------------------
    // document_extractor
    // ------------------------------------------------------------------------------------------------------------



    NodeExecutionResult executeDocumentExtractor(WorkflowNodeDto node, String input) {
        Map<String, Object> config = node.config();
        if (!enabled) {
            return NodeExecutionResult.skipped(
                    input, "AI nodes are disabled in this deployment - input passed through.");
        }
        List<String> logLines = new ArrayList<>();
        try {
            WorkflowDocumentText.Format hint =
                    WorkflowDocumentText.parseFormat(str(config.get("format")));
            WorkflowDocumentText.Limits limits =
                    new WorkflowDocumentText.Limits(
                            intConfig(config, "maxChars", docMaxChars, 500, docMaxChars),
                            intConfig(config, "maxPages", docMaxPages, 1, docMaxPages),
                            intConfig(config, "maxRows", docMaxRows, 1, docMaxRows));

            AiReferenceResolver.Reference ref = resolver.resolve(config, input);
            if (ref == null) {
                return NodeExecutionResult.skipped(
                        input,
                        "No document reference: set Source, or pass a URL / uploaded file key as the node input.");
            }
            String contentType = ref.contentType();
            byte[] bytes;
            switch (ref.kind()) {
                case STORAGE -> {
                    bytes = resolver.readStored(ref.value());
                    logLines.add("Read an uploaded file (" + bytes.length + " bytes).");
                }
                case URL -> {
                    HttpRequestResult fetched = resolver.fetchUrl(ref.value());
                    contentType = fetched.contentType();
                    bytes = fetched.body().getBytes(StandardCharsets.UTF_8);
                    logLines.add(
                            "Fetched "
                                    + oneLine(AiReferenceResolver.hostOf(ref.value()), 80)
                                    + " ("
                                    + bytes.length
                                    + " bytes of text"
                                    + (fetched.bodyTruncated() ? ", clipped by the fetch cap" : "")
                                    + ").");
                }
                default -> {
                    bytes = ref.value().getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > docMaxBytes) {
                        throw new DocumentExtractionException(
                                "the text is larger than the "
                                        + (docMaxBytes / 1024)
                                        + " KB limit");
                    }
                    logLines.add(
                            "Reading the node input as document text ("
                                    + bytes.length
                                    + " bytes).");
                }
            }

            WorkflowDocumentText.Format format =
                    hint != null
                            ? hint
                            : WorkflowDocumentText.detect(ref.fileName(), contentType, bytes);
            if (format == null) {
                throw new DocumentExtractionException(
                        "could not tell the document type - set Format (pdf, docx, xlsx, csv, txt, md or html)");
            }
            if (ref.kind() != AiReferenceResolver.RefKind.STORAGE
                    && (format == WorkflowDocumentText.Format.PDF
                            || format == WorkflowDocumentText.Format.DOCX
                            || format == WorkflowDocumentText.Format.XLSX)) {
                throw new DocumentExtractionException(
                        format.wire.toUpperCase(Locale.ROOT)
                                + " files must be uploaded and passed by their attachment key - only text-based pages and files can be read from a URL or from text");
            }
            SidecarClient sidecar = sidecarProvider.getIfAvailable();
            BiFunction<String, byte[], String> extractor =
                    sidecar == null ? null : sidecar::extractText;
            WorkflowDocumentText.Result result =
                    WorkflowDocumentText.extract(bytes, format, ref.fileName(), extractor, limits);

            String summary =
                    "Extracted "
                            + result.text().length()
                            + " chars from a "
                            + result.format().wire.toUpperCase(Locale.ROOT)
                            + (result.pages() >= 0 ? " (" + result.pages() + " page(s))" : "")
                            + (result.truncated() ? "; truncated to the configured limits." : ".");
            logLines.add(summary);
            String output = result.text();
            if ("json".equalsIgnoreCase(str(config.get("outputMode")))) {
                ObjectNode out = objectMapper.createObjectNode();
                out.put("text", result.text());
                out.put("format", result.format().wire);
                if (result.pages() >= 0) {
                    out.put("pages", result.pages());
                } else {
                    out.putNull("pages");
                }
                out.put("chars", result.text().length());
                out.put("truncated", result.truncated());
                out.put("source", ref.kind().name().toLowerCase(Locale.ROOT));
                output = out.toString();
            }
            return NodeExecutionResult.ok(output, summary, null, logLines);
        } catch (DocumentExtractionException e) {
            return NodeExecutionResult.skipped(
                    input, "Document not extracted: " + e.getMessage() + ".", logLines);
        } catch (InvalidKnowledgeUploadException e) {
            return NodeExecutionResult.skipped(
                    input, "Document not extracted: " + oneLine(e.getMessage(), 200), logLines);
        } catch (WebPageFetchException e) {
            return NodeExecutionResult.skipped(
                    input, "Document not extracted: " + oneLine(e.getMessage(), 200), logLines);
        } catch (RuntimeException e) {
            log.warn("Document extractor failed for node {}: {}", node.id(), e.toString());
            return NodeExecutionResult.skipped(
                    input,
                    "Document not extracted: the document service could not read this file right now.",
                    logLines);
        }
    }





}
