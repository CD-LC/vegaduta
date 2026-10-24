package com.agenticai.core.guardrails;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.tools.output.*}: how much of ONE tool result reaches the model, and what it is told when
 * the rest was cut. One cap for every tool, built-in or MCP, applied by {@link GuardedToolCallback}
 * (and, for a caller that bypasses it, by {@link PatternBasedGuardrailService}); {@link #getPerTool()}
 * raises or lowers it for a tool by name.
 *
 * <p>A result over the cap is TRUNCATED, never discarded: the head is kept and a marker says how much
 * was cut, so the model can ask for a narrower slice. The cap includes the marker, so the result is
 * never longer than the cap and cutting an already-cut result changes nothing.
 *
 * <p>Other limits on the way to the model are not this one and are documented next to it in {@code
 * docs/configuration.md}: a tool may stop earlier on its own (a search that returns the first N hits,
 * an OpenAPI call clipped at 16,000 characters), the MCP client keeps an unconditional 64 KB ceiling on
 * what it will even hold from an untrusted server, and the stores that persist a result for replay
 * (step journal, approval record) have their own storage caps. This cap is the one the model sees.
 *
 * <p>The marker is model-facing text, so it is configuration rather than a Java literal; the default
 * is read from {@code /guardrails/tool-output.properties} (the same text is in {@code
 * application.yml}). Registered by {@link ToolOutputConfiguration}.
 */
@ConfigurationProperties(prefix = "app.tools.output")
public class ToolOutputProperties {

    private static final String DEFAULTS_RESOURCE = "/guardrails/tool-output.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** The most characters one tool result may hand to the model, truncation marker included. */
    private int maxChars = Integer.parseInt(DEFAULTS.getProperty("max-chars"));

    /**
     * Tool name to its own cap, in place of {@link #getMaxChars()}. A tool that pages its own output
     * (a file read with an offset) can be given more room; a chatty one less. Values of zero or less
     * are ignored.
     */
    private Map<String, Integer> perTool = new LinkedHashMap<>();

    /** Appended to a cut result; {@code {total}}, {@code {dropped}} and {@code {limit}} are filled in. */
    private String truncationMarker = DEFAULTS.getProperty("truncation-marker");

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = ToolOutputProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the guardrails module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    /**
     * This configuration with a different default cap: the agent's own setting. The per-tool entries, the
     * marker and everything else are shared, so a tool the operator gave its own cap keeps it; the new
     * value replaces only the default that every other tool gets. Returns this when {@code maxChars}
     * is the current default (or not positive).
     */
    public ToolOutputProperties withMaxChars(int maxChars) {
        if (maxChars <= 0 || maxChars == this.maxChars) {
            return this;
        }
        ToolOutputProperties copy = new ToolOutputProperties();
        copy.maxChars = maxChars;
        copy.perTool = perTool;
        copy.truncationMarker = truncationMarker;
        return copy;
    }

    /** The cap that applies to {@code toolName}: its own entry in {@link #getPerTool()}, else the default. */
    public int limitFor(String toolName) {
        Integer own = toolName == null ? null : perTool.get(toolName);
        return own != null && own > 0 ? own : maxChars;
    }

    /**
     * {@code output} as the model may see it: unchanged when it fits the cap for {@code toolName},
     * otherwise its head followed by the truncation marker, in total no longer than the cap.
     */
    public String limit(String toolName, String output) {
        if (output == null) {
            return null;
        }
        int limit = limitFor(toolName);
        int total = output.length();
        if (total <= limit) {
            return output;
        }
        // The marker prints how much was cut, and how much was cut depends on how long the marker is:
        // settle on the longest head that fits, which takes a pass or two.
        int head = Math.max(0, limit - render(total, total, limit).length());
        String marker = render(total, total - head, limit);
        for (int pass = 0; pass < 3; pass++) {
            int fit = Math.max(0, limit - marker.length());
            if (fit == head) {
                break;
            }
            head = fit;
            marker = render(total, total - head, limit);
        }
        if (head + marker.length() > limit) {
            head = Math.max(0, limit - marker.length());
            marker = render(total, total - head, limit);
        }
        if (head > 0 && Character.isHighSurrogate(output.charAt(head - 1))) {
            head--;
        }
        String cut = output.substring(0, head) + marker;
        return cut.length() <= limit ? cut : cut.substring(0, limit);
    }

    private String render(int total, int dropped, int limit) {
        String template = truncationMarker == null ? "" : truncationMarker;
        return template
            .replace("{total}", String.valueOf(total))
            .replace("{dropped}", String.valueOf(dropped))
            .replace("{limit}", String.valueOf(limit));
    }

    public int getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(int maxChars) {
        if (maxChars > 0) {
            this.maxChars = maxChars;
        }
    }

    public Map<String, Integer> getPerTool() {
        return perTool;
    }

    public void setPerTool(Map<String, Integer> perTool) {
        this.perTool = perTool == null ? new LinkedHashMap<>() : new LinkedHashMap<>(perTool);
    }

    public String getTruncationMarker() {
        return truncationMarker;
    }

    public void setTruncationMarker(String truncationMarker) {
        this.truncationMarker = truncationMarker;
    }
}
