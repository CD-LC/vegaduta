package com.agenticai.core.prompts.tool;

import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptDefinition;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSource;
import com.agenticai.core.prompts.PromptTemplates;
import com.agenticai.core.prompts.ResolvedPrompt;
import com.agenticai.core.security.TenantContext;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The texts of tools for one agent: the lookups behind {@code tool.description.<id>}, {@code
 * tool.template.<id>} and {@code tool.param.<id>.<path>} (see {@link ToolPromptKeys}).
 *
 * <p>Most tools never use this class directly: the tool resolver applies {@code tool.description.*}
 * and {@code tool.param.*} to every built-in tool's finished definition ({@link #idFor}, {@link
 * #overriddenDescription}, {@link #schema}), recovering the values of a template from the text the
 * code built. A tool that renders its description <em>itself</em> from run time values (the
 * delegation tools: a roster, a hop counter, a collaborator's name) keeps its text in a {@code
 * tool.template.<id>} key, which that decorator never touches, and asks here when it is built:
 * {@link #description} with the values as placeholders, {@link #schema} for its parameter texts.
 * The tool resolver binds the agent being resolved around building the tool list ({@link
 * #within}), so {@link #current()} in a tool's constructor answers for that agent and tenant.
 * Outside such a scope {@code current()} serves the shipped texts only, which are the texts the
 * code used before the registry.
 *
 * <p>It never throws: a failed lookup gives the shipped text.
 */
public final class ToolPromptTexts {

    private static final Logger log = LoggerFactory.getLogger(ToolPromptTexts.class);

    private static final ThreadLocal<ToolPromptTexts> CURRENT = new ThreadLocal<>();

    private final PromptResolver resolver;
    private final UUID agentId;
    /** The tenant of the thread that created this object: lookups later, on other threads, still answer for it. */
    private final TenantContext tenant;

    private ToolPromptTexts(PromptResolver resolver, UUID agentId) {
        this.resolver = resolver;
        this.agentId = agentId;
        this.tenant = TenantContext.current().orElse(null);
    }

    /** Runs a lookup as the tenant this object was created for (a no-op on the creating thread). */
    private <T> T asTenant(Supplier<T> lookup) {
        if (tenant == null) {
            return lookup.get();
        }
        TenantContext prior = TenantContext.current().orElse(null);
        if (prior == tenant) {
            return lookup.get();
        }
        TenantContext.set(tenant);
        try {
            return lookup.get();
        } finally {
            if (prior == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(prior);
            }
        }
    }

    public static ToolPromptTexts of(PromptResolver resolver, UUID agentId) {
        return new ToolPromptTexts(resolver == null ? PromptResolver.defaultsOnly() : resolver, agentId);
    }

    /** The shipped texts only. */
    public static ToolPromptTexts defaults() {
        return new ToolPromptTexts(PromptResolver.defaultsOnly(), null);
    }

    /** The texts for the agent bound by the enclosing {@link #within}, else {@link #defaults()}. */
    public static ToolPromptTexts current() {
        ToolPromptTexts bound = CURRENT.get();
        return bound != null ? bound : defaults();
    }

    /** Runs {@code body} with {@code texts} as {@link #current()} on this thread, then restores the previous one. */
    public static <T> T within(ToolPromptTexts texts, Supplier<T> body) {
        ToolPromptTexts previous = CURRENT.get();
        CURRENT.set(texts);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public PromptResolver resolver() {
        return resolver;
    }

    public UUID agentId() {
        return agentId;
    }

    /**
     * The id under which a tool is registered: the id of the variant whose shipped description is
     * what the code just built (equal to it, or, for a template, the template filled in), else the
     * plain tool name's id; null when the catalog has no keys for {@code toolName}.
     */
    public String idFor(String toolName, String builtDescription) {
        PromptCatalog catalog = resolver.catalog();
        ToolPromptIndex index = ToolPromptIndex.of(catalog);
        List<String> ids = index.idsByName.get(toolName);
        if (ids == null) {
            return null;
        }
        if (ids.size() > 1) {
            for (String id : ids) {
                String key = ToolPromptKeys.description(id);
                ToolPromptIndex.Template template = index.template(catalog, key);
                boolean matches =
                        template != null
                                ? template.match(builtDescription) != null
                                : catalog.defaultText(key, catalog.defaultLocale())
                                        .map(shipped -> shipped.equals(builtDescription))
                                        .orElse(false);
                if (matches) {
                    return id;
                }
            }
        }
        return ids.get(0);
    }

    /**
     * The registry's wording of tool {@code id}'s description when an override of it is in force
     * for this agent; null when the shipped text applies or the override does not fit.
     *
     * <p>For a key without placeholders that is simply the override. For a template (the code fills
     * run time values into the text) the values are recovered from {@code builtDescription}, the
     * shipped template filled in by the code, and the override is filled in with them; if the built
     * text is not the shipped template (the tool rendered its own override already, or its wording
     * drifted) nothing is changed.
     */
    public String overriddenDescription(String id, String builtDescription) {
        try {
            PromptCatalog catalog = resolver.catalog();
            String key = ToolPromptKeys.description(id);
            PromptDefinition def = catalog.find(key).orElse(null);
            if (def == null) {
                return null;
            }
            PromptContext context = PromptContext.forAgent(agentId);
            if (!def.placeholders().isEmpty()) {
                ToolPromptIndex.Template template = ToolPromptIndex.of(catalog).template(catalog, key);
                Map<String, String> values = template == null ? null : template.match(builtDescription);
                if (values == null) {
                    return null;
                }
                context = context.withVariables(values);
            }
            PromptContext finalContext = context;
            ResolvedPrompt resolved = asTenant(() -> resolver.resolve(key, finalContext));
            return overridden(resolved) ? resolved.text() : null;
        } catch (RuntimeException e) {
            log.warn("Tool description '{}' could not be resolved; using the built-in wording: {}", id, e.toString());
            return null;
        }
    }

    /**
     * The description of tool {@code id} for this agent with its placeholders filled from {@code
     * variables}: the override in force, else the shipped text. Never null for a registered id.
     */
    public String description(String id, Map<String, String> variables) {
        String key = ToolPromptKeys.template(id);
        try {
            ResolvedPrompt resolved =
                    asTenant(() -> resolver.resolve(key, PromptContext.forAgent(agentId).withVariables(variables)));
            if (resolved.active()) {
                return resolved.text();
            }
        } catch (RuntimeException e) {
            log.warn("Tool description '{}' could not be resolved; using the shipped text: {}", id, e.toString());
        }
        return shipped(key, variables);
    }

    /**
     * {@code builtSchema} with the parameter descriptions that have an override in force for this
     * agent replaced; the same string when none has, or when applying them would change anything
     * but descriptions.
     */
    public String schema(String id, String builtSchema) {
        if (builtSchema == null) {
            return null;
        }
        try {
            ToolPromptIndex index = ToolPromptIndex.of(resolver.catalog());
            String owner = index.paramOwner(id);
            List<String> paramKeys = index.paramKeysById.get(owner);
            if (paramKeys == null) {
                return builtSchema;
            }
            PromptContext context = PromptContext.forAgent(agentId);
            Map<String, String> overrides = new HashMap<>();
            for (String key : paramKeys) {
                ResolvedPrompt resolved = asTenant(() -> resolver.resolve(key, context));
                if (overridden(resolved)) {
                    overrides.put(key, resolved.text());
                }
            }
            if (overrides.isEmpty()) {
                return builtSchema;
            }
            Map<String, String> byPath = new LinkedHashMap<>();
            Map<String, Integer> seen = new HashMap<>();
            for (ToolPromptSchemas.Param p : ToolPromptSchemas.params(builtSchema)) {
                String key = ToolPromptKeys.param(owner, p.path());
                seen.merge(key, 1, Integer::sum);
                if (overrides.containsKey(key)) {
                    byPath.put(p.path(), overrides.get(key));
                }
            }
            // Two parameters that share a key cannot be told apart: leave both as the code wrote them.
            byPath.keySet().removeIf(path -> seen.get(ToolPromptKeys.param(owner, path)) > 1);
            String rewritten = ToolPromptSchemas.withDescriptions(builtSchema, byPath);
            if (!ToolPromptSchemas.sameShape(builtSchema, rewritten)) {
                log.error("Overrides of the parameter texts of tool '{}' would change its schema; ignored", id);
                return builtSchema;
            }
            return Objects.requireNonNull(rewritten);
        } catch (RuntimeException e) {
            log.warn("Parameter texts of tool '{}' could not be resolved; using the built-in wording: {}", id, e.toString());
            return builtSchema;
        }
    }

    private String shipped(String key, Map<String, String> variables) {
        PromptCatalog catalog = resolver.catalog();
        PromptDefinition def = catalog.find(key).orElse(null);
        String text = catalog.defaultText(key, catalog.defaultLocale()).orElse("");
        return def == null ? text : PromptTemplates.render(text, def.placeholderNames(), variables);
    }

    private static boolean overridden(ResolvedPrompt resolved) {
        return resolved.active()
                && (resolved.source() == PromptSource.PLATFORM
                        || resolved.source() == PromptSource.TENANT
                        || resolved.source() == PromptSource.AGENT);
    }
}
