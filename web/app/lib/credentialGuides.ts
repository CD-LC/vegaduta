// Centralized "how do I get this credential" content for CredentialHelp.js -
// one place to keep these accurate rather than duplicating steps/links
// across ProvidersPanel, ChannelsPanel, McpPanel, KnowledgeCollectionsPanel.
// Agentic AI is new to most people opening this console for the first time;
// a field labeled "Auth Token" or "API Key" means nothing without this.

// One entry per chat-capable provider card on the Providers page. `href` is
// where the KEY is created; `pricingHref` is that provider's own official
// pricing page. Pricing, free-tier limits, trial credits and offers change
// constantly and differ by region/account, so this file deliberately LINKS to
// them and never quotes numbers - the provider's page is the only source that
// is ever current. Omit pricingHref when the provider has no public pricing
// page (e.g. Ollama is free/self-hosted; Meta's Model API pricing isn't
// published as a standalone page).
export interface LlmProviderGuide {
  title: string;
  steps: string[];
  href: string;
  /** Official pricing page for this provider - rendered as a second link in
   * the guide popover and on the provider card. Never a number, always a
   * URL (see the comment above). */
  pricingHref?: string;
  /** Short "good first pick" rationale, shown as a hint on the provider card
   * for someone who has never configured a provider before. About WHY it's a
   * good starting point (free tier exists, one key covers many models), not
   * about specific prices. */
  startHint?: string;
}

