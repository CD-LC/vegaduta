package com.agenticai.core.agents.importer;

import java.util.regex.Pattern;

/**
 * Normalises an {@link AgentImportDraft} to what the platform's agent-create validation accepts, and
 * says so in the report whenever it had to change or invent something. Pure.
 */
public final class AgentImportFinalizer {

    private AgentImportFinalizer() {
    }

    /** Must stay equal to the {@code @Size(max)} on {@code AgentCreateRequest.systemPrompt}. */
    public static final int INSTRUCTIONS_MAX = 32768;

    static final String DEFAULT_NAME = "Imported agent";
    /**
     * Required only because agent creation rejects an empty prompt; the report flags it so it is never
     * mistaken for imported content.
     */
    static final String PLACEHOLDER_INSTRUCTIONS = "You are a helpful assistant.";
    private static final int DESCRIPTION_MAX = 500;
    private static final Pattern CONTROL_KEEP_WS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{[^{}]{1,100}\\}\\}");

    public static void finish(AgentImportDraft d) {
        AgentImportReport report = d.report;

        d.name = AgentImportText.sanitizeName(d.name, DEFAULT_NAME);
        d.description = d.description == null
            ? null
            : AgentImportText.truncate(AgentImportText.inline(d.description, DESCRIPTION_MAX), DESCRIPTION_MAX);
        if (d.description != null && d.description.isEmpty()) {
            d.description = null;
        }

        String instr = d.instructions == null ? "" : CONTROL_KEEP_WS.matcher(d.instructions).replaceAll("").trim();
        if (instr.isEmpty()) {
            instr = PLACEHOLDER_INSTRUCTIONS;
            report.needsManual(
                "Instructions",
                "The source had no instructions, so a one-line placeholder was used (this platform needs a non-empty prompt).",
                "Write the agent's instructions before using it."
            );
        } else if (instr.length() > INSTRUCTIONS_MAX) {
            report.warn("Instructions were " + instr.length() + " characters; truncated to the platform limit of "
                + INSTRUCTIONS_MAX + ". Review the end of the prompt.");
            instr = AgentImportText.truncate(instr, INSTRUCTIONS_MAX);
        }
        if (TEMPLATE_VAR.matcher(instr).find()) {
            report.warn("The instructions contain {{ ... }} placeholders from the source platform. They were kept as "
                + "literal text and are not filled in here - review them.");
        }
        d.instructions = instr;

        d.temperature = clamp(d.temperature, "temperature", report);
        d.topP = clamp(d.topP, "top_p", report);
    }

    private static Double clamp(Double v, String label, AgentImportReport report) {
        if (v == null) {
            return null;
        }
        if (v < 0.0 || v > 1.0) {
            double clamped = Math.max(0.0, Math.min(1.0, v));
            report.warn("The source " + label + " (" + v + ") is outside this platform's 0 to 1 range; set to "
                + clamped + ".");
            return clamped;
        }
        return v;
    }
}
