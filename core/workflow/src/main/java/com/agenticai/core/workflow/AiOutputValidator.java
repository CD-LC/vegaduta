package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowAiNodeHandler.*;

import com.agenticai.core.chat.structured.StructuredOutput;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validation and type coercion of a parameter-extractor reply against the declared parameters
 * (string / number / boolean / array / enum). Strict: an unusable value becomes JSON null plus a
 * per-parameter problem, never a guess. Extracted from {@link WorkflowAiNodeHandler}; pure and
 * static.
 */
final class AiOutputValidator {
    private static final Pattern PLAIN_NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");
    private static final Pattern THOUSANDS = Pattern.compile("[+-]?\\d{1,3}(,\\d{3})+(\\.\\d+)?");

    private AiOutputValidator() {}

    /**
     * Validates and coerces {@code obj} against {@code params}; keys the model added beyond the
     * schema are ignored.
     */
    static Extraction validate(JsonNode obj, List<AiParam> params, ObjectMapper mapper) {
        JsonNodeFactory factory = mapper.getNodeFactory();
        ObjectNode values = mapper.createObjectNode();
        Map<String, String> problems = new LinkedHashMap<>();
        for (AiParam p : params) {
            Coerced c = coerce(p, obj.get(p.name()), factory, mapper);
            if (c.problem() != null) {
                problems.put(p.name(), c.problem());
            }
            values.set(p.name(), c.value() == null ? factory.nullNode() : c.value());
        }
        return new Extraction(values, problems);
    }

    static Coerced coerce(AiParam p, JsonNode v, JsonNodeFactory f, ObjectMapper mapper) {
        if (v == null || v.isMissingNode() || v.isNull()) {
            return Coerced.absent();
        }
        if (v.isString()) {
            String t = v.asString().strip();
            if (t.isEmpty() || t.equalsIgnoreCase("null")) {
                return Coerced.absent();
            }
        }
        switch (p.type()) {
            case STRING -> {
                if (v.isString()) {
                    return Coerced.of(f.textNode(v.asString().strip()));
                }
                if (v.isNumber() || v.isBoolean()) {
                    return Coerced.of(f.textNode(v.asString()));
                }
                return Coerced.bad("expected text");
            }
            case NUMBER -> {
                BigDecimal n = null;
                if (v.isNumber()) {
                    n = v.decimalValue();
                } else if (v.isString()) {
                    n = parseNumber(v.asString());
                }
                return n == null ? Coerced.bad("expected a number") : Coerced.of(numberNode(n, f));
            }
            case BOOLEAN -> {
                if (v.isBoolean()) {
                    return Coerced.of(v);
                }
                if (v.isString()) {
                    switch (v.asString().strip().toLowerCase(Locale.ROOT)) {
                        case "true", "yes", "y" -> {
                            return Coerced.of(f.booleanNode(true));
                        }
                        case "false", "no", "n" -> {
                            return Coerced.of(f.booleanNode(false));
                        }
                        default -> {
                            return Coerced.bad("expected true or false");
                        }
                    }
                }
                return Coerced.bad("expected true or false");
            }
            case ARRAY -> {
                JsonNode array = v;
                if (v.isString()) {
                    String t = v.asString().strip();
                    array =
                            t.startsWith("[")
                                    ? StructuredOutput.readArray(mapper, t).orElse(null)
                                    : null;
                }
                if (array == null || !array.isArray()) {
                    return Coerced.bad("expected an array");
                }
                ArrayNode out = f.arrayNode();
                for (JsonNode element : array) {
                    if (out.size() >= MAX_ARRAY_ITEMS) {
                        break;
                    }
                    if (element == null || element.isNull()) {
                        continue;
                    }
                    if (element.isString()) {
                        String s = element.asString().strip();
                        if (!s.isEmpty()) {
                            out.add(s);
                        }
                    } else if (element.isNumber() || element.isBoolean()) {
                        out.add(element);
                    } else {
                        return Coerced.bad("array items must be text or numbers");
                    }
                }
                return Coerced.of(out);
            }
            case ENUM -> {
                if (!v.isValueNode()) {
                    return Coerced.bad("expected one of the listed options");
                }
                String wanted = v.asString().strip();
                for (String option : p.options()) {
                    if (option.equalsIgnoreCase(wanted)) {
                        return Coerced.of(f.textNode(option));
                    }
                }
                return Coerced.bad("not one of: " + String.join(", ", p.options()));
            }
            default -> {
                return Coerced.bad("unsupported type");
            }
        }
    }

    private static BigDecimal parseNumber(String raw) {
        String t = raw.strip();
        if (t.length() > 40) {
            return null;
        }
        if (THOUSANDS.matcher(t).matches()) {
            t = t.replace(",", "");
        }
        // Plain decimal digits only: scientific notation ("1e999999999") would otherwise build a
        // BigDecimal whose
        // exact integer conversion below is unbounded.
        if (!PLAIN_NUMBER.matcher(t).matches()) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonNode numberNode(BigDecimal n, JsonNodeFactory f) {
        BigDecimal stripped = n.stripTrailingZeros();
        if (stripped.scale() <= 0 && stripped.precision() - stripped.scale() <= 30) {
            try {
                return f.numberNode(stripped.longValueExact());
            } catch (ArithmeticException e) {
                return f.numberNode(stripped.toBigIntegerExact());
            }
        }
        return f.numberNode(n);
    }
}