export const LLM_PROVIDER_GUIDES: Record<string, LlmProviderGuide> = {
  ANTHROPIC: {
    title: "Get an Anthropic API key",
    steps: [
      "Go to console.anthropic.com and sign in (or create an account).",
      "Open Settings → API Keys in the left sidebar.",
      'Click "Create Key", name it (e.g. "vegaduta"), and copy it immediately - it\'s shown only once.',
      "Paste it into the API key field here and click Activate.",
    ],
    href: "https://console.anthropic.com/settings/keys",
    pricingHref: "https://www.anthropic.com/pricing#anthropic-api",
  },
  OPENAI: {
    title: "Get an OpenAI API key",
    steps: [
      "Go to platform.openai.com and sign in.",
      "Open Settings → API keys.",
      'Click "Create new secret key" and copy it - it\'s shown only once.',
    ],
    href: "https://platform.openai.com/api-keys",
    pricingHref: "https://platform.openai.com/docs/pricing",
  },
  OPENROUTER: {
    title: "Get an OpenRouter API key",
    steps: [
      "Go to openrouter.ai and sign in (or create an account).",
      "Open Settings → Keys.",
      'Click "Create Key", name it, and copy it - it\'s shown only once.',
      "Paste it into the API key field here and click Activate.",
    ],
    href: "https://openrouter.ai/settings/keys",
    pricingHref: "https://openrouter.ai/models",
    startHint:
      "One key, many labs - the easiest way to try several models (including free ones) without juggling accounts.",
  },
  GROK: {
    title: "Get an xAI (Grok) API key",
    steps: [
      "Go to console.x.ai (xAI's developer console) and sign in.",
      "Open the API Keys section.",
      'Click "Create API Key", name it, and copy it - it\'s shown only once.',
    ],
    href: "https://console.x.ai",
    pricingHref: "https://docs.x.ai/docs/models",
  },
  GEMINI: {
    title: "Get a Google Gemini (AI Studio) API key",
    steps: [
      "Go to aistudio.google.com/apikey and sign in with a Google account.",
      'Click "Create API key" (no GCP project or billing setup required to get started).',
      "Copy the key and paste it into the API key field here.",
    ],
    href: "https://aistudio.google.com/apikey",
    pricingHref: "https://ai.google.dev/gemini-api/docs/pricing",
    startHint: "Offers a free tier for getting started - check its pricing page for current limits and terms.",
  },
  AZURE_OPENAI: {
    title: "Get an Azure OpenAI key",
    steps: [
      "In the Azure Portal, open (or create) an Azure OpenAI resource.",
      'Go to "Keys and Endpoint" under Resource Management, and copy KEY 1 (or KEY 2) and the endpoint URL.',
      "In Azure AI Foundry (or Resource Management → Model deployments), find the deployment name you want to use - this is the name YOU gave the deployment, not the underlying model id.",
      "Leave API version blank to use this platform's default, or set one explicitly if your resource needs a different one.",
    ],
    href: "https://portal.azure.com",
    pricingHref: "https://azure.microsoft.com/en-us/pricing/details/cognitive-services/openai-service/",
  },
  GOOGLE_VERTEX: {
    title: "Get Google Vertex AI credentials",
    steps: [
      "In Google Cloud Console, enable the Vertex AI API on your project.",
      "Create a service account under IAM & Admin with the Vertex AI User role.",
      "Create and download a JSON key for that service account.",
    ],
    href: "https://console.cloud.google.com/vertex-ai",
    pricingHref: "https://cloud.google.com/vertex-ai/generative-ai/pricing",
  },
  AWS_BEDROCK: {
    title: "Get AWS Bedrock credentials",
    steps: [
      "In AWS IAM, create (or reuse) a user/role with the AmazonBedrockFullAccess (or AmazonBedrockReadOnly, for validation) policy.",
      "Generate an access key ID and secret access key for it.",
      "Make sure the model you want is enabled under Bedrock → Model access, in the same region you enter below.",
      "When creating an agent, set its model to the full Bedrock model id (e.g. anthropic.claude-3-5-sonnet-20241022-v2:0), not a bare model name.",
    ],
    href: "https://console.aws.amazon.com/bedrock/",
    pricingHref: "https://aws.amazon.com/bedrock/pricing/",
  },
  OLLAMA: {
    title: "Connect a tenant-hosted Ollama server",
    steps: [
      "Install Ollama from ollama.com on the machine/server you want this platform to call, and run `ollama pull <model>`.",
      "That server must be reachable from this platform over the network - a server only listening on localhost/127.0.0.1 on your own machine won't be reachable, since this platform runs elsewhere.",
      "Enter that server's base URL below (e.g. http://your-ollama-host:11434). No API key is required unless you've put your own auth proxy in front of it.",
    ],
    href: "https://ollama.com",
    startHint: "Free and self-hosted - no API bill at all, but you run the model server yourself.",
  },
  LOCAL_OPENAI: {
    title: "Connect a local or self-hosted model server",
    steps: [
      "Start any server that speaks the OpenAI API: vLLM (`vllm serve <model>`, port 8000), llama.cpp (`llama-server -m model.gguf --jinja`, port 8080), LM Studio (Developer → Start Server, port 1234), Jan (port 1337) or LocalAI (port 8080).",
      "Click a preset below to fill in its usual address, or type your own (include the /v1 suffix the server documents).",
      "The platform refuses private and loopback addresses unless its operator allow-lists them: add the host:port shown below to app.security.outbound.allowed-private-hosts. If the platform runs in Docker, use host.docker.internal instead of localhost.",
      "No API key is needed unless the server was started with one (--api-key) or sits behind an auth proxy. Then pick the model from the list on the agent.",
    ],
    href: "https://docs.vllm.ai/en/latest/serving/openai_compatible_server.html",
    startHint: "Runs on hardware you control - no per-token bill, and your prompts stay on servers you run.",
  },
  MISTRAL: {
    title: "Get a Mistral AI API key",
    steps: [
      "Go to console.mistral.ai and sign in (or create an account).",
      'Open API Keys and click "Create new key".',
      "Copy it and paste it into the field below - it's shown only once.",
    ],
    href: "https://console.mistral.ai",
    pricingHref: "https://mistral.ai/pricing",
  },
  DEEPSEEK: {
    title: "Get a DeepSeek API key",
    steps: [
      "Go to platform.deepseek.com and sign in (or create an account).",
      'Open API keys and click "Create new API key".',
      "Copy it and paste it into the field below - it's shown only once.",
    ],
    href: "https://platform.deepseek.com",
    pricingHref: "https://api-docs.deepseek.com/quick_start/pricing",
  },
  GROQ: {
    title: "Get a Groq (GroqCloud) API key",
    steps: [
      "Go to console.groq.com and sign in (or create an account).",
      'Open API Keys and click "Create API Key".',
      "Copy it and paste it into the field below - it's shown only once. Not the same company as xAI's Grok, despite the similar name.",
    ],
    href: "https://console.groq.com/keys",
    pricingHref: "https://groq.com/pricing/",
    startHint: "Offers a free tier - fast open models for experimenting; check its pricing page for current limits.",
  },
  META: {
    title: "Get a Meta Model API key (Muse Spark)",
    steps: [
      "Go to dev.meta.ai and sign in with your Meta account. Sign-up for the Meta Model API is not open in every country yet - a key obtained anywhere works from anywhere.",
      'Open the API keys tab and click "Create API key", name it (e.g. "vegaduta"), and copy it - it looks like LLM|<digits>|<chars> and is shown only once.',
      "Paste it into the field below and click Activate. This unlocks muse-spark-1.3 (recommended), 1.2 and 1.1; the -contributor variants are cheaper because Meta trains on that traffic, so pick them only knowingly.",
      "No Meta key? Muse Spark is also reachable through the OpenRouter provider as meta/muse-spark-1.3.",
    ],
    href: "https://dev.meta.ai/docs/getting-started/authentication/",
  },
  SARVAM: {
    title: "Get a Sarvam AI subscription key",
    steps: [
      "Go to dashboard.sarvam.ai and sign in (or create an account).",
      "Open API Keys and create a new subscription key (starts with sk_...).",
      "Copy it and paste it into the field below - it powers sarvam-30b/sarvam-105b chat plus translation (22 scheduled Indian languages), speech-to-text, and text-to-speech.",
    ],
    href: "https://dashboard.sarvam.ai",
  },
};

