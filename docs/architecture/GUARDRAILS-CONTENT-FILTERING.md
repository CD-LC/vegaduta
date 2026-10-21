# Content Filtering Guardrails System

This document describes the agent-specific content filtering guardrails system built on the existing moderation infrastructure.

## Overview

The guardrails system provides per-agent, opt-in safety filters for content moderation. It evaluates both:
- **Input**: User messages before sending to the agent/LLM
- **Output**: Agent responses before returning to the user

When content violates a guardrail, a clear reason is shown to the user rather than silently blocking.

## Architecture

### Core Components

1. **ContentGuardrailType** (`ContentGuardrailType.java`)
   - Enum of available guardrails
   - Maps to specific implementations
   - Defines plan tier availability

2. **ContentGuardrail** (interface)
   - Contract for guardrail implementations
   - `evaluate(String content)` - synchronous check
   - `evaluate(String content, ChatClient)` - with optional semantic analysis
   - Stateless, thread-safe

3. **ContentGuardrailEngine** (orchestrator)
   - Coordinates multiple guardrails
   - Evaluates content through enabled guardrails
   - Short-circuits on first block (fail-fast)
   - Logs violations for observability

4. **Guardrail Implementations**
   - `ProfanityGuardrail` - detects offensive language (FREE)
   - `JailbreakDetectorGuardrail` - detects prompt injection/jailbreaks (FREE)
   - `PiiGuardrail` - detects emails, phones, SSNs, credit cards (PREMIUM)
   - `PolicyViolationGuardrail` - detects violence, illegal activity, hate speech (PREMIUM)
   - `ToxicityGuardrail` - semantic toxicity detection (UNLIMITED)

## Guardrails by Plan Tier

### FREE Tier (Basic Safety)
- ✓ **Profanity**: Offensive language
- ✓ **Jailbreak**: Prompt injection & role override attempts

### PREMIUM Tier (Extended Safety)
- ✓ All FREE guardrails
- ✓ **PII**: Emails, phone numbers, SSNs, credit cards
- ✓ **Policy Violation**: Violence, illegal activities, hate speech, self-harm

### UNLIMITED Tier (Full Suite)
- ✓ All PREMIUM guardrails
- ✓ **Toxicity**: Semantic analysis of hostile/toxic language (requires ChatClient)
- ✓ **Custom**: Tenant-specific guardrails (extensible)

## Database Schema

### Migration V149: `agent_enabled_guardrail`

```sql
create table agent_enabled_guardrail (
    agent_id uuid not null,
    guardrail_type varchar(32) not null,
    primary key (agent_id, guardrail_type),
    foreign key (agent_id) references agent(id) on delete cascade
);
```

**Backfill**: Empty set for all pre-existing agents (unchanged behavior)

## Entity Changes

### Agent Entity

New field: `enabledGuardrails` (Set<String>)
- Stored as `agent_enabled_guardrail` join table
- Empty = no guardrails (default)
- EAGER fetch for efficient evaluation

Getters/Setters:
```java
Set<String> getEnabledGuardrails()     // Unmodifiable set
void setEnabledGuardrails(Set<String>) // Update and persist
```

## API Endpoints

### GET /api/agents/{id}/guardrails

**Response:** Current and available guardrails for this agent

```json
{
  "enabledGuardrails": ["profanity", "jailbreak"],
  "availableGuardrails": ["profanity", "jailbreak", "pii", "policy_violation"],
  "lockedGuardrails": ["toxicity", "custom"],
  "guardrailDescriptions": {
    "profanity": "Profanity Filter",
    "jailbreak": "Jailbreak Detector",
    "pii": "PII Detector",
    "policy_violation": "Policy Violation Detector",
    "toxicity": "Toxicity Detector",
    "custom": "Custom Guardrail"
  }
}
```

### PATCH /api/agents/{id}/guardrails

**Request:**
```json
{
  "guardrails": ["profanity", "jailbreak", "pii"]
}
```

