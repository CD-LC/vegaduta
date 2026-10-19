"use client";

import { useState } from "react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { parseStoredArgs } from "../McpPanel/helpers";
import { parseArgs, parseEnv } from "./mcpForm";

/** Inline edit of a registered server (re-registers under the SAME name: McpRegistryService#register upserts). */
export function useMcpServerEdit(
  setError: (m: string | null) => void,
  setSuccess: (m: string | null) => void,
  onChanged: () => void
) {
  const t = useTranslations("mcpPanel");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editCommand, setEditCommand] = useState("");
  const [editArgsText, setEditArgsText] = useState("");
  const [editEnvText, setEditEnvText] = useState("");
  const [editUrl, setEditUrl] = useState("");
  const [editBusy, setEditBusy] = useState(false);
  // Opens the inline edit form pre-filled from the server's own (non-secret)
  // command/args/url - env is deliberately left blank (McpServerRegistrationResponse
  // never carries decrypted secrets back to the browser - see its javadoc - so
  // there is nothing to pre-fill there even if it would be convenient).
  function startEditServer(server: any) {
    setError(null);
    setSuccess(null);
    setEditingId(server.id);
    setEditCommand(server.command || "");
    setEditArgsText(parseStoredArgs(server.args).join("\n"));
    setEditEnvText("");
    setEditUrl(server.url || "");
  }

  function cancelEditServer() {
    setEditingId(null);
  }

  async function submitEditServer(server: any, e: any) {
    e.preventDefault();
    if (editBusy) return;
    setEditBusy(true);
    setError(null);
    setSuccess(null);
    try {
      // name/type/description are NEVER sent as edited values here - the
      // whole point is this stays an UPDATE (McpRegistryService#register
      // upserts by exact name match) rather than a new, sixth duplicate
      // registration under a slightly different name.
      const payload: any = {
        name: server.name,
        type: server.type,
        description: server.description,
        enabled: server.enabled,
      };
      if (server.dynamic && editCommand.trim()) {
        payload.command = editCommand.trim();
        payload.args = parseArgs(editArgsText);
        payload.env = parseEnv(editEnvText);
      } else if (editUrl.trim()) {
        payload.url = editUrl.trim();
        payload.env = parseEnv(editEnvText);
      }
      await api.registerMcpServer(payload);
      setSuccess(t("serverUpdatedSuccess", { name: server.name }));
      setEditingId(null);
      onChanged();
    } catch (err: any) {
      setError(err.message || t("editServerError"));
    } finally {
      setEditBusy(false);
    }
  }

  return {
    editingId,
    editCommand,
    setEditCommand,
    editArgsText,
    setEditArgsText,
    editEnvText,
    setEditEnvText,
    editUrl,
    setEditUrl,
    editBusy,
    startEditServer,
    cancelEditServer,
    submitEditServer,
  };
}
