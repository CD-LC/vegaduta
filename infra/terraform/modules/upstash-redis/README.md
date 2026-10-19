# upstash-redis

Not a provisioning module - it declares no providers and creates no
resources. A manually created database is a five-minute console task; this
module exists so the REST URL/token still flow through the same secret path
(e.g. `../gcp-secrets`) as every other credential, instead of living in a
separate README or `.env` file.

To provision: create a database at https://console.upstash.com (region
closest to the rest of your stack), then pass its REST URL and token in via
`TF_VAR_*` environment variables in CI (never a committed `terraform.tfvars`).
The `rest_token` output is marked sensitive.
