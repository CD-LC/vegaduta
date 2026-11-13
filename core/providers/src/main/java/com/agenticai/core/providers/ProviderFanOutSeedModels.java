package com.agenticai.core.providers;

import com.agenticai.core.providers.ProviderModelSeedCatalog.SeedModel;
import java.util.List;
import java.util.Map;

/**
 * The seed models of the providers added in the July 2026 fan-out (Google Vertex, Azure OpenAI, AWS
 * Bedrock, Ollama, Mistral, DeepSeek, Groq, Meta). Reference data only, merged with the hosted-pool
 * providers by {@link ProviderModelSeedCatalog}; it lives in its own file so neither stays near the
 * repository's file-size cap.
 */
final class ProviderFanOutSeedModels {

    private ProviderFanOutSeedModels() {}

    static final Map<ProviderName, List<SeedModel>> MODELS =
            Map.ofEntries(
                    // Provider fan-out (July 2026) - GOOGLE_VERTEX, AZURE_OPENAI,
                    // AWS_BEDROCK, OLLAMA, MISTRAL, DEEPSEEK, GROQ added below.
                    //
                    // GOOGLE_VERTEX: this platform's ProviderName enum has carried this
                    // value since before this fan-out, but as of this pass NO
                    // GoogleVertexProviderValidator/VertexAiChatClientFactory/
                    // VertexCredential exists anywhere in this checkout - GOOGLE_VERTEX
                    // has zero working backend wiring beyond this enum constant and
                    // (per ProvidersPanel.tsx) a "Coming soon" card. This SEED_MODELS
                    // entry was still added on the theory that a non-empty catalog is
                    // harmless even for an unimplemented provider (IMPLEMENTED_PROVIDERS
                    // on the frontend is what actually gates visibility/activation, not
                    // this map) and fixes the specific "empty dropdown" bug this class's
                    // javadoc already calls out - but the model ids below are
                    // Vertex's Gemini-family ids as of this platform's July 2026 build
                    // and have NOT been probed against a real Vertex validator the way
                    // every other entry in this map effectively has been (indirectly,
                    // by that provider's own validator/factory existing and being
                    // exercised) - flag this for re-verification once GOOGLE_VERTEX
                    // actually gets implemented.
                    Map.entry(
                            ProviderName.GOOGLE_VERTEX,
                            List.of(
                                    new SeedModel(
                                            "gemini-2.5-pro",
                                            "Gemini 2.5 Pro (Vertex)",
                                            1_000_000,
                                            true,
                                            true,
                                            true),
                                    new SeedModel(
                                            "gemini-2.5-flash",
                                            "Gemini 2.5 Flash (Vertex)",
                                            1_000_000,
                                            true,
                                            true,
                                            true),
                                    new SeedModel(
                                            "gemini-2.0-flash-001",
                                            "Gemini 2.0 Flash (Vertex)",
                                            1_000_000,
                                            true,
                                            true,
                                            true))),
                    // Azure hosts the same underlying OpenAI model families as OPENAI
                    // above - listed here as a reference/starting point, though in
                    // practice a given Azure OpenAI credential's actual usable model is
                    // fixed by whichever deployment the tenant named in the activation
                    // form (AzureOpenAiCredential#deploymentName), not by this dropdown.
                    Map.entry(
                            ProviderName.AZURE_OPENAI,
                            List.of(
                                    new SeedModel("gpt-5.1", "GPT-5.1", 400_000, true, true, false),
                                    new SeedModel("gpt-4o", "GPT-4o", 128_000, true, true, false),
                                    new SeedModel(
                                            "gpt-4o-mini",
                                            "GPT-4o Mini",
                                            128_000,
                                            true,
                                            true,
                                            false))),
                    // Full Bedrock model ids (region/account model-access-gated - see
                    // AwsBedrockProviderValidator's activation-time check). Anthropic
                    // model ids on Bedrock intentionally mirror the vendor's own
                    // dated-version ids, not ANTHROPIC's plain "claude-sonnet-5" style
                    // above - Bedrock addresses models this way regardless of vendor.
                    Map.entry(
                            ProviderName.AWS_BEDROCK,
                            List.of(
                                    new SeedModel(
                                            "anthropic.claude-3-5-sonnet-20241022-v2:0",
                                            "Claude 3.5 Sonnet v2 (Bedrock)",
                                            200_000,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "anthropic.claude-3-5-haiku-20241022-v1:0",
                                            "Claude 3.5 Haiku (Bedrock)",
                                            200_000,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "amazon.titan-text-premier-v1:0",
                                            "Titan Text Premier (Bedrock)",
                                            32_000,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "meta.llama3-1-70b-instruct-v1:0",
                                            "Llama 3.1 70B Instruct (Bedrock)",
                                            128_000,
                                            false,
                                            false,
                                            false))),
                    // Ollama is a tenant-hosted server, not a fixed cloud catalog - this
                    // is a curated seed of common, widely-pulled model tags as a
                    // starting point for the dropdown, NOT a live read of what any
                    // given tenant's server actually has pulled (there is no universal
                    // "Ollama catalog" to seed the way OpenRouter's public /models
                    // endpoint provides one). A tenant can always type a different tag
                    // their server has - see the free-text fallback every agent's model
                    // field already supports. Refreshed Oct 2026 to mirror the shared
                    // local catalog (local-models/catalog.txt - the tags the installer
                    // and the console's local-model wizard offer), so the dropdown no
                    // longer lists only tags nothing else recommends: gpt-oss and the
                    // DeepSeek R1 distills were the missing entries users asked for.
                    Map.entry(
                            ProviderName.OLLAMA,
                            List.of(
                                    new SeedModel(
                                            "qwen3.5:4b",
                                            "Qwen 3.5 4B",
                                            131_072,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "qwen3.5:9b",
                                            "Qwen 3.5 9B",
                                            131_072,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "qwen3:8b", "Qwen 3 8B", 131_072, false, false, false),
                                    new SeedModel(
                                            "granite4.2:3b",
                                            "Granite 4.2 3B",
                                            128_000,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "gpt-oss:20b",
                                            "GPT-OSS 20B",
                                            131_072,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "gpt-oss:120b",
                                            "GPT-OSS 120B",
                                            131_072,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "deepseek-r1:8b",
                                            "DeepSeek R1 8B",
                                            131_072,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "deepseek-r1:14b",
                                            "DeepSeek R1 14B",
                                            131_072,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "llama3.2:3b",
                                            "Llama 3.2 3B",
                                            131_072,
                                            false,
                                            false,
                                            false),
                                    // Meta's open-weight lines beyond Llama 3.x (Nov 2026): Muse
                                    // Glimmer
                                    // (Apache-2.0, Aug 2026) and Llama 4 Scout, both text + image
                                    // input.
                                    new SeedModel(
                                            "llama3.1:8b",
                                            "Llama 3.1 8B",
                                            131_072,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "llama3.3:70b",
                                            "Llama 3.3 70B",
                                            131_072,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "llama4:16x17b",
                                            "Llama 4 Scout",
                                            131_072,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "muse-glimmer:30b",
                                            "Muse Glimmer 30B",
                                            131_072,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "mistral:7b",
                                            "Mistral 7B",
                                            32_000,
                                            false,
                                            false,
                                            false))),
                    // The -latest aliases are Mistral's own moving pointers and stay; the
                    // pinned current-generation ids (Aug 2026 lineup) sit alongside them
                    // for tenants who want a fixed target.
                    Map.entry(
                            ProviderName.MISTRAL,
                            List.of(
                                    new SeedModel(
                                            "mistral-large-latest",
                                            "Mistral Large",
                                            128_000,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "mistral-small-latest",
                                            "Mistral Small",
                                            128_000,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "mistral-medium-3.5-26-04",
                                            "Mistral Medium 3.5",
                                            128_000,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "mistral-small-4-0-26-03",
                                            "Mistral Small 4.0",
                                            128_000,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "mistral-large-3-25-12",
                                            "Mistral Large 3",
                                            128_000,
                                            true,
                                            false,
                                            false),
                                    new SeedModel(
                                            "open-mistral-nemo",
                                            "Mistral Nemo",
                                            128_000,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "codestral-latest",
                                            "Codestral",
                                            256_000,
                                            false,
                                            false,
                                            false))),
                    // deepseek-chat/deepseek-reasoner (the V3/R1 endpoints) were retired
                    // in favor of the V4 line (Aug 2026 catalog repair, see V182) -
                    // RETIRED_MODEL_REPLACEMENTS below carries the old->new mapping.
                    Map.entry(
                            ProviderName.DEEPSEEK,
                            List.of(
                                    new SeedModel(
                                            "deepseek-v4-flash",
                                            "DeepSeek V4 Flash",
                                            1_000_000,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "deepseek-v4-pro",
                                            "DeepSeek V4 Pro",
                                            1_000_000,
                                            false,
                                            false,
                                            false))),
                    // gemma2-9b-it removed Aug 2026 - deprecated/decommissioned on
                    // GroqCloud (see V182); the two openai/gpt-oss-* ids are Groq-hosted
                    // open-weight models that are part of Groq's current lineup.
                    Map.entry(
                            ProviderName.GROQ,
                            List.of(
                                    new SeedModel(
                                            "llama-3.3-70b-versatile",
                                            "Llama 3.3 70B Versatile (Groq)",
                                            128_000,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "llama-3.1-8b-instant",
                                            "Llama 3.1 8B Instant (Groq)",
                                            128_000,
                                            false,
                                            false,
                                            false),
                                    // Muse Spark Open (Meta's 816B open-weight model, Oct 2026) is
                                    // a Groq
                                    // preview model on enterprise pricing: listed so it can be
                                    // chosen, and
                                    // its id holds none of the hosted pool's auto-pick keywords, so
                                    // auto-selection stays on llama-3.1-8b-instant.
                                    new SeedModel(
                                            "meta-models/muse-spark-open",
                                            "Muse Spark Open (Groq, preview)",
                                            131_072,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "openai/gpt-oss-20b",
                                            "GPT-OSS 20B (Groq)",
                                            128_000,
                                            false,
                                            false,
                                            false),
                                    new SeedModel(
                                            "openai/gpt-oss-120b",
                                            "GPT-OSS 120B (Groq)",
                                            128_000,
                                            false,
                                            false,
                                            false))),
                    // META (Meta Model API, api.meta.ai - added 2026-09-17, facts
                    // verified against dev.meta.ai that day). The Muse Spark chat line:
                    // all three take text, image, video, PDF/file and audio input (audio
                    // is degraded on 1.3) with a 1,048,576-token context window, and all
                    // support tool calling, structured output, streaming and prompt
                    // caching. The "-contributor" ids are the same models at ~1/10th the
                    // price BECAUSE META TRAINS ON THAT TRAFFIC - hence the blunt display
                    // name, and they are never a default (PREFERRED_DEFAULT_MODELS /
                    // AgentService's tier map both pin muse-spark-1.3). muse-image-1.0
                    // (image generation) and muse-voice-transcribe-1.0 (STT) exist on
                    // the same API but are not chat models and are deliberately absent.
                    // Like every other seeded provider, this reaches the DB through
                    // refreshProvider() (daily job + lazy warm on first listModels for a
                    // provider with zero rows) - no Flyway migration is needed to add a
                    // brand-new provider block.
                    Map.entry(
                            ProviderName.META,
                            List.of(
                                    new SeedModel(
                                            "muse-spark-1.3",
                                            "Muse Spark 1.3",
                                            1_048_576,
                                            true,
                                            true,
                                            true),
                                    new SeedModel(
                                            "muse-spark-1.2",
                                            "Muse Spark 1.2",
                                            1_048_576,
                                            true,
                                            true,
                                            true),
                                    new SeedModel(
                                            "muse-spark-1.1",
                                            "Muse Spark 1.1",
                                            1_048_576,
                                            true,
                                            true,
                                            true),
                                    new SeedModel(
                                            "muse-spark-1.3-contributor",
                                            "Muse Spark 1.3 Contributor (Meta trains on your data)",
                                            1_048_576,
                                            true,
                                            true,
                                            true),
                                    new SeedModel(
                                            "muse-spark-1.2-contributor",
                                            "Muse Spark 1.2 Contributor (Meta trains on your data)",
                                            1_048_576,
                                            true,
                                            true,
                                            true))));
}
