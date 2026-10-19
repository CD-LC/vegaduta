package com.agenticai.core.agents.tools.code.dto;

/**
 * {@code POST /v1/validate} request. Field names are the sandbox's fixed wire contract - do not
 * rename them to match Java conventions, and do not add fields the service does not read.
 *
 * @param language the sandbox's lowercase spelling ({@code "java"} / {@code "python"})
 */
public record SandboxValidateRequest(String language, String toolName, String source) {}
