# Deliberately does not provision the Upstash database itself - create it in
# the Upstash console (region near the rest of the stack), then pass its REST
# URL/token in here so they flow through the same secret wiring (e.g.
# gcp-secrets) as every other credential. The upstash/upstash provider could
# replace this pass-through if Terraform-managed provisioning is wanted.

output "rest_url" {
  value = var.rest_url
}

output "rest_token" {
  value     = var.rest_token
  sensitive = true
}
