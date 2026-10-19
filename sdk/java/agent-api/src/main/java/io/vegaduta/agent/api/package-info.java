/**
 * Root of the VegaDuta Agent SDK public API.
 *
 * <p>Everything here is an interface, a record, an enum or an exception. Nothing depends on a
 * framework. Implementations live in {@code vegaduta-agent-core}; framework adapters (Spring AI,
 * LangChain4j) translate their tool and model types to these.
 *
 * <p>Packages:
 *
 * <ul>
 *   <li>{@link io.vegaduta.agent.api.tool}: the framework-neutral {@code Tool} and its call and
 *       result records.
 *   <li>{@link io.vegaduta.agent.api.risk}: tool risk classification and the OWASP ASI catalogue.
 *   <li>{@link io.vegaduta.agent.api.gate}: the dispatch gate that allows, denies or escalates a
 *       tool call.
 *   <li>{@link io.vegaduta.agent.api.approval}: human approval requests, store and gate.
 *   <li>{@link io.vegaduta.agent.api.budget}: reserve-then-commit budgets.
 *   <li>{@link io.vegaduta.agent.api.journal}: idempotent effect journal.
 *   <li>{@link io.vegaduta.agent.api.guardrail}: content guardrails on inputs, outputs and tool
 *       arguments.
 *   <li>{@link io.vegaduta.agent.api.hook}: policy hooks at execution points.
 *   <li>{@link io.vegaduta.agent.api.audit}: the audit event and sink.
 *   <li>{@link io.vegaduta.agent.api.observe}: tool call observation for metrics and tracing.
 * </ul>
 */
@NullMarked
package io.vegaduta.agent.api;

import org.jspecify.annotations.NullMarked;