export const TWILIO_AUTH_TOKEN_GUIDE = {
  title: "Get your Twilio Account SID & Auth Token",
  steps: [
    "Sign in at twilio.com/console.",
    "Your Account SID and Auth Token are shown right on the console home page.",
    "Click the eye icon to reveal the Auth Token, then copy both values.",
  ],
  href: "https://www.twilio.com/console",
  hrefLabel: "Open Twilio Console",
};

export const TWILIO_API_KEY_GUIDE = {
  title: "Get a Twilio API Key (recommended over Auth Token)",
  steps: [
    "In the Twilio Console, go to Account → API keys & tokens.",
    'Click "Create API key", choose Standard, and name it.',
    "Copy the API Key SID (starts with SK...) and Secret immediately - the secret is shown only once.",
    "API keys can be scoped and revoked individually, unlike the Auth Token which grants full account access.",
  ],
  href: "https://console.twilio.com/us1/account/keys-credentials/api-keys",
  hrefLabel: "Open Twilio API Keys",
};

export const TWILIO_WHATSAPP_NUMBER_GUIDE = {
  title: "Get a Twilio WhatsApp-enabled number",
  steps: [
    "For testing: Twilio Console → Messaging → Try it out → Send a WhatsApp message (uses Twilio's shared sandbox number).",
    "For production: Messaging → Senders → WhatsApp senders, and follow Meta's business verification steps.",
  ],
  href: "https://console.twilio.com/us1/develop/sms/try-it-out/whatsapp-learn",
};

export const META_WHATSAPP_GUIDE = {
  title: "Set up a Meta WhatsApp Cloud API app",
  steps: [
    "Go to developers.facebook.com and create a new App (type: Business).",
    "Add the WhatsApp product to the app.",
    "Under WhatsApp → API Setup, copy the Phone Number ID and WABA ID, and generate a temporary access token (or a permanent System User token under Business Settings).",
    "Under App Settings → Basic, copy the App Secret.",
  ],
  href: "https://developers.facebook.com/docs/whatsapp/cloud-api/get-started",
};

