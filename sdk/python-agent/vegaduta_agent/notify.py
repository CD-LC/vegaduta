"""Approval notifications: formatted messages a reviewer can act on.

Mirrors ``agent-core``'s ``approval`` notification pieces (``MessageFormat``,
``SlackMessageFormat``, ``TeamsMessageFormat``). The package builds the
payload; delivery is a port — pass the dict to your own HTTP call
(``WebhookHook`` shows the same pattern; the SDK itself imports no HTTP
client). Typical wiring::

    g = Governance(on_approval_requested=lambda req: post(
        "https://hooks.slack.com/...", slack_message(req)))

A message names the action, the requester, the run and the approval id — the
``approve(id, decided_by)`` call the reviewer makes stays on your side.
"""

from __future__ import annotations

from .approval import ApprovalRequest


def _summary_lines(request: ApprovalRequest) -> list[str]:
    req = request
    lines = [
        f"*Approval needed:* {req.summary}",
        f"- action: `{req.action_type}`",
        f"- requested by: {req.requested_by}",
        f"- run: `{req.context.run_id}`",
    ]
    if req.context.agent_id:
        lines.append(f"- agent: `{req.context.agent_id}`")
    if req.context.tenant_id:
        lines.append(f"- tenant: `{req.context.tenant_id}`")
    lines += [
        f"- approval id: `{req.id}`",
        f"- expires: {req.expires_at.isoformat()}",
    ]
    return lines


def slack_message(request: ApprovalRequest) -> dict:
    """A Slack incoming-webhook / chat.postMessage payload."""
    return {
        "text": f"Approval needed: {request.summary} (id {request.id})",
        "blocks": [
            {
                "type": "section",
                "text": {"type": "mrkdwn", "text": "\n".join(_summary_lines(request))},
            }
        ],
    }


def teams_message(request: ApprovalRequest) -> dict:
    """A Teams-style payload (Power Automate / webhook connector)."""
    return {
        "@type": "MessageCard",
        "@context": "https://schema.org/extensions",
        "summary": f"Approval needed: {request.summary}",
        "themeColor": "E59400",
        "title": f"Approval needed: {request.action_type}",
        "text": "<br>".join(
            line.replace("*", "**") for line in _summary_lines(request)
        ),
    }
