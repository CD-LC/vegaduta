output "gateway_id" {
  value = cloudflare_ai_gateway.this.id
}

# Base URL prefix - append /{provider-slug}/{original-path} to route a call
# through the gateway, e.g. .../openai/chat/completions. Use it as the
# base_url of each hosted LLM provider pool row that should be proxied.
output "gateway_base_url" {
  value = "https://gateway.ai.cloudflare.com/v1/${var.account_id}/${cloudflare_ai_gateway.this.id}"
}
