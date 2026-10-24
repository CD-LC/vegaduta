package com.agenticai.core.prompts.tool;

import com.agenticai.core.prompts.PromptKeys;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The key convention for tool descriptions. A tool is registered under an <b>id</b>, normally its
 * model-facing name ({@code generate_document}); a tool whose name is shared by two
 * implementations with different wording gets a variant id {@code <name>.<variant>} for the
 * second one ({@code http_request.safe}).
 *
 * <ul>
 *   <li>{@code tool.description.<id>}: the description of the tool in the tool list
 *   <li>{@code tool.template.<id>}: the description of a tool that renders it itself from run
 *       time values (see {@link #template}); the resolver's decorator never touches these
 *   <li>{@code tool.param.<id>.<path>}: the description of one parameter of the tool's JSON schema.
 *       {@code path} is the property names from the schema root, joined by dots; an array's {@code
 *       items} level is skipped ({@code tasks.agent} is the {@code agent} property of the objects
 *       in {@code tasks}); every segment is written in lower snake case ({@link #segment}).
 * </ul>
 *
 * An override can only ever change these texts: the decorator that applies them never touches the
 * tool name, the schema's types, required list or any other keyword.
 */
public final class ToolPromptKeys {

    public static final String DESCRIPTION_PREFIX = "tool.description.";
    public static final String PARAM_PREFIX = "tool.param.";
    public static final String TEMPLATE_PREFIX = "tool.template.";

    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern NOT_KEY_CHAR = Pattern.compile("[^a-z0-9_-]");
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]*(\\.[a-z0-9][a-z0-9_-]*)?");

    private ToolPromptKeys() {}

    /** True for an id that can sit inside a key: a tool name, optionally with one {@code .variant}. */
    public static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches() && PromptKeys.isValid(DESCRIPTION_PREFIX + id);
    }

    public static String description(String id) {
        return DESCRIPTION_PREFIX + id;
    }

    /**
     * {@code tool.template.<id>}: the description of a tool that fills run time values into the
     * text and applies the registry itself, in its own constructor ({@link ToolPromptTexts#description}),
     * instead of leaving it to the tool resolver's decorator.
     */
    public static String template(String id) {
        return TEMPLATE_PREFIX + id;
    }

    /** The key of the parameter at {@code rawPath} (property names joined by dots, as in the schema). */
    public static String param(String id, String rawPath) {
        StringBuilder out = new StringBuilder(PARAM_PREFIX).append(id);
        for (String part : rawPath.split("\\.")) {
            out.append('.').append(segment(part));
        }
        return out.toString();
    }

    /**
     * One key segment from a schema property name: camelCase becomes snake_case, upper case becomes
     * lower case, any other character outside {@code a-z 0-9 _ -} becomes an underscore, and a
     * segment that would not start with a letter or digit gets a {@code p} in front.
     */
    public static String segment(String raw) {
        String s = CAMEL_BOUNDARY.matcher(raw == null ? "" : raw).replaceAll("$1_$2").toLowerCase(Locale.ROOT);
        s = NOT_KEY_CHAR.matcher(s).replaceAll("_");
        if (s.isEmpty() || !Character.isLetterOrDigit(s.charAt(0))) {
            s = "p" + s;
        }
        return s;
    }

    /** The tool name part of an id ({@code http_request.safe} gives {@code http_request}). */
    public static String toolName(String id) {
        int dot = id.indexOf('.');
        return dot < 0 ? id : id.substring(0, dot);
    }
}
