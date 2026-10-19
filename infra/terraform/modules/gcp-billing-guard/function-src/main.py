"""Soft-kill emergency brake for a runaway GCP bill.

Triggered by every Cloud Billing budget notification (fires on each
threshold crossing AND periodically as spend is recalculated - not just
once). Does nothing unless spend has crossed the hard soft-kill ratio
(default 150% of expected budget, see variables.tf) - the 50/90/100%
alerts are handled separately by email notification channels, this
function only acts at the emergency threshold.

Deliberately does NOT disable billing or touch Cloud SQL. It sets
max-instance-count=0 on the guarded Cloud Run services, which stops new
variable compute spend (existing instances drain and are not replaced)
without any data-loss risk and without needing to be manually undone in a
panic - once the underlying cost spike is understood and fixed, re-running
`terraform apply` restores every service's real max-instance-count from
your root module's gcp-compute-run calls, since that is still the source of truth
Terraform's state expects. This function only ever moves a value AWAY from
what Terraform last applied; it never becomes a new permanent source of
truth itself.
"""

import base64
import json
import os

import google.auth
import google.auth.transport.requests
import functions_framework

PROJECT_ID = os.environ["PROJECT_ID"]
REGION = os.environ["REGION"]
GUARDED_SERVICES = os.environ["GUARDED_SERVICES"].split(",")
KILL_RATIO = float(os.environ["KILL_RATIO"])

_credentials, _ = google.auth.default()
_session = google.auth.transport.requests.AuthorizedSession(_credentials)


@functions_framework.cloud_event
def soft_kill_on_budget_breach(cloud_event):
    envelope = json.loads(base64.b64decode(cloud_event.data["message"]["data"]))

    cost = float(envelope.get("costAmount", 0))
    budget = float(envelope.get("budgetAmount", 0))
    if budget <= 0:
        print(f"No budget amount in notification, skipping: {envelope}")
        return

    ratio = cost / budget
    print(f"Budget check: spend=${cost:.2f} budget=${budget:.2f} ratio={ratio:.2f} kill_ratio={KILL_RATIO}")

    if ratio < KILL_RATIO:
        return  # below the emergency threshold - the 50/90/100% email alerts already fired separately

    print(f"SOFT KILL TRIGGERED: spend is {ratio:.0%} of budget (threshold {KILL_RATIO:.0%}). "
          f"Zeroing max-instance-count on: {GUARDED_SERVICES}")

    for service in GUARDED_SERVICES:
        _zero_out_service(service)


def _zero_out_service(service_name: str) -> None:
    url = (
        f"https://run.googleapis.com/v2/projects/{PROJECT_ID}/locations/{REGION}"
        f"/services/{service_name}?updateMask=template.scaling.maxInstanceCount"
    )
    body = {"template": {"scaling": {"maxInstanceCount": 0}}}
    resp = _session.patch(url, json=body)
    if resp.status_code >= 300:
        print(f"Failed to zero out {service_name}: {resp.status_code} {resp.text}")
    else:
        print(f"Zeroed max-instance-count for {service_name}")
