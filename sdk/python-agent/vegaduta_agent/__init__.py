"""vegaduta-agent — governance for AI agents in Python, as a dependency.

Tool risk classification, human approval, budgets, idempotent effects, content
guardrails, hooks, audit and telemetry, wrapped around the tools you already
have — plus the harder guarantees a security review asks for: a kill switch,
tiered autonomy, narrowing mandate chains, tamper-evident (hash-chained,
signable) audit with evidence export, durable SQLite stores, tool-shape
pinning, governed memory, cost attribution, and policy replay / shadow mode.

Works on plain functions with zero dependencies; adapters for LangChain,
OpenAI-style tool calling and OpenTelemetry are optional extras.
Framework-free core, same contracts as the Java Agent SDK
(``io.vegaduta:vegaduta-agent-*``).

Quick start::

    from vegaduta_agent import Governance, governed, RiskLabel, AsiRisk

    @governed(risk=RiskLabel.HIGH, requires_approval=True, idempotent=True,
              risks={AsiRisk.ASI02})
    def refund(order_id: str, cents: int) -> str:
        ...

    governance = Governance()
    tool = governance.wrap(refund)
    result = tool({"order_id": "o_1", "cents": 500})     # -> approvalRequired JSON
    req = governance.approval_gate.pending()[0]
    governance.approval_gate.approve(req.id, "reviewer@corp")  # runs refund()
"""

from .approval import (
    ApprovalDecisionAuthorizer,
    ApprovalGate,
    ApprovalRequest,
    ApprovalStatus,
    ApprovalStore,
    FourEyesAuthorizer,
    InMemoryApprovalStore,
    allowed_deciders,
    sign_receipt,
    verify_receipt,
)
from .audit import (
    AUDIT_LOGGER_NAME,
    AuditEvent,
    AuditOutcome,
    AuditSink,
    InMemoryAuditSink,
    LoggingAuditSink,
)
from .autonomy import (
    AUTONOMY_ATTRIBUTE,
    AutonomyGate,
    AutonomyLevel,
    AutonomyRegistry,
    AutonomyRequirement,
)
from .budget import (
    Budget,
    BudgetCharge,
    BudgetProvider,
    ChargeEstimator,
    CountingBudget,
    default_provider,
    one_call_per_tool,
)
from .context import RunContext
from .cost import (
    CostLedger,
    CostModel,
    CostObserver,
    ModelPrice,
    showback,
)
from .errors import (
    ApprovalAlreadyDecidedException,
    ApprovalNotAuthorizedException,
    ApprovalNotFoundException,
    BudgetExceededException,
    GovernanceException,
    GuardrailViolationException,
    ToolDeniedException,
)
from .evidence import (
    GENESIS,
    ChainVerification,
    EvidenceBundle,
    HashChainedAuditSink,
    HmacSha256,
    evidence_bundle,
)
from .evidence import (
    verify as verify_audit_chain,
)
from .gate import Outcome, ToolDispatchGate, Verdict, allow_all
from .governance import Governance, GovernanceMode, GovernancePolicy, GovernedTool
from .governed import classification_of, governed
from .guardrail import (
    ContentGuardrail,
    GuardrailAction,
    GuardrailResult,
    GuardrailStage,
    GuardrailViolation,
    InstructionLikeTextGuardrail,
    SecretRedactionGuardrail,
    run_guardrails,
)
from .halt import (
    DrillReport,
    HaltGate,
    HaltRecord,
    HaltScope,
    HaltScopeKind,
    HaltSwitch,
    InMemoryHaltStore,
    halt_drill,
)
from .hooks import FailMode, Hook, HookDecision, HookEvent, HookOutcome, HookPoint, WebhookHook
from .journal import (
    EffectJournal,
    InMemoryEffectJournal,
    SqliteEffectJournal,
    UnknownEffectPolicy,
    disabled,
    effect_key,
    interrupted_keys,
)
from .mandates import (
    Mandate,
    MandateGate,
)
from .mandates import (
    attach as mandate_attach,
)
from .mandates import (
    from_context as mandate_from_context,
)
from .mandates import (
    verify_chain as verify_mandate_chain,
)
from .memory import (
    GovernedMemoryStore,
    InMemoryMemoryStore,
    MemoryEntry,
    MemoryStore,
    MemoryWriteBlockedException,
    MemoryWriteCapExceededException,
    ProbeReport,
    poison_probe,
)
from .memory import (
    Provenance as MemoryProvenance,
)
from .notify import slack_message, teams_message
from .observe import OtelToolCallObserver, ToolCallObserver
from .provenance import (
    Drift,
    InMemoryManifestPinStore,
    ManifestPinStore,
    ProvenanceGate,
    ProvenanceMode,
    ToolManifest,
    manifest_digest,
)
from .replay import (
    ChangeKind,
    PolicyDiff,
    RecordedCall,
    ReplayReport,
    ShadowGate,
    TraceMismatch,
    TraceRecorder,
    policy_replay,
    replay_trace,
)
from .report import GovernanceReport, ToolCallSummary, governance_report
from .risk import AsiRisk, RiskLabel, ToolClassification
from .riskmap import Deduction, RiskMap, ScannedTool, risk_map
from .stores import SqliteApprovalStore, SqliteAuditSink
from .tool_call import ToolCall

