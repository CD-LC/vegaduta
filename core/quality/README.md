# Agent Response Quality Evaluation Framework

Automatic measurement and tracking of agent response quality across multiple dimensions.

## Overview

This module provides:
- **Evaluation Framework**: Pluggable evaluators for different quality metrics
- **Automatic Scoring**: Evaluates every agent response asynchronously (non-blocking)
- **Historical Tracking**: Stores scores over time for trend analysis
- **Dashboard Ready**: Provides APIs suitable for charting quality trends
- **Plan Gating**: FREE tier gets basic evals, PREMIUM gets all advanced evals

## Evaluation Types

Each evaluation type measures a specific quality dimension:

### Available Evaluations

| Type | Tier | Metric | Method |
|------|------|--------|--------|
| **HELPFULNESS** | FREE | Does response help answer the question? | LLM-as-judge |
| **SAFETY** | FREE | Does response follow guardrails? | LLM-as-judge |
| **ACCURACY** | PREMIUM | Is response factually correct? | LLM-as-judge |
| **TONE** | PREMIUM | Does tone match agent persona? | LLM-as-judge |
| **RELEVANCE** | PREMIUM | Does response stay on topic? | LLM-as-judge |
| **LENGTH** | PREMIUM | Is response length appropriate? | Heuristic |

### Scoring

Each evaluation produces a **normalized score from 0.0 to 1.0**:
- **1.0** = Excellent/Best practice
- **0.75** = Good with minor issues
- **0.5** = Neutral/Mixed
- **0.25** = Poor with significant issues
- **0.0** = Unacceptable/Failure

### Aggregation

The **overall_score** is a weighted average of enabled evaluations:
```
overall_score = sum(score * weight) / sum(weight)
```

Currently all evaluations have equal weight (0.5, hardcoded in
`EvaluationType.getDefaultWeight()`); per-tenant customization is a Future
Enhancement, not yet configurable.

## Architecture

### Components

1. **Evaluator Interface** (`Evaluator.java`)
   - Pluggable interface for evaluation implementations
   - Implementations are stateless, thread-safe, and reusable

2. **Evaluation Engine** (`EvalEngine.java`)
   - Orchestrates running evaluations asynchronously
   - Handles score aggregation
   - Virtual-thread-per-task executor (no fixed pool - see its javadoc)

3. **Quality Service** (`QualityEvaluationService.java`)
   - High-level API for evaluation operations
   - Triggers async evaluations after responses
   - Queries historical data for dashboards

4. **Database Storage** (`AgentQualityRecord.java`)
   - Append-only log (never updated/deleted)
   - Per-evaluation scores and feedback
   - Response metadata for context

5. **REST API** (`QualityController.java`) — **PREMIUM+ only** (pricing audit
   2026-07-31, item C1): every endpoint checks the tenant's plan via
   `TenantLimits.plan()`; FREE tenants get 403 with an upgrade message
   (`TenantLimitExceededException`, same gate as `LlmCallLogController`).
   - GET /api/agents/{id}/quality/summary - latest scores
   - GET /api/agents/{id}/quality/history - historical data for charts
   - GET /api/agents/{id}/quality/statistics - aggregated metrics
   - GET /api/agents/{id}/quality/config - enabled evaluations for this plan

## Usage

### Triggering Evaluations

After an agent generates a response, trigger evaluation:

```java
// In AgentChatService or wherever responses are generated
evaluationService.evaluateResponseAsync(
    agentId,
    turnNumber,
    new EvaluationContext(
        agentId,
        agentName,
        systemPrompt,
        userMessage,
        agentResponse,
        toolCalls,
        inputTokens,
        outputTokens,
        model
    ),
    isPremium  // from tenant's plan
);
```

The method returns immediately; evaluation runs in background.

### Querying Results

```java
// Get latest summary
Optional<AgentQualityRecord> latest = service.getLatestSummary(agentId);

// Get historical data for charts
Map<String, Object> data = service.getHistoricalData(agentId, limit: 100);
// Returns:
// {
//   "timestamps": ["2025-07-28T10:30:00Z", ...],
//   "overall_scores": [0.85, 0.87, ...],
//   "helpfulness_scores": [0.8, 0.9, ...],
//   "accuracy_scores": [0.85, 0.85, ...],
//   ...
// }

// Get statistics
QualityStatistics stats = service.getStatistics(agentId);
// Returns average scores, total evaluations, etc.
```