export const META_SOCIAL_GUIDE = {
  title: "Connect a Facebook Page (Messenger + Instagram DMs)",
  steps: [
    "In the same Meta App used for WhatsApp (developers.facebook.com), add the Messenger product and select your Facebook Page.",
    "Generate a Page Access Token for that Page under Messenger → API Setup.",
    "The App Secret is under App Settings → Basic (same one WhatsApp uses).",
    "If the Page has a linked Instagram professional account, its Instagram Business Account ID (also under Messenger → API Setup) is optional - add it to also receive Instagram DMs through the same connection.",
  ],
  href: "https://developers.facebook.com/docs/messenger-platform/get-started",
};

export const SLACK_APP_GUIDE = {
  title: "Get a Slack Bot Token & Signing Secret",
  steps: [
    "Go to api.slack.com/apps and create a new app (From scratch) in your workspace.",
    "Under OAuth & Permissions, add the chat:write, im:history, and channels:history Bot Token Scopes, then install the app to your workspace.",
    "Copy the Bot User OAuth Token (starts with xoxb-) from that same page.",
    "Under Event Subscriptions, turn it on, point the Request URL at this platform's /webhook/slack, and subscribe to the message.im (and/or message.channels) bot event.",
    "Copy the Signing Secret from Basic Information → App Credentials.",
  ],
  href: "https://api.slack.com/apps",
  hrefLabel: "Open your Slack Apps",
};

export const DISCORD_BOT_GUIDE = {
  title: "Get a Discord Bot Token",
  steps: [
    "Go to discord.com/developers/applications and create a New Application.",
    'Under Bot, click Add Bot, then turn on the "Message Content Intent" (required to read ordinary message text).',
    "Click Reset Token (or Copy) to get the Bot Token.",
    "Under OAuth2 → URL Generator, select the bot scope plus Send Messages and Read Message History, then open the generated URL to invite the bot to your server.",
  ],
  href: "https://discord.com/developers/applications",
  hrefLabel: "Open Discord Developer Portal",
};

export const X_APP_GUIDE = {
  title: "Get an X (Twitter) OAuth 2.0 Client ID & Secret",
  steps: [
    "DM access requires at least X's paid Basic API tier - go to developer.x.com and create a Project + App.",
    'Under User authentication settings, turn on OAuth 2.0, set App type to "Confidential client", and request the dm.read, dm.write, tweet.read, users.read, and offline.access scopes.',
    "Add this platform's callback URL as a Callback URI (ask your platform operator for the exact value - it's the same for every tenant).",
    "Copy the OAuth 2.0 Client ID and Client Secret from Keys and tokens.",
  ],
  href: "https://developer.x.com/en/portal/dashboard",
  hrefLabel: "Open X Developer Portal",
};

export const TELEGRAM_BOT_GUIDE = {
  title: "Get a Telegram Bot Token",
  steps: [
    "Open a chat with @BotFather in Telegram (search for it, or t.me/BotFather).",
    "Send /newbot and follow the prompts to name your bot.",
    "BotFather replies with a token that looks like 123456789:ABC-...  - copy it.",
  ],
  href: "https://t.me/BotFather",
  hrefLabel: "Open @BotFather",
};

export const MATRIX_BOT_GUIDE = {
  title: "Get a Matrix bot access token",
  steps: [
    "Create a normal Matrix user for the bot on your homeserver (a separate account, not your own).",
    "Log in as that user in Element and open Settings > Help & About > Access Token, then copy it. Treat it like a password.",
    "Use the homeserver address your client connects to, for example https://matrix.example.org. A homeserver on your own network must be listed by the operator in app.security.outbound.allowed-private-hosts.",
  ],
  href: "https://spec.matrix.org/latest/client-server-api/",
  hrefLabel: "Matrix Client-Server API",
};

export const EMAIL_IMAP_GUIDE = {
  title: "Connect a support mailbox via IMAP",
  steps: [
    "Use the IMAP host/port your mail provider publishes (Gmail: imap.gmail.com, port 993; Outlook: outlook.office365.com, port 993).",
    "If 2-factor authentication is on (recommended), generate an app-specific password rather than using your normal login password.",
    "Use a mailbox that only the agent reads: it is checked every 5 minutes for unread mail, and every unread message is marked read after one attempt, even if the agent failed to answer it (it is not retried). Replies go out through the platform's own outbound relay, not this mailbox's SMTP.",
    "A mail server on a private network (a LAN server, a local bridge) must be listed by the operator in app.security.outbound.allowed-private-hosts.",
  ],
  href: "https://support.google.com/mail/answer/7126229",
  hrefLabel: "Gmail IMAP settings",
};

