package io.vegaduta.agent.api.annotation;

import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the risk classification of a tool next to its code. Framework adapters read it from the
 * tool method (for example a Spring AI {@code @Tool} method) and register a {@link
 * io.vegaduta.agent.api.risk.ToolClassification} for the tool's name.
 *
 * <pre>{@code
 * @Tool(description = "Refund an order")
 * @Governed(risk = RiskLabel.HIGH, requiresApproval = true, idempotent = true)
 * String refund(String orderId, long cents) { ... }
 * }</pre>
 *
 * <p>On a class, it sets the default for every tool method in that class; a method-level annotation
 * overrides it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Governed {

  /** How much damage a wrong call can do. Drives the default approval and audit behaviour. */
  RiskLabel risk() default RiskLabel.MEDIUM;

  /** The call changes state outside the process (writes, sends, pays, deletes). */
  boolean sideEffecting() default true;

  /** The call returns data the model must not leak (customer records, secrets, internal docs). */
  boolean readsPrivateData() default false;

  /** The call's output comes from a party the model should not trust (web, email, MCP server). */
  boolean untrustedInput() default false;

  /** The call can move data out of the trust boundary (send email, post HTTP, write to shared). */
  boolean exfiltrationSink() default false;

  /** Repeating the call with the same arguments is safe; the effect journal may replay results. */
  boolean idempotent() default false;

  /** A human must approve each call before it runs, whatever the policy's risk threshold is. */
  boolean requiresApproval() default false;

  /** OWASP Agentic Top 10 items this tool is exposed to, for audit and reporting. */
  AsiRisk[] risks() default {};
}
