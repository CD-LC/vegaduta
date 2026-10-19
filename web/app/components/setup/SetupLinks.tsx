"use client";

// Setup pointers: one place that knows where each integration is configured,
// so every surface (agent workbench, workflow editor, Edge console) links to
// the same routes instead of hand-writing hrefs. Strings are plain English on
// purpose - the i18n en.json file is fragile (see web-i18n memory).

import Link from "next/link";
import { ArrowRight, Plug } from "lucide-react";
import { cn } from "../../lib/utils";

export type SetupTarget =
  | "providers"
  | "edge"
  | "tools"
  | "mcp"
  | "secrets"
  | "channels"
  | "knowledge"
  | "integrations"
  | "agents"
  | "workflows";

export const SETUP_TARGETS: Record<SetupTarget, { href: string; label: string; help: string }> = {
  providers: { href: "/providers", label: "LLM providers", help: "Add and activate an API key (OpenAI, Anthropic, Gemini, and more) so the agent can answer." },
  edge: { href: "/edge", label: "Edge (on-device AI)", help: "Download an on-device model so browser chat can run locally and privately." },
  tools: { href: "/tools", label: "Tools", help: "Pick which built-in tools the agent may call." },
  mcp: { href: "/tools/servers", label: "MCP servers", help: "Connect external tool servers and grant them to agents." },
  secrets: { href: "/secrets", label: "Secrets", help: "Store credentials once, then reference them as {{secret.NAME}}." },
  channels: { href: "/admin/channels", label: "Channels", help: "Deploy the agent to WhatsApp, web chat, voice and more." },
  knowledge: { href: "/knowledge", label: "Knowledge", help: "Upload documents so the agent can answer from your own content." },
  integrations: { href: "/integrations", label: "Integrations catalog", help: "Browse every app and service VegaDuta connects to." },
  agents: { href: "/agents", label: "Agent workbench", help: "Create and tune the agents your workflows call." },
  workflows: { href: "/workflows", label: "Workflows", help: "Chain agents, tools and approvals into automations." },
};

const LINK_CLASS = "font-medium text-primary underline-offset-4 hover:underline";

/** Inline "Set up: A · B" line. `newTab` keeps unsaved editor state (workflow canvas) intact. */
export function SetupHint({
  targets,
  lead = "Set up:",
  newTab = false,
  className,
}: {
  targets: SetupTarget[];
  lead?: string;
  newTab?: boolean;
  className?: string;
}) {
  return (
    <p className={cn("m-0 text-[11px] leading-relaxed text-muted-foreground", className)}>
      {lead}{" "}
      {targets.map((k, i) => (
        <span key={k}>
          {i > 0 && " · "}
          <Link
            href={SETUP_TARGETS[k].href}
            className={LINK_CLASS}
            title={SETUP_TARGETS[k].help}
            {...(newTab ? { target: "_blank", rel: "noreferrer" } : {})}
          >
            {SETUP_TARGETS[k].label}
          </Link>
        </span>
      ))}
    </p>
  );
}

/** Collapsible step-by-step guide: what to configure, in order, with a link for each. */
export function SetupGuideCard({
  title = "Integration setup guide",
  steps,
  newTab = false,
  className,
}: {
  title?: string;
  steps: SetupTarget[];
  newTab?: boolean;
  className?: string;
}) {
  return (
    <details className={cn("group rounded-lg border border-border bg-secondary/20 px-3.5 py-2.5", className)}>
      <summary className="flex cursor-pointer list-none items-center gap-2 text-sm font-medium text-foreground">
        <Plug size={14} className="text-primary" />
        {title}
        <span className="ml-auto text-xs font-normal text-muted-foreground group-open:hidden">Show</span>
      </summary>
      <ol className="mb-0 mt-2.5 space-y-1.5 pl-0">
        {steps.map((k, i) => (
          <li key={k} className="flex items-start gap-2 text-xs text-muted-foreground">
            <span className="mt-px flex size-4 shrink-0 items-center justify-center rounded-full bg-primary/10 text-[10px] font-semibold text-primary">
              {i + 1}
            </span>
            <span>
              <Link
                href={SETUP_TARGETS[k].href}
                className={LINK_CLASS}
                {...(newTab ? { target: "_blank", rel: "noreferrer" } : {})}
              >
                {SETUP_TARGETS[k].label} <ArrowRight size={10} className="inline" />
              </Link>{" "}
              {SETUP_TARGETS[k].help}
            </span>
          </li>
        ))}
      </ol>
    </details>
  );
}