### REST API Usage

```bash
# Get latest quality summary
curl https://api.staging.example.com/api/agents/{agentId}/quality/summary

# Get history (last 50 records)
curl https://api.staging.example.com/api/agents/{agentId}/quality/history?limit=50

# Get statistics
curl https://api.staging.example.com/api/agents/{agentId}/quality/statistics

# Get configuration (which evals are enabled)
curl https://api.staging.example.com/api/agents/{agentId}/quality/config
```

## Implementation Details

### LLM-as-Judge

Complex evaluations (helpfulness, accuracy, tone, relevance, safety) use an LLM as a judge:

1. Built-in prompt templates in each evaluator (shared pipeline in `AbstractLlmJudgeEvaluator`)
2. `JudgeChatClientFactory` resolves a judge `ChatClient` from the **tenant's own
   active BYOK provider** (via `LlmGatewayService`/`ProviderCredentialResolver`,
   mirroring `AssistService#buildChatClient`/`McpStdioSelfHealer#resolveChatClient`)
   - the model is whatever provider/model the tenant currently has active
3. Judge scores the response on 0.0-1.0 scale and provides feedback explaining the score
4. Scores are persisted in the database

If the tenant has no active provider or the call fails, the evaluation reports an
explicit `UNAVAILABLE` status with a `null` score - see Error Handling below.

### Heuristic Evaluations

Simple evaluations (length) use rule-based heuristics:

1. **LengthEvaluator** uses word count and complexity heuristics
2. Runs locally with no LLM calls
3. Fast and deterministic

### Error Handling — honest absence, never fabricated scores

A failed evaluation is reported as **absent**, never as a made-up number
(the previous behaviour of degrading every failure to a neutral 0.5 made
"unmeasured" indistinguishable from "measured as mediocre"):

- If an evaluator can't get a judge verdict (no active provider, provider
  failure, malformed verdict), its result has `status=UNAVAILABLE` and
  `score=null`, with the reason in `feedback`
- `overallScore` aggregates only the dimensions that were actually
  `EVALUATED`; when nothing was measured it is `null`
- An all-unavailable run stores **no** `agent_quality_record` row at all
- `averageQualityScore` in the statistics API is `null` (JSON null) when an
  agent has no records - clients must render "no data"
- Errors are logged but don't block response delivery (evaluation stays
  async and off the chat path)

### Asynchronous Execution

Evaluations run in the background on virtual threads:
- API returns immediately (no waiting for evals)
- Evals persist to database automatically
- Multiple agents can be evaluated in parallel

## Database Schema

### Tables

- `agent_quality_record` - Main evaluation record
- `agent_quality_record_scores` - Map of scores by evaluation type
- `agent_quality_record_feedback` - Map of feedback by evaluation type

### Columns

```sql
agent_quality_record:
  - id UUID (PK)
  - agent_id UUID
  - turn_number INT
  - overall_score DECIMAL(3,2)  -- 0.0-1.0
  - user_message TEXT
  - agent_response TEXT
  - input_tokens BIGINT
  - output_tokens BIGINT
  - created_at TIMESTAMP
```

### Indexes

- `idx_agent_quality_agent_id_created_at` - For historical queries
- `idx_agent_quality_agent_id` - For agent stats
- `idx_agent_quality_created_at` - For recent evals

## Plan Tier Gating

### FREE Plan

Users get basic evaluations:
- HELPFULNESS - Is response helpful?
- SAFETY - Does it follow guardrails?

Quality tab in console shows:
- Latest overall score
- "Upgrade to Premium" CTA for advanced metrics

### PREMIUM Plan

Users get all evaluations:
- All FREE tier evals
- ACCURACY - Factual correctness
- TONE - Persona consistency
- RELEVANCE - Topic focus
- LENGTH - Appropriate length

Quality tab shows:
- Overall score trend (line chart)
- Per-evaluation scores (bar chart)
- Detailed feedback for each eval
- Statistics (average, min, max)

## Configuration

This module intentionally has **no `quality.*` application properties**. A
draft config file (`application-quality.yml`) shipped with the original
scaffolding at the module root - never on the classpath, never read by any
`@Value`/`@ConfigurationProperties` - and was removed on 2026-08-27 because
every knob it sketched had been superseded by a deliberate in-code decision:

