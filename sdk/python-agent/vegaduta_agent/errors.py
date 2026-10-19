"""Exceptions raised by the governance layer.

Mirrors ``io.vegaduta.agent.api.GovernanceException`` and its subclasses. All
of them are fail-closed signals: the tool call did not run, and the caller
(the model loop, a UI, a scheduler) must decide what to tell the user.
"""

from __future__ import annotations


class GovernanceException(Exception):
    """Base class for every governance failure."""


class ToolDeniedException(GovernanceException):
    """The dispatch gate denied this tool call outright."""


class BudgetExceededException(GovernanceException):
    """The run's budget could not reserve the estimated charge."""


class GuardrailViolationException(GovernanceException):
    """A guardrail with action BLOCK rejected the content."""


class ApprovalNotFoundException(GovernanceException):
    """No approval request exists for the given id."""


class ApprovalAlreadyDecidedException(GovernanceException):
    """The approval request was already approved, rejected or expired."""


class ApprovalNotAuthorizedException(GovernanceException):
    """The decider is not allowed to decide this request (when an
    ``ApprovalDecisionAuthorizer`` is installed)."""
