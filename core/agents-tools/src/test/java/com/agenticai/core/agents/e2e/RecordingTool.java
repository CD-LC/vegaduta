package com.agenticai.core.agents.e2e;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A raw tool with a real, countable side effect: every call that reaches it is recorded, so a test
 * can say "the invoice was sent exactly once" about what the guard chain actually let through,
 * regardless of what the model was told.
 */
public final class RecordingTool implements ToolCallback {

    private final ToolDefinition definition;
    private final Function<String, String> body;
    private final AtomicInteger executions = new AtomicInteger();
    private final List<String> arguments = new CopyOnWriteArrayList<>();

    public RecordingTool(String name, String description, Function<String, String> body) {
        this.definition =
                ToolDefinition.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(
                                "{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"}}}")
                        .build();
        this.body = body;
    }

    /** A tool that always returns {@code output}. */
    public static RecordingTool returning(String name, String description, String output) {
        return new RecordingTool(name, description, args -> output);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        executions.incrementAndGet();
        arguments.add(toolInput);
        return body.apply(toolInput);
    }

    /** How many times the tool body really ran. */
    public int executions() {
        return executions.get();
    }

    /** The exact argument JSON of every run, in order. */
    public List<String> argumentsSeen() {
        return List.copyOf(arguments);
    }
}
