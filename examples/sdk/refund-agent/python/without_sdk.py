"""The support agent the way most tutorials build it: LLM + bare functions.

Nothing stands between the model's intent and the shop backend — no approval,
no idempotency, no spending cap, no record of what ran. Run it, then run
with_sdk.py and compare the money ledger and the audit trail.
"""

from __future__ import annotations

from scenario import STEPS, banner
from shop import Shop

shop = Shop()

TOOLS = {
    "lookup_order": shop.get_order,
    "read_email": shop.get_email,
    "issue_refund": shop.refund,
    "apply_store_credit": shop.store_credit,
}


def dispatch(tool: str, args: dict) -> str:
    print(f"  -> {tool}({args})")
    result = TOOLS[tool](**args)
    print(f"  <- {result}")
    return result


def main() -> None:
    banner("SUPPORT AGENT — NO SDK (model calls tools directly)")
    for step in STEPS:
        if step["kind"] == "call":
            dispatch(step["tool"], step["args"])
        else:
            print(step["text"])

    banner("MONEY MOVED (who approved it? nobody can say)")
    print(shop.money_moved())
    print(
        "\nWhat just happened:"
        "\n  * $9,999.00 was refunded on the strength of text inside a customer email."
        "\n  * The goodwill credit posted twice — the model retried, the effect ran again."
        "\n  * Then the stuck loop posted eight more credits with drifting amounts."
        "\n  * A Slack bot token pasted into the email is now in the model's context."
        "\n  * There is no record of any of it. Ledger entries are all you have."
    )


if __name__ == "__main__":
    main()
