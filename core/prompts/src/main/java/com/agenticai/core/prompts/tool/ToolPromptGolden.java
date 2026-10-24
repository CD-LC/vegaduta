package com.agenticai.core.prompts.tool;

import com.agenticai.core.prompts.PromptCatalog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.objenesis.ObjenesisStd;

/**
 * Support for the golden test of tool descriptions, used by the tests of every module that ships
 * tools. Not used at run time.
 *
 * <p>The rule it checks is the migration rule of the prompt registry: the text a tool's code builds
 * today and the shipped default of its registry keys are the same string, byte for byte. {@link
 * #probe} reads the definition a tool class really builds (without running its constructor, so a
 * tool that needs a repository or a client can still be probed; a field that the definition reads
 * is set from {@code fields}); {@link #problems} compares it with the catalog.
 */
public final class ToolPromptGolden {

    private static final ObjenesisStd OBJENESIS = new ObjenesisStd();

    private ToolPromptGolden() {}

    /** What a tool tells the model. */
    public record Probed(String name, String description, String inputSchema) {}

    /**
     * Builds an instance of {@code toolClass} without a constructor, sets the named fields (looked
     * up in the class and its superclasses) and returns the tool definition it builds.
     */
    public static Probed probe(Class<?> toolClass, Map<String, Object> fields) {
        try {
            Object instance = OBJENESIS.newInstance(toolClass);
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                Field f = field(toolClass, e.getKey());
                f.setAccessible(true);
                f.set(instance, e.getValue());
            }
            Method get = toolClass.getMethod("getToolDefinition");
            get.setAccessible(true);
            Object definition = get.invoke(instance);
            return new Probed(
                    (String) call(definition, "name"),
                    (String) call(definition, "description"),
                    (String) call(definition, "inputSchema"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not probe " + toolClass.getName() + ": " + root(e), e);
        }
    }

    public static Probed probe(Class<?> toolClass) {
        return probe(toolClass, Map.of());
    }

    /** As {@link #problems(PromptCatalog, String, String, String, Map)} for a description without placeholders. */
    public static List<String> problems(PromptCatalog catalog, String id, String description, String schema) {
        return problems(catalog, id, description, schema, Map.of());
    }

    /**
     * Why the registry does not match the tool; empty when it does. For tool id {@code id}: the
     * description key exists and its default, with the placeholders filled in from {@code variables},
     * equals {@code description}; every described parameter has a key with the same default; no
     * {@code tool.param.<id>.*} key is left without a parameter; no two parameters share a key.
     * Parameter keys are those of {@code id}, or of the plain tool name for a variant that ships
     * none of its own.
     */
    public static List<String> problems(
            PromptCatalog catalog, String id, String description, String schema, Map<String, String> variables) {
        List<String> problems = new ArrayList<>();
        if (!ToolPromptKeys.isValidId(id)) {
            problems.add(id + ": not a valid tool id");
            return problems;
        }
        String descriptionKey =
                catalog.find(ToolPromptKeys.template(id)).isPresent()
                        ? ToolPromptKeys.template(id)
                        : ToolPromptKeys.description(id);
        var definition = catalog.find(descriptionKey);
        Optional<String> shipped = catalog.defaultText(descriptionKey, catalog.defaultLocale());
        if (definition.isEmpty() || shipped.isEmpty()) {
            problems.add(descriptionKey + ": not registered");
        } else {
            String rendered =
                    com.agenticai.core.prompts.PromptTemplates.render(
                            shipped.get(), definition.get().placeholderNames(), variables);
            if (!rendered.equals(description)) {
                problems.add(descriptionKey + ": default differs from the tool's description\n  code:     " + description + "\n  registry: " + rendered);
            }
            for (String token : com.agenticai.core.prompts.PromptTemplates.tokens(rendered)) {
                if (definition.get().placeholderNames().contains(token)) {
                    problems.add(descriptionKey + ": placeholder {" + token + "} was not filled by the variables given");
                }
            }
        }
        String owner = ToolPromptIndex.of(catalog).paramOwner(id);
        Map<String, String> keyToPath = new HashMap<>();
        for (ToolPromptSchemas.Param p : ToolPromptSchemas.params(schema)) {
            String key = ToolPromptKeys.param(owner, p.path());
            String clash = keyToPath.put(key, p.path());
            if (clash != null) {
                problems.add(key + ": parameters '" + clash + "' and '" + p.path() + "' map to the same key");
                continue;
            }
            Optional<String> text = catalog.defaultText(key, catalog.defaultLocale());
            if (catalog.find(key).isEmpty() || text.isEmpty()) {
                problems.add(key + ": not registered (parameter '" + p.path() + "')");
            } else if (!text.get().equals(p.description())) {
                problems.add(key + ": default differs from the parameter description\n  code:     " + p.description() + "\n  registry: " + text.get());
            }
        }
        String paramPrefix = ToolPromptKeys.PARAM_PREFIX + owner + ".";
        String name = ToolPromptKeys.toolName(owner);
        for (var def : catalog.all()) {
            String key = def.key();
            if (key.startsWith(paramPrefix) && !keyToPath.containsKey(key) && !belongsToVariant(catalog, name, owner, key)) {
                problems.add(key + ": registered, but the tool has no such parameter");
            }
        }
        return problems;
    }

    /** True when {@code key} is a param key of another variant id of the same tool name. */
    private static boolean belongsToVariant(PromptCatalog catalog, String name, String id, String key) {
        for (var def : catalog.all()) {
            String k = def.key();
            if (k.startsWith(ToolPromptKeys.DESCRIPTION_PREFIX + name + ".")) {
                String variantId = k.substring(ToolPromptKeys.DESCRIPTION_PREFIX.length());
                if (!variantId.equals(id) && key.startsWith(ToolPromptKeys.PARAM_PREFIX + variantId + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (!Modifier.isStatic(f.getModifiers())) {
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static String root(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null) {
            r = r.getCause();
        }
        return r.toString();
    }
}
