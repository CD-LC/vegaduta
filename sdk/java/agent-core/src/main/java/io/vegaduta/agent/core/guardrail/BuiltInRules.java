package io.vegaduta.agent.core.guardrail;

import static io.vegaduta.agent.api.guardrail.GuardrailStage.INPUT;
import static io.vegaduta.agent.api.guardrail.GuardrailStage.OUTPUT;
import static io.vegaduta.agent.api.guardrail.GuardrailStage.TOOL_ARGUMENTS;
import static io.vegaduta.agent.api.guardrail.GuardrailStage.TOOL_OUTPUT;

import io.vegaduta.agent.api.guardrail.GuardrailAction;
import java.util.List;

/**
 * Rules every deployment benefits from. Secrets are redacted wherever they appear and blocked from
 * leaving through tool arguments; instruction-like text coming back from a tool is redacted so a
 * compromised tool or web page cannot steer the model (ASI01, ASI06).
 */
public final class BuiltInRules {

  private BuiltInRules() {}

  public static List<PatternRule> secrets() {
    return List.of(
        PatternRule.of(
            "secret.aws_access_key",
            "\\bAKIA[0-9A-Z]{16}\\b",
            GuardrailAction.REDACT,
            "AWS access key id",
            INPUT,
            OUTPUT,
            TOOL_OUTPUT),
        PatternRule.of(
            "secret.aws_access_key.egress",
            "\\bAKIA[0-9A-Z]{16}\\b",
            GuardrailAction.BLOCK,
            "AWS access key id must not be sent to a tool",
            TOOL_ARGUMENTS),
        PatternRule.of(
            "secret.private_key",
            "-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY(?: BLOCK)?-----",
            GuardrailAction.BLOCK,
            "Private key material",
            INPUT,
            OUTPUT,
            TOOL_ARGUMENTS,
            TOOL_OUTPUT),
        PatternRule.of(
            "secret.github_token",
            "\\bgh[pousr]_[A-Za-z0-9]{36,}\\b",
            GuardrailAction.REDACT,
            "GitHub token",
            INPUT,
            OUTPUT,
            TOOL_OUTPUT),
        PatternRule.of(
            "secret.github_token.egress",
            "\\bgh[pousr]_[A-Za-z0-9]{36,}\\b",
            GuardrailAction.BLOCK,
            "GitHub token must not be sent to a tool",
            TOOL_ARGUMENTS),
        PatternRule.of(
            "secret.slack_token",
            "\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b",
            GuardrailAction.REDACT,
            "Slack token",
            INPUT,
            OUTPUT,
            TOOL_ARGUMENTS,
            TOOL_OUTPUT),
        PatternRule.of(
            "secret.bearer_header",
            "(?i)authorization:\\s*bearer\\s+[A-Za-z0-9._\\-]{20,}",
            GuardrailAction.REDACT,
            "Bearer token in a header",
            INPUT,
            OUTPUT,
            TOOL_ARGUMENTS,
            TOOL_OUTPUT));
  }

  public static List<PatternRule> promptInjection() {
    return List.of(
        PatternRule.of(
            "injection.override_instructions",
            "(?i)\\b(?:ignore|disregard|forget)\\s+(?:all\\s+|any\\s+)?(?:previous|prior|above|earlier|your)\\s+(?:instructions|prompts|rules|guidelines)\\b",
            GuardrailAction.REDACT,
            "Instruction-override phrase in tool output",
            TOOL_OUTPUT),
        PatternRule.of(
            "injection.role_reassignment",
            "(?i)\\byou\\s+are\\s+now\\s+(?:a|an|the|in)\\b",
            GuardrailAction.REDACT,
            "Role-reassignment phrase in tool output",
            TOOL_OUTPUT),
        PatternRule.of(
            "injection.system_prompt_probe",
            "(?i)\\b(?:reveal|print|show|repeat)\\s+(?:your\\s+)?(?:system\\s+prompt|hidden\\s+instructions)\\b",
            GuardrailAction.REDACT,
            "System-prompt extraction phrase in tool output",
            TOOL_OUTPUT));
  }

  public static PatternGuardrail secretsGuardrail() {
    return new PatternGuardrail("builtin.secrets", secrets());
  }

  public static PatternGuardrail promptInjectionGuardrail() {
    return new PatternGuardrail("builtin.prompt-injection", promptInjection());
  }
}
