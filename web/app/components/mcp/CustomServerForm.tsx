"use client";

import { useState, type FormEvent } from "react";
import { X } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";
import AskAiHelper from "../AskAiHelper";
import CredentialHelp from "../CredentialHelp";
import Select from "../Select";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Textarea } from "../ui/textarea";
import { MCP_SERVER_GUIDE } from "../../lib/credentialGuides";
import McpRemoteOAuthSection from "../McpPanel/McpRemoteOAuthSection";
import { parseArgs, parseEnv } from "./mcpForm";

/**
 * The "Register a custom server" form. Command/args/env are only collected for STDIO (SSE and
 * STREAMABLE_HTTP are a name-reference or a remote URL), since DynamicMcpConnectionManager only
 * spawns a subprocess for STDIO.
 */
export default function CustomServerForm({
  catalogCount,
  onClose,
  onChanged,
  setError,
  setSuccess,
  setRegisterWarnings,
  noteRegisterResponse,
  scheduleAutoTestRefresh,
}: {
  catalogCount: number;
  onClose: () => void;
  onChanged: () => void;
  setError: (m: string | null) => void;
  setSuccess: (m: string | null) => void;
  setRegisterWarnings: (w: string[]) => void;
  noteRegisterResponse: (res: any, baseMessage: string) => string;
  scheduleAutoTestRefresh: () => void;
}) {
  const t = useTranslations("mcpPanel");
  const [name, setName] = useState("");
  const [type, setType] = useState("STDIO");
  const [description, setDescription] = useState("");
  const [command, setCommand] = useState("");
  const [argsText, setArgsText] = useState("");
  const [envText, setEnvText] = useState("");
  const [url, setUrl] = useState("");
  const [busy, setBusy] = useState(false);

  async function handleRegister(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    setRegisterWarnings([]);
    if (!name.trim()) {
      setError(t("serverNameRequired"));
      return;
    }
    setBusy(true);
    const connectingLive = (type === "STDIO" && command.trim()) || (type !== "STDIO" && url.trim());
    try {
      const payload: any = { name, type, description, enabled: true };
      if (type === "STDIO" && command.trim()) {
        payload.command = command.trim();
        payload.args = parseArgs(argsText);
        payload.env = parseEnv(envText);
      } else if (type !== "STDIO" && url.trim()) {
        payload.url = url.trim();
      }
      const res = await api.registerMcpServer(payload);
      setSuccess(
        noteRegisterResponse(
          res,
          connectingLive ? t("registeredConnectingSuccess", { name }) : t("registeredEnabledSuccess", { name })
        )
      );
      setName("");
      setDescription("");
      setCommand("");
      setArgsText("");
      setEnvText("");
      setUrl("");
      onChanged();
      if (connectingLive) scheduleAutoTestRefresh();
    } catch (err) {
      setError(errorMessage(err, t("registerServerError")));
    } finally {
      setBusy(false);
    }
  }

  // "Connect with OAuth" (McpRemoteOAuthSection): the server is saved FIRST so the backend callback has a registration
  // to attach the token to. Same upsert-by-name call the Register button makes; the form is left as typed.
  async function registerForOAuth(): Promise<boolean> {
    setError(null);
    setSuccess(null);
    if (!name.trim()) {
      setError(t("serverNameRequired"));
      return false;
    }
    try {
      await api.registerMcpServer({ name, type, description, enabled: true, url: url.trim() });
      onChanged();
      return true;
    } catch (err) {
      setError(errorMessage(err, t("registerServerError")));
      return false;
    }
  }

  return (
    <Card>
      <CardContent className="space-y-3 pt-6">
        <div className="flex items-center justify-between gap-2">
          <span className="flex items-center gap-2 text-sm font-semibold">
            {t("registerCustomServer")} <CredentialHelp {...MCP_SERVER_GUIDE} />
          </span>
          <Button type="button" variant="ghost" size="icon" onClick={onClose} aria-label={t("closeAriaLabel")}>
            <X aria-hidden="true" size={15} />
          </Button>
        </div>
        <p className="text-xs text-muted-foreground">
          {t("customServerNotePrefix", { count: catalogCount })}
          <code>core</code>
          {t("customServerNoteMiddle")}
          <code>mcp</code>
          {t("customServerNoteSuffix")}
        </p>
        <form onSubmit={handleRegister} className="flex flex-wrap items-start gap-2">
          <div className="min-w-[160px] flex-1 space-y-1">
            <Label htmlFor="mcp-server-name-input" className="sr-only">
              {t("namePlaceholder")}
            </Label>
            <Input
              id="mcp-server-name-input"
              type="text"
              placeholder={t("namePlaceholder")}
              value={name}
              onChange={(e) => setName(e.target.value)}
              aria-describedby="mcp-server-name-help"
              required
            />
            <p id="mcp-server-name-help" className="text-xs text-muted-foreground">
              The name agents and teammates will see for this server.
            </p>
          </div>
          <Select
            className="select-inline"
            data-testid="mcp-server-type-select"
            value={type}
            onChange={(e) => setType(e.target.value)}
            options={[
              { value: "STDIO", label: "STDIO" },
              { value: "SSE", label: "SSE (deprecated)" },
              { value: "STREAMABLE_HTTP", label: "STREAMABLE_HTTP" },
            ]}
          />
          <Input
            type="text"
            placeholder={t("descriptionPlaceholder")}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="min-w-[220px] flex-1"
          />
          <AskAiHelper
            fieldLabel={t("mcpServerDescriptionFieldLabel")}
            currentValue={description}
            placeholder={t("mcpServerDescriptionExamplePlaceholder")}
            onApply={setDescription}
          />
          <Button type="submit" size="sm" disabled={busy}>
            {busy ? t("registeringEllipsis") : t("registerButton")}
          </Button>
        </form>
        {type !== "STDIO" && (
          <div className="flex flex-col gap-2">
            <p className="text-xs text-muted-foreground">
              Leave URL blank to just register a name-reference to something already wired in{" "}
              <code>application-mcp.yml</code>. Fill it in to connect this remote server live, scoped to your
              organization. Header credentials (e.g. Authorization: Bearer &lt;token&gt;) are added in the enable step;
              they are encrypted at rest and sent only to this server.
              {type === "SSE" && " SSE is deprecated in the MCP spec: prefer STREAMABLE_HTTP."}
            </p>
            <Input
              type="text"
              placeholder="URL (e.g. https://mcp.example.com/mcp)"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
            />
            <McpRemoteOAuthSection url={url} ensureRegistered={registerForOAuth} onChanged={onChanged} />
          </div>
        )}
        {type === "STDIO" && (
          <div className="flex flex-col gap-2">
            <p className="text-xs text-muted-foreground">
              {t("optionalCommandNotePrefix")}
              <code>application-mcp.yml</code>
              {t("optionalCommandNoteSuffix")} Environment entries below are what the server process needs (API keys
              etc.) — they&apos;re encrypted at rest and sent only to this server.
            </p>
            <Input
              type="text"
              placeholder={t("commandPlaceholder")}
              value={command}
              onChange={(e) => setCommand(e.target.value)}
            />
            <Textarea
              className="resize-y"
              placeholder={t("argsPlaceholder")}
              value={argsText}
              onChange={(e) => setArgsText(e.target.value)}
              rows={3}
            />
            <Textarea
              className="resize-y"
              placeholder={t("envPlaceholder")}
              value={envText}
              onChange={(e) => setEnvText(e.target.value)}
              rows={3}
            />
          </div>
        )}
      </CardContent>
    </Card>
  );
}
