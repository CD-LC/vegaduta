# Consumer contract: `sdk-python-agent`

The Python Agent SDK (`sdk/python-agent`, package `vegaduta_agent`) **makes no HTTP call to core**.
It runs inside the user's own application. `sdk/python-agent/tests/test_contracts.py` fails if the
package ever imports a network client (any non-stdlib module, the stdlib's socket, ssl, http, ftp and
mail modules, or a module loaded by computed name), so a first call to core has to start with a new
interaction here.

What it does share with core is vocabulary:

- `AuditEvent.to_flat_map()` uses the field names of core's `AuditLogEntryResponse`
  (`GET /api/audit-log`, schema `contracts/outbound/audit-event.schema.json`);
- `ApprovalRequest` mirrors core's `ApprovalRequestResponse`
  (`GET /api/approvals/{id}`, schema `contracts/outbound/approval-callback.schema.json`).

`governance-records.contract.json` lists those two core responses and the fields the SDK mirrors,
so core's provider verifier fails if core renames one. The test validates the payloads the SDK
emits (audit flat maps, approval records, `GovernanceReport.to_jsonable()` approvals) against the
outbound schemas.

The two do not match exactly. `outbound-alignment.json` freezes every known divergence:

| Record | Divergence |
|---|---|
| Audit | SDK-only keys `runId`, `tenantId`, `agentId`, `sessionId`, `correlationId`, `risks` (plus free-form attributes) |
| Audit | None for outcomes: the SDK's `AuditOutcome` and core's are the same set (core/app `AuditOutcomeAlignmentTest` pins them) |
| Approval | SDK statuses `EXPIRED`, `EXECUTED`, `FAILED` and core statuses `EXECUTION_FAILED`, `DEAD_LETTERED` exist on one side only |
| Approval | core requires `retryCount`, which the SDK has no counterpart for |
| Report | `GovernanceReport.to_jsonable()` names approval fields `action` and `decisionReason`, where core uses `actionType` and `rejectionReason` |

A new divergence fails the test until someone adds it to that file on purpose. The lists should
only get shorter, as the SDK and core converge.