**Response:** Same as GET (updated state)

**Validation:**
- Plan gating enforced (403 if requesting locked guardrails)
- Invalid guardrail IDs silently skipped (future-proof)

## Services

### ContentGuardrailEngine

Orchestrates evaluation of content through multiple guardrails.

```java
ContentGuardrailCheckResult evaluate(
    String content,
    Set<ContentGuardrailType> enabledGuardrails,
    GuardrailEvaluationContext context
);

ContentGuardrailCheckResult evaluate(
    String content,
    Set<ContentGuardrailType> enabledGuardrails,
    GuardrailEvaluationContext context,
    ChatClient chatClient  // Optional for semantic checks
);
```

**Returns:**
- `pass()` - Content is safe
- `blocked(type, reason, confidence)` - Content blocked with clear reason

### AgentGuardrailService

Manages per-agent guardrail configuration with plan gating.

```java
void updateAgentGuardrails(UUID agentId, Set<String> guardrailIds)
    throws GuardrailNotAvailableForPlanException

Set<ContentGuardrailType> getAvailableGuardrailsForPlan()
Set<ContentGuardrailType> getLockedGuardrailsForPlan()
void validateGuardrailsForPlan(Set<String> ids, TenantPlan plan)
```

### GuardrailViolationLogger

Logs all guardrail blocks for observability.

```java
void logInputViolation(UUID agentId, String sessionId, ContentGuardrailCheckResult result)
void logOutputViolation(UUID agentId, String sessionId, ContentGuardrailCheckResult result)
```

## Integration Points

### AgentChatService (implemented)

The chat turn path enforces the enabled guardrails in `ChatGuardrailGate`
(`core/agents-turn/.../agents/turn/ChatGuardrailGate.java`): `enforceInboundGuardrails` evaluates the input path
with `GuardrailEvaluationContext.input(...)` after `moderateInbound`, and `enforceOutboundGuardrails` evaluates
the output path with `GuardrailEvaluationContext.output(...)`; streamed output violations are audited by
`auditStreamedOutboundViolation`. `ResponsePiiFlagAdvisor` (`core/agents-turn/.../agents/gateway`) also runs the
PII guardrail over completed responses. The sketch below is the original design; read the class for the exact
behaviour:

**Input Path (before LLM call):**
```java
// After moderateInbound but before LLM
Set<ContentGuardrailType> enabledGuardrails = agent.getEnabledGuardrails();
Set<ContentGuardrailType> types = guardrailService.resolveGuardrailTypes(enabledGuardrails);

ContentGuardrailCheckResult result = guardrailEngine.evaluate(
    userMessage,
    types,
    GuardrailEvaluationContext.input(agentId, sessionId, turnId),
    chatClient
);

if (!result.isPassed()) {
    guardrailLogger.logInputViolation(agentId, sessionId, result);
    throw new GuardrailViolationException(result.getBlockReason());
}
```

**Output Path (before returning response):**
```java
ContentGuardrailCheckResult result = guardrailEngine.evaluate(
    assistantResponse,
    types,
    GuardrailEvaluationContext.output(agentId, sessionId, turnId),
    chatClient
);

if (!result.isPassed()) {
    guardrailLogger.logOutputViolation(agentId, sessionId, result);
    // Option 1: Throw exception
    throw new GuardrailViolationException(result.getBlockReason());
    // Option 2: Return safe message (chosen in implementation)
    return Flux.just("Unable to provide this response due to safety guidelines.");
}
```

## Observability

### Logging

All guardrail evaluations are logged:
- **Violations**: WARN level with guardrail type, confidence, reason
- **Audit trail**: Recorded via `GuardrailViolationLogger` to audit system

### Metrics

Track:
- Guardrail block rate by type
- False positive rate (false blocks)
- Confidence distribution
- Per-agent violation patterns

### Examples

