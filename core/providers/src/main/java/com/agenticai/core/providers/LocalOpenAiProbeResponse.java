package com.agenticai.core.providers;

/**
 * Result of {@code POST /api/providers/local-openai/probe}. {@code toolCallGrade} is null when
 * nothing could be measured (server unreachable, model not loaded, probe disabled); {@code
 * serverKind} and {@code toolRouterMode} are null when the server was not recognised. {@code advice}
 * is plain-language guidance for the tenant, never a raw upstream message.
 */
public record LocalOpenAiProbeResponse(
    String model,
    ToolCallGrade toolCallGrade,
    ToolCallCapabilityProbe.ServerKind serverKind,
    ToolRouterMode toolRouterMode,
    Double parameterBillions,
    String advice
) {

    static LocalOpenAiProbeResponse of(String model, ToolCallCapabilityProbe.Result result) {
        return new LocalOpenAiProbeResponse(
            model, result.grade(), result.serverKind(), ToolRouterMode.forServer(result.serverKind()),
            result.parameterBillions(), adviceFor(result.grade(), result.serverKind()));
    }

    static LocalOpenAiProbeResponse notMeasured(String model) {
        return new LocalOpenAiProbeResponse(model, null, null, null, null,
            "Tool calling could not be measured: the server did not answer a test request for this model (or the "
                + "check is turned off on this platform). Check the server is running with the model loaded, then "
                + "try again. Agents can still chat.");
    }

    static String adviceFor(ToolCallGrade grade, ToolCallCapabilityProbe.ServerKind kind) {
        if (grade == null) {
            return "Tool calling could not be measured.";
        }
        return switch (grade) {
            case STRUCTURED -> "Native tool calling works. Agents can use tools with this model.";
            case TEXT -> "This model writes tool calls as plain text instead of the structured format. The platform "
                + "recovers the common shapes, but tool use will be less reliable than with a model trained for it."
                + serverHint(kind);
            case NONE -> "This model did not call a tool when asked. Agents will chat but not use tools with it."
                + serverHint(kind);
        };
    }

    private static String serverHint(ToolCallCapabilityProbe.ServerKind kind) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case VLLM -> " vLLM needs --enable-auto-tool-choice and a --tool-call-parser matching the model family.";
            case LLAMA_CPP -> " llama-server needs --jinja (and a model whose chat template supports tools).";
            case OLLAMA, OLLAMA_WITHOUT_FORMAT -> " Pick a model tagged with the 'tools' capability in Ollama.";
            case OTHER -> " Check the server's documentation for tool or function calling, and prefer a "
                + "tool-trained model (Qwen, Llama 3.x, Mistral instruct families).";
        };
    }
}
