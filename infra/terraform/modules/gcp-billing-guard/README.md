# gcp-billing-guard

The honest starting point: **Google Cloud billing budgets are notifications,
not a hard spend cap.** Nothing in GCP stops resources from running just
because a budget threshold is crossed — the only true "everything stops"
mechanism is unlinking billing from the project entirely, and that takes
down Cloud SQL and every other service in the same instant, data-loss risk
included. This module is the safer middle ground.

## What actually happens, in order

1. **50% / 90% / 100% of expected spend** → email to `var.alert_emails`
   only. Nothing automated happens. This is the tier you're expected to
   actually react to.
2. **150% of expected spend (default `soft_kill_threshold_ratio`)** → a
   Cloud Function (`function-src/main.py`) sets `max-instance-count=0` on
   every service in `var.guarded_cloud_run_services`. Existing instances
   drain, no new ones start, new variable Cloud Run spend stops within
   minutes. **Cloud SQL, Secret Manager, and everything else is never
   touched** — no data-loss risk, and nothing needs a manual "undo" click.

## Recovering after a soft-kill fires

Once the underlying cause (a bug, a traffic spike, a misconfigured retry
loop) is understood and fixed, re-run `terraform apply` — every service's
real `max-instance-count` is defined in your root module's gcp-compute-run
calls, so a normal apply restores it.
The function never becomes a new source of truth; it only ever pushes a
value away from what Terraform last applied, and Terraform always wins the
next time it runs.

## What this deliberately does NOT do

- **Does not disable billing.** That's still a real, manual, last-resort
  option — but it's not wired to fire automatically, because
  taking down Cloud SQL mid-write on a false alarm is a worse outcome than
  an extra hour of an already-elevated bill.
- **Does not cap Gemini/LLM spend specifically.** The hosted LLM pool's
  budget field is informational-only and this module doesn't change that — it only guards GCP-side Cloud Run spend. The
  closest things to an LLM spend cap today are the pool's `priority`
  ordering (free-tier key first) and `FreeTierUsageLimiterService`'s
  per-tenant message/token ceiling, both already in the app.
- **Is not a substitute for setting `budget_amount_usd` correctly.** It
  should track your current cost estimate, not an arbitrary
  round number — a budget set too high makes every alert late, one set too
  low makes them noise you learn to ignore.