__version__ = "0.1.0"

__all__ = [
    "AUDIT_LOGGER_NAME",
    "AUTONOMY_ATTRIBUTE",
    "ApprovalAlreadyDecidedException",
    "ApprovalDecisionAuthorizer",
    "ApprovalGate",
    "ApprovalNotAuthorizedException",
    "ApprovalNotFoundException",
    "ApprovalRequest",
    "ApprovalStatus",
    "ApprovalStore",
    "AsiRisk",
    "AuditEvent",
    "AuditOutcome",
    "AuditSink",
    "AutonomyGate",
    "AutonomyLevel",
    "AutonomyRegistry",
    "AutonomyRequirement",
    "Budget",
    "BudgetCharge",
    "BudgetExceededException",
    "BudgetProvider",
    "ChainVerification",
    "ChangeKind",
    "ChargeEstimator",
    "ContentGuardrail",
    "CostLedger",
    "CostModel",
    "CostObserver",
    "CountingBudget",
    "Deduction",
    "Drift",
    "DrillReport",
    "EffectJournal",
    "EvidenceBundle",
    "FailMode",
    "FourEyesAuthorizer",
    "GENESIS",
    "Governance",
    "GovernanceException",
    "GovernanceMode",
    "GovernancePolicy",
    "GovernanceReport",
    "GovernedMemoryStore",
    "GovernedTool",
    "GuardrailAction",
    "GuardrailResult",
    "GuardrailStage",
    "GuardrailViolation",
    "GuardrailViolationException",
    "HaltGate",
    "HaltRecord",
    "HaltScope",
    "HaltScopeKind",
    "HaltSwitch",
    "HashChainedAuditSink",
    "HmacSha256",
    "Hook",
    "HookDecision",
    "HookEvent",
    "HookOutcome",
    "HookPoint",
    "InMemoryApprovalStore",
    "InMemoryAuditSink",
    "InMemoryEffectJournal",
    "InMemoryHaltStore",
    "InMemoryManifestPinStore",
    "InMemoryMemoryStore",
    "InstructionLikeTextGuardrail",
    "LoggingAuditSink",
    "Mandate",
    "MandateGate",
    "ManifestPinStore",
    "MemoryEntry",
    "MemoryProvenance",
    "MemoryStore",
    "MemoryWriteBlockedException",
    "MemoryWriteCapExceededException",
    "ModelPrice",
    "Outcome",
    "OtelToolCallObserver",
    "PolicyDiff",
    "ProbeReport",
    "ProvenanceGate",
    "ProvenanceMode",
    "RecordedCall",
    "ReplayReport",
    "RiskLabel",
    "RiskMap",
    "RunContext",
    "ScannedTool",
    "SecretRedactionGuardrail",
    "ShadowGate",
    "SqliteApprovalStore",
    "SqliteAuditSink",
    "SqliteEffectJournal",
    "ToolCall",
    "ToolCallSummary",
    "ToolClassification",
    "ToolDeniedException",
    "ToolDispatchGate",
    "ToolCallObserver",
    "ToolManifest",
    "TraceMismatch",
    "TraceRecorder",
    "UnknownEffectPolicy",
    "Verdict",
    "WebhookHook",
    "allow_all",
    "allowed_deciders",
    "classification_of",
    "default_provider",
    "disabled",
    "effect_key",
    "evidence_bundle",
    "governed",
    "governance_report",
    "halt_drill",
    "interrupted_keys",
    "mandate_attach",
    "mandate_from_context",
    "manifest_digest",
    "one_call_per_tool",
    "poison_probe",
    "policy_replay",
    "replay_trace",
    "risk_map",
    "run_guardrails",
    "showback",
    "sign_receipt",
    "slack_message",
    "teams_message",
    "verify_audit_chain",
    "verify_mandate_chain",
    "verify_receipt",
]