export const TEAMS_APP_GUIDE = {
  title: "Register an Azure Bot for Microsoft Teams",
  steps: [
    'In the Azure portal, create an "Azure Bot" resource (or reuse an existing Bot registration).',
    "Under the bot's Configuration, set the Messaging endpoint to this platform's /webhook/teams/<your App ID> URL - inbound DMs never arrive until this is set.",
    "Copy the Microsoft App ID from the same page; create a client secret under Certificates & secrets in the linked Entra app registration.",
    "The Entra tenant ID is optional - leave it blank for the usual multi-tenant bot (the platform defaults to botframework.com).",
  ],
  href: "https://portal.azure.com/#blade/HubsExtension/BrowseResource/resourceType/Microsoft.BotService%2FBotServices",
  hrefLabel: "Open Azure Bot registrations",
};

export const MCP_SERVER_GUIDE = {
  title: "What's an MCP server?",
  steps: [
    "MCP (Model Context Protocol) is Anthropic's open standard for giving an AI agent extra tools - like a plugin.",
    "Pick a server from the catalog below (each card already has the right values filled in), or add a custom one if you're running your own.",
    "Once enabled, an agent with tool access turned on can call anything that server exposes - no code required on your side.",
  ],
  href: "https://modelcontextprotocol.io/introduction",
  hrefLabel: "Learn about MCP",
};

export const KNOWLEDGE_BASE_GUIDE = {
  title: "What goes in a knowledge base?",
  steps: [
    "Upload .md, .pdf, .docx, images, audio, or video files - each gets split into searchable chunks automatically.",
    'Group related files into a Collection (e.g. "Product FAQs", "Support Policies") so you can scope which agents can search which collection.',
    "An agent with Knowledge Base access turned on will automatically search relevant collections when answering questions.",
  ],
};

