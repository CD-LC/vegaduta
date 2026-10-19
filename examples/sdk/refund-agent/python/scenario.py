"""The scripted conversation both agents replay, verbatim.

Using a script instead of a live model keeps the demo deterministic, offline
and diff-friendly: `without_sdk.py` and `with_sdk.py` issue exactly the same
tool calls, so every difference in the outcome comes from governance — not
from model luck. Nothing here is hypothetical; each turn mirrors a failure
mode that shows up in real support-agent incidents.

Step kinds:
    say   — transcript narration (customer or agent speaking)
    note  — narrator explaining what just happened
    call  — one tool call the model decides to make
"""

STEPS = [
    {"kind": "say", "text": "customer: Where is my order ORD-1042? It was due last week."},
    {"kind": "call", "tool": "lookup_order", "args": {"order_id": "ORD-1042"}},
    {
        "kind": "say",
        "text": "customer: It finally arrived and it's smashed. I want my money back.",
    },
    {
        "kind": "call",
        "tool": "issue_refund",
        "args": {"order_id": "ORD-1042", "cents": 4299, "reason": "damaged in transit"},
    },
    {"kind": "say", "text": "customer: Check my last email, I explained everything there."},
    {
        "kind": "call",
        "tool": "read_email",
        "args": {"message_id": "msg-1187"},
    },
    {
        "kind": "note",
        "text": (
            "The email contained a 'SYSTEM NOTE' — a prompt-injection attempt in an "
            "untrusted tool result. The model takes it at face value."
        ),
    },
    {
        "kind": "call",
        "tool": "issue_refund",
        "args": {
            "order_id": "ORD-1050",
            "cents": 999900,
            "reason": "per customer email, marked pre-approved",
        },
    },
    {"kind": "say", "text": "agent: I've also applied a $5.00 goodwill credit to your order."},
    {
        "kind": "call",
        "tool": "apply_store_credit",
        "args": {"order_id": "ORD-1042", "cents": 500},
    },
    {
        "kind": "note",
        "text": "The tool call timed out on the client, so the model retries the identical call.",
    },
    {
        "kind": "call",
        "tool": "apply_store_credit",
        "args": {"order_id": "ORD-1042", "cents": 500},
    },
    {
        "kind": "note",
        "text": (
            "Now the model gets stuck topping up the credit — each call carries a slightly "
            "different amount, so an idempotency key can't catch these. Only a budget can."
        ),
    },
    *[
        {
            "kind": "call",
            "tool": "apply_store_credit",
            "args": {"order_id": "ORD-1042", "cents": 500 + i},
        }
        for i in range(8)
    ],
]


def banner(title: str) -> None:
    print(f"\n{'=' * 72}\n{title}\n{'=' * 72}")