- **Concurrency** (`evaluator.thread-pool-size`/`timeout-ms`): `EvalEngine`
  runs on a virtual-thread-per-task executor (JVM thread-pool audit,
  2026-07-29) - there is no pool to size, and no per-eval timeout mechanism
  was ever built.
- **Enabled evals / tiers**: driven by the tenant's REAL plan
  (`EvalEngine.getEnabledForTier`, plan-gated in `QualityController` via
  `TenantLimits`), not static config - static YAML tiers would conflict with
  the entitlement system.
- **Weights**: `EvaluationType.getDefaultWeight()` (all 0.5 today).
- **Judge max-tokens**: `AbstractLlmJudgeEvaluator.JUDGE_MAX_TOKENS` (1024);
  the judge MODEL is resolved per-tenant by `JudgeChatClientFactory`.
- **`features.semantic-evals-enabled` / `features.custom-prompts-enabled`**:
  neither feature exists (see Future Enhancements) - the flags gated nothing.
- **`quality.enabled` kill-switch**: the background per-turn path it would
  disable (`evaluateResponseAsync`) has no callers today; the live sampled
  background quality check is the separate `LlmQualityJudgeService`,
  configured under `app.llm.judge.*` in `core/app`'s `application.yml`.

If a knob here ever needs to become configurable, wire it explicitly
(`@Value`/`@ConfigurationProperties`) at that point - do not reintroduce an
unwired config file.

## Frontend Integration

### Quality Tab in Agent Details

Location: `/console/agents/{id}/quality`

Shows:
- **Summary Card** - Overall score, last updated
- **Quality Score Trend** - Line chart of overall scores over time
- **Per-Evaluation Scores** - Bar chart of each evaluation type
- **Recent Feedback** - Table of latest eval feedback
- **Statistics** - Average/min/max scores by evaluation type

### Upgrade CTA (FREE → PREMIUM)

For FREE tier users:
- Show "Upgrade to unlock advanced quality metrics" on quality tab
- Highlight which evals require PREMIUM
- Link to upgrade page

## Testing

### Unit Tests

```bash
mvn test -pl quality
```

Covered:
- Individual evaluators (LengthEvaluator tested, others mock-friendly)
- Score aggregation in EvalEngine
- Normalization (scores always 0-1)
- Plan tier gating

### Integration Tests

- Evaluations persist to database
- Historical data queries work
- REST endpoints return correct data
- Plan tier gating enforced

### Manual Testing

1. Create an agent
2. Send a chat turn
3. Wait for async eval to complete (~5-30s depending on model)
4. Check /quality/summary endpoint
5. Send more turns
6. Check /quality/history for trend data

## Future Enhancements

### Phase 2
- [ ] Custom weights per-tenant
- [ ] Webhooks on low-quality responses
- [ ] Alerts when quality drops below threshold
- [ ] Custom evaluation prompt templates per-agent
- [ ] A/B testing framework (compare agent versions)

### Phase 3
- [ ] Fine-tuned judge model for specific domains
- [ ] Bulk historical re-evaluation
- [ ] Quality anomaly detection
- [ ] Recommendation engine (suggest prompt improvements)

## Troubleshooting

### Evaluations Not Running

1. Check logs for evaluator errors
2. Ensure LlmGatewayService is properly configured (the tenant needs an
   active BYOK provider for the LLM-as-judge evaluators)
3. Verify database migrations (V147) ran

### Low Scores

1. Check judge feedback for specific issues
2. Consider adjusting system prompt for agent
3. Verify user input quality
4. Check if LENGTH evaluator is miscalibrated

### High Latency

1. Check LLM gateway queue depth
2. Consider caching identical questions
3. Profile with different evaluator subsets

## Related Documentation

- `eval-harness.md` (this directory) - the standalone golden-dataset eval CLI
  (`com.agenticai.core.quality.eval`, merged from the former `core/eval`
  module): prompt-regression runner, Langfuse mirroring, token baseline
- `docs/AGENT-COORDINATION-2026-07-18.md` - Eval feature ownership tracking
- `docs/BILLING-TIER-STRATEGY-2026-07-27.md` - Plan tier architecture
- Agent Dashboard Design (Frontend)
