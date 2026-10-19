"use client";

import { Badge } from "../ui/badge";
import { Switch as SharedSwitch } from "../ui/switch";

export interface OpenApiToolSource {
  id: string;
  name: string;
  toolPrefix: string;
  specUrl?: string | null;
  specTitle?: string | null;
  specVersion?: string | null;
  baseUrl: string;
  authType: "NONE" | "API_KEY_HEADER" | "BEARER" | "OAUTH2_CLIENT_CREDENTIALS";
  authHeaderName?: string | null;
  // OAuth2 client-credentials (Availity, Optum/UHC and friends) - all
  // non-secret; the client secret travels in `secret`/hasSecret as before.
  oauth2TokenUrl?: string | null;
  oauth2ClientId?: string | null;
  oauth2Scope?: string | null;
  oauth2TokenStyle?: string | null;
  hasSecret: boolean;
  secretPreview?: string | null;
  enabled: boolean;
  toolCount: number;
  enabledToolCount: number;
  lastRefreshedAt?: string | null;
  lastError?: string | null;
}

export interface OpenApiTool {
  id: string;
  sourceId: string;
  toolName: string;
  operationKey: string;
  operationId?: string | null;
  httpMethod: string;
  pathTemplate: string;
  summary?: string | null;
  description?: string | null;
  bodyMode: string;
  inputSchemaJson?: string | null;
  enabled: boolean;
  deprecated: boolean;
}

export interface PreviewOperation {
  operationKey: string;
  suggestedToolName: string;
  operationId?: string | null;
  method: string;
  path: string;
  summary?: string | null;
  description?: string | null;
  deprecated: boolean;
  bodyMode: string;
  inputSchemaJson?: string | null;
}

export interface Preview {
  title?: string | null;
  version?: string | null;
  description?: string | null;
  serverUrl?: string | null;
  suggestedBaseUrl?: string | null;
  toolPrefix: string;
  operations: PreviewOperation[];
}

export type AuthType = OpenApiToolSource["authType"];

export const EMPTY_SOURCE_FORM = {
  name: "",
  toolPrefix: "",
  mode: "url" as "url" | "paste",
  specUrl: "",
  specText: "",
};
export const EMPTY_SETTINGS = {
  baseUrl: "",
  authType: "NONE" as AuthType,
  authHeaderName: "X-API-Key",
  secret: "",
  oauth2TokenUrl: "",
  oauth2ClientId: "",
  oauth2Scope: "",
  oauth2TokenStyle: "FORM",
};

// Same classes as ui/input.tsx so a native <select> sits flush with the
// Inputs around it (there is no shadcn Select in components/ui).
export const SELECT_CLASS =
  "flex h-10 w-full min-w-0 rounded-md border border-border bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50";

// Thin wrapper around the shared ui/switch.tsx primitive keeping this
// file's existing checked/disabled/label/onToggle call-site API, so every
// call site below converges on the canonical Switch (role=switch, tokens,
// focus ring) instead of a hand-rolled 44x24 button.
export function Switch({
  checked,
  disabled,
  label,
  onToggle,
}: {
  checked: boolean;
  disabled?: boolean;
  label: string;
  onToggle: () => void;
}) {
  return <SharedSwitch checked={checked} disabled={disabled} aria-label={label} onCheckedChange={onToggle} />;
}

export function MethodBadge({ method }: { method: string }) {
  return (
    <Badge variant="outline" className="w-16 justify-center font-mono text-[0.6875rem] uppercase">
      {method}
    </Badge>
  );
}

export function formatWhen(iso?: string | null) {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d.toLocaleString();
}
