/**
 * Agent FinOps: cost modelling, attribution and showback.
 *
 * <p>Token spend is often less than a quarter of what an agent task really costs; tool calls,
 * retries and human review make up the rest. This package prices all three ({@link
 * io.vegaduta.agent.core.cost.CostModel}), records them per run in a thread-safe {@link
 * io.vegaduta.agent.core.cost.CostLedger} fed by a {@link io.vegaduta.agent.core.cost.CostObserver}
 * and a {@link io.vegaduta.agent.core.cost.CostAuditSink}, tags runs with team, feature and cost
 * centre ({@link io.vegaduta.agent.core.cost.CostAttribution}), and renders a {@link
 * io.vegaduta.agent.core.cost.ShowbackReport}. {@link
 * io.vegaduta.agent.core.cost.CostAwareBudgetProvider} lets the budget reserve real per-tool
 * estimates instead of a constant.
 */
@Experimental
@NullMarked
package io.vegaduta.agent.core.cost;

import io.vegaduta.agent.api.Experimental;
import org.jspecify.annotations.NullMarked;
