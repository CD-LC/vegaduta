package com.agenticai.core.agents.tools.script;

import com.agenticai.core.json.JsonMappers;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The actual SpEL sandbox {@link UserToolScript}'s javadoc describes - shared by {@link
 * UserToolScriptCallback} (real tool calls) and {@link UserToolScriptService#test} (the frontend's
 * "Try it" dry run), so there is exactly one code path that decides what a tenant's expression is
 * allowed to do.
 *
 * <p><b>The sandbox, precisely:</b> {@link SimpleEvaluationContext} - by design (see its own class
 * javadoc), unlike {@code StandardEvaluationContext}, it never registers a method resolver,
 * constructor resolver, bean resolver, or type locator, so an expression cannot call a method,
 * instantiate an object, reach a Spring bean, or reference a Java type ({@code T(...)}) no matter
 * what property accessors are configured - that restriction is orthogonal to (and doesn't depend
 * on) the two accessors registered below. Combined with SpEL's grammar having no loop construct at
 * all (no {@code for}/{@code while}), an expression here cannot run forever, spawn a process, touch
 * the filesystem, or reach outside the single JSON object it's given - it can only compute a value
 * FROM that object using operators (arithmetic, comparison, ternary/elvis, string concatenation via
 * {@code +}), indexing ({@code ['key']}), safe navigation ({@code ?.}), and collection
 * selection/projection ({@code ?[...]}, {@code ![...]}).
 *
 * <p>{@link DataBindingPropertyAccessor#forReadOnlyAccess()} + {@link MapAccessor} together let an
 * expression read the parsed JSON input both ways SpEL users commonly expect - {@code
 * input['field']} (core indexer, works on any Map) and {@code input.field} (dot access, needs
 * MapAccessor since a {@code Map} isn't a JavaBean) - while still refusing every write
 * (forReadOnlyAccess) and every non-property operation (the context-level restriction above).
 */
@Component
public class UserToolScriptEvaluator {

    private static final Logger log = LoggerFactory.getLogger(UserToolScriptEvaluator.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    // autoGrowNullReferences/autoGrowCollections both left at their default
    // (false) - this parser configuration is otherwise plain; nothing here
    // widens the sandbox above, it only affects how missing intermediate
    // values are treated during parsing.
    private final ExpressionParser parser =
            new SpelExpressionParser(new SpelParserConfiguration(false, false));

    /**
     * Throws a {@link org.springframework.expression.ParseException} on invalid syntax - callers
     * decide how to surface that.
     */
    public void validateSyntax(String expression) {
        parser.parseExpression(expression);
    }

    /**
     * Evaluates {@code expression} against {@code inputJson} (parsed as a JSON object; an
     * empty/blank input evaluates against an empty map) and returns the result as a JSON string -
     * either {@code {"result": <value>}} on success or {@code {"error": "..."}} on any failure (bad
     * JSON input, a SpEL evaluation error, an expression that touches a field the input doesn't
     * have). Mirrors every other native {@code ToolCallback} in this codebase (e.g. {@code
     * GoogleDriveToolCallback}), which return a JSON error string rather than throwing, so a
     * mistake in a tenant's expression reads to the LLM as "the tool said X" instead of crashing
     * the whole tool call.
     */
    public String evaluate(String expression, String inputJson) {
        try {
            Map<String, Object> input = parseInput(inputJson);
            Expression exp = parser.parseExpression(expression);
            SimpleEvaluationContext context =
                    SimpleEvaluationContext.forPropertyAccessors(
                                    DataBindingPropertyAccessor.forReadOnlyAccess(),
                                    new MapAccessor())
                            .build();
            // Root object is passed here, per-evaluation, rather than baked into the context
            // (SimpleEvaluationContext.Builder has no withRootObject - the documented pattern is
            // Expression#getValue(EvaluationContext, Object)) - see the Spring reference docs'
            // own SimpleEvaluationContext example.
            Object result = exp.getValue(context, input);
            return MAPPER.writeValueAsString(Map.of("result", result == null ? "" : result));
        } catch (Exception e) {
            log.debug("User tool script expression failed: {}", expression, e);
            return safeErrorJson(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(String inputJson) throws Exception {
        if (inputJson == null || inputJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object parsed = MAPPER.readValue(inputJson, Object.class);
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        // A bare scalar/array input (not a JSON object) - wrap it under a
        // single "value" key so it's still reachable as input['value']
        // rather than failing outright just because the tenant sent
        // "5" instead of {"value": 5}.
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("value", parsed);
        return wrapped;
    }

    private String safeErrorJson(String message) {
        try {
            return MAPPER.writeValueAsString(
                    Map.of("error", message == null ? "Evaluation failed." : message));
        } catch (Exception e) {
            return "{\"error\": \"Evaluation failed.\"}";
        }
    }
}