```
WARN GuardrailViolationLogger: Input guardrail violation: 
     agent=12345, session=sess-1, path=input guardrail=jailbreak 
     confidence=90 reason="Your request appears to contain a prompt injection..."

WARN GuardrailViolationLogger: Output guardrail violation: 
     agent=12345, session=sess-1, path=output guardrail=pii 
     confidence=85 reason="Response contains email addresses..."
```

## Error Handling

### Plan Gating Exception

```java
try {
    guardrailService.updateAgentGuardrails(agentId, Set.of("toxicity"));
} catch (GuardrailNotAvailableForPlanException ex) {
    // REST endpoint returns 403 Forbidden
    // Body: { "error": "Toxicity guardrail requires UNLIMITED plan or higher" }
}
```

### Unknown Guardrail Types

- When resolving guardrail types, unknown IDs are **silently skipped**
- Rationale: Future guardrail types should not break current deployments
- Logged as WARN for debugging

## Testing

### Unit Tests

**ContentGuardrailEngineTest**: 40+ tests covering:
- Clean content passing all guardrails
- Each guardrail detecting its respective violation
- Short-circuit on first block
- Empty/null content handling
- Guardrail availability

**ContentGuardrailsImplementationTest**: 30+ tests covering:
- Individual guardrail behavior
- Pattern matching (case-insensitive)
- Edge cases
- Type resolution

Run tests:
```bash
mvn -f core/guardrails test
```

## Implementation Notes

### Plan Tier Mapping

The `GUARDRAIL_PLAN_REQUIREMENTS` static map (in AgentGuardrailService) defines which guardrails require which plan. Extend it when adding new guardrails:

```java
static {
    GUARDRAIL_PLAN_REQUIREMENTS.put(
        ContentGuardrailType.YOUR_NEW_GUARDRAIL,
        TenantPlan.PREMIUM
    );
}
```

### Semantic Guardrails

Guardrails that use semantic analysis (like `ToxicityGuardrail`) have:
- `requiresChatClient() = true` - tells engine a ChatClient is needed
- `evaluate(content, chatClient)` override - uses ChatClient for LLM call
- Fail-safe: Return `pass()` if ChatClient is null or on error

### Extending with Custom Guardrails

To add a new guardrail:

1. Create a class implementing `ContentGuardrail`
2. Implement `evaluate(String)` and optionally `evaluate(String, ChatClient)`
3. Add `@Component` annotation
4. Add to `GUARDRAIL_PLAN_REQUIREMENTS` map in AgentGuardrailService
5. Add to `ContentGuardrailType` enum

Engine auto-wires all `ContentGuardrail` beans.

## Security Considerations

### Fail-Safe Posture

- All guardrails fail **open** on errors (log and allow)
- No single guardrail can crash the system
- Moderation is additive to existing security layers (not a replacement)

### No Prompt Injection Risk

- Guardrail evaluation is read-only (does not execute)
- Profanity/jailbreak patterns are pre-compiled regexes (no dynamic code)
- Semantic guardrails use short, templated prompts (no user input in prompt)

### Plan Gating Enforcement

- Enforced at write time (update endpoint)
- Enforced at evaluation time (if guardrail smuggled in via direct DB access)
- No plan-tier bypasses in agent model or repository

## Future Enhancements

1. **Semantic Guardrails**: Toxicity detection via LLM scoring
2. **Custom Guardrails**: Per-tenant regex/keyword lists
3. **Guardrail Chains**: AND/OR logic for guardrail combinations
4. **Confidence Thresholds**: Per-agent tunability
5. **A/B Testing**: Experiment with guardrail effectiveness
6. **Integration with Code-Intel**: Guardrails for code execution

## Related Documents

- `docs/architecture/GUARDRAILS-AND-INTEGRATION.md` - Code-Intel guardrails model
- `docs/product/BILLING-TIER-STRATEGY-2026-07-27.md` - Tier entitlements overview
- `core/guardrails/` - Full guardrails module
- `core/agents/` - Agent service integration points