// MCP server credential guides - specific instructions for obtaining credentials
// for third-party MCP servers that agents can connect to
export const MCP_CREDENTIAL_GUIDES: Record<string, any> = {
  atlassian: {
    title: "Get an Atlassian API token",
    steps: [
      "Go to id.atlassian.com/manage-profile/security/api-tokens in your browser.",
      'Click "Create API token" to generate a new token.',
      'Name it (e.g. "vegaduta") and copy the token immediately - it\'s shown only once.',
      "Add a credential named Authorization with value: Bearer <your-token>",
      "Verify your Atlassian host URL (e.g., https://yourcompany.atlassian.net) is correct in the server configuration.",
    ],
    href: "https://id.atlassian.com/manage-profile/security/api-tokens",
    hrefLabel: "Create Atlassian API token",
    fields: [
      {
        name: "Authorization",
        description: "Bearer <token from id.atlassian.com>",
        secret: true,
        required: true,
      },
    ],
  },
  github: {
    title: "Get a GitHub Personal Access Token",
    steps: [
      "Go to github.com/settings/tokens in your browser.",
      'Click "Generate new token" (choose "Fine-grained personal access token" for more control).',
      "Give it a name, set expiration, select required scopes (repo, gist, workflow, etc.), and click Generate.",
      "Copy the token immediately - it's shown only once.",
      "Add a credential named Authorization with value: Bearer <your-token> (or token <your-token> depending on the MCP server).",
    ],
    href: "https://github.com/settings/tokens",
    hrefLabel: "Create GitHub token",
    fields: [
      {
        name: "Authorization",
        description: "Bearer <your-token> (or token <your-token>, depending on the MCP server)",
        secret: true,
        required: true,
      },
    ],
  },
  gitlab: {
    title: "Get a GitLab Personal Access Token",
    steps: [
      "Go to gitlab.com/-/user_settings/personal_access_tokens (or your self-hosted GitLab instance).",
      'Click "Add new token" and name it (e.g. "vegaduta").',
      "Select scopes (api, read_api, write_repository, etc.) and click Create token.",
      "Copy the token immediately - it's shown only once.",
      "Add a credential named Authorization with value: Bearer <your-token>",
    ],
    href: "https://gitlab.com/-/user_settings/personal_access_tokens",
    hrefLabel: "Create GitLab token",
    fields: [
      {
        name: "Authorization",
        description: "Bearer <your-token>",
        secret: true,
        required: true,
      },
    ],
  },
  postgres: {
    title: "Connect a PostgreSQL database",
    steps: [
      "Ensure your PostgreSQL database is reachable over the network from this platform.",
      "Create a database user with the permissions needed (SELECT, INSERT, UPDATE, DELETE as appropriate).",
      "Add credentials: DATABASE_URL with value: postgresql://user:password@host:5432/database",
      "Or add individual credentials: DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, DB_NAME",
    ],
    fields: [
      {
        name: "DATABASE_URL",
        description: "postgresql://user:password@host:5432/database",
        secret: true,
        required: true,
      },
    ],
  },
  slack: {
    title: "Connect a Slack workspace",
    steps: [
      "Go to api.slack.com/apps and create a new app (From scratch) in your workspace.",
      "Under OAuth & Permissions, install the app to your workspace.",
      "Copy the Bot User OAuth Token (starts with xoxb-) from that page.",
      "Add a credential named SLACK_BOT_TOKEN with this value.",
    ],
    href: "https://api.slack.com/apps",
    hrefLabel: "Open Slack API dashboard",
    fields: [
      {
        name: "SLACK_BOT_TOKEN",
        description: "Bot User OAuth Token, starts with xoxb-",
        secret: true,
        required: true,
      },
    ],
  },
  notion: {
    title: "Get a Notion Integration Token",
    steps: [
      "Go to notion.so/my-integrations and create a new internal integration.",
      'Name it (e.g. "vegaduta") and copy the Internal Integration Token.',
      "Go to each Notion page/database you want this integration to access, and in Share settings, add your integration.",
      "Add a credential named NOTION_API_KEY with the token value.",
    ],
    href: "https://www.notion.so/my-integrations",
    hrefLabel: "Create Notion integration",
    fields: [
      {
        name: "NOTION_API_KEY",
        description: "Internal Integration Token from notion.so/my-integrations",
        secret: true,
        required: true,
      },
    ],
  },
  brave: {
    title: "Get a Brave Search API key",
    steps: [
      "Go to api.search.brave.com and sign up for an API key.",
      "Copy your API key from the dashboard.",
      "Add a credential named BRAVE_API_KEY with this value.",
    ],
    href: "https://api.search.brave.com",
    hrefLabel: "Get Brave Search API key",
    fields: [
      {
        name: "BRAVE_API_KEY",
        description: "API key from your Brave Search dashboard",
        secret: true,
        required: true,
      },
    ],
  },
};

export interface McpCredentialGuideField {
  name: string;
  description?: string;
  secret?: boolean;
  required?: boolean;
}

// Fuzzy name -> guide lookup, shared by every first-contact credential form
// (tools/credentials/page.tsx's after-the-fact editor, and McpPanel.tsx's
// CatalogConnectForm/EnableConfirmCard - the actual "type your credential
// here" forms a user hits first when enabling a server). One normalization
// rule everywhere: lowercase, strip non-alphanumerics, then a two-way
// substring match against MCP_CREDENTIAL_GUIDES' keys - "atlassian-mcp"
// matches "atlassian", "GitHub MCP Server" matches "github", etc.
export function credentialGuideFor(serverName: string | null | undefined) {
  const normalized = (serverName || "").toLowerCase().replace(/[^a-z0-9]/g, "");
  if (!normalized) return undefined;
  return Object.entries(MCP_CREDENTIAL_GUIDES).find(
    ([key]) => normalized.includes(key) || key.includes(normalized)
  )?.[1];
}
