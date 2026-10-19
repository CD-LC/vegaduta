-- Support platform-hosted (FREE-tier) LLM providers whose OpenAI-compatible
-- chat-completions endpoint does NOT follow the conventional
-- "{base_url}/v1/chat/completions" shape that Spring AI's OpenAiApi assumes by
-- default. The canonical case is Google Gemini's OpenAI-compat surface, whose
-- full endpoint is
--   https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
-- - note there is NO bare "/v1" segment, so the default completions path (and
-- the /v1-stripping normalizeBaseUrl) are both wrong for it.
--
-- When completions_path is non-null, HostedLlmChatClientFactory sets
-- OpenAiApi.builder().completionsPath(...) AND uses base_url verbatim (skipping
-- the /v1-stripping normalization). When null, every existing row keeps
-- behaving exactly as before: default "/v1/chat/completions" + normalization.
-- Nullable so every already-configured provider (Groq, Together AI, ...)
-- continues to work with no admin action required.
--
-- control_plane-schema-qualified, same as the platform_hosted_llm_provider
-- table it extends (see V58 / V85).
ALTER TABLE control_plane.platform_hosted_llm_provider
    ADD COLUMN completions_path VARCHAR(255);
