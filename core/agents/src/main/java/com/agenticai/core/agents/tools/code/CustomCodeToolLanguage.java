package com.agenticai.core.agents.tools.code;

/**
 * The languages the tool-sandbox microservice can compile/run. Stored as the enum NAME (see {@link
 * CustomCodeTool}'s {@code @Enumerated(EnumType.STRING)}), but sent over the wire to the sandbox
 * lowercased ({@code "java"} / {@code "python"}) - see {@link ToolSandboxClient}, whose request
 * contract is fixed by the service, not by this enum's spelling.
 */
public enum CustomCodeToolLanguage {
    JAVA,
    PYTHON;

    /** The literal string the sandbox's wire contract expects for this language. */
    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
