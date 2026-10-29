"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2, ShieldAlert } from "lucide-react";
import { api } from "../../lib/api";
import type { McpToolPinStatus } from "../../lib/api/mcpToolPins";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";

/** What the current mode means for the people reading the card. */
export function pinModeNote(mode: McpToolPinStatus["mode"]): string {
  if (mode === "enforce") return "Agents cannot use this server's tools until you approve the new list.";
  return "Agents are still using this server. Set app.mcp.tool-pinning.mode=enforce to withhold changed servers until approved.";
}

function ToolList({ label, names }: { label: string; names: string[] }) {
  if (names.length === 0) return null;
  return (
    <div className="text-xs">
      <span className="font-medium">{label}:</span>{" "}
      {names.map((n) => (
        <code key={n} className="me-1 rounded bg-muted px-1 py-0.5">
          {n}
        </code>
      ))}
    </div>
  );
}

/**
 * Review queue for tool-definition pinning (MCP spec alignment): every remote MCP server whose tool list
 * (names, descriptions, input schemas) differs from the list an admin last accepted. Approving makes the
 * new list the baseline. Renders nothing when nothing changed, when pinning is off, or when the endpoint is
 * unavailable, so a deployment without changes sees an unchanged page.
 */
export default function ToolPinReview({ onChanged }: { onChanged?: () => void }) {
  const [items, setItems] = useState<McpToolPinStatus[]>([]);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setItems(await api.listChangedMcpToolPins());
    } catch {
      setItems([]);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function approve(item: McpToolPinStatus) {
    setError(null);
    setBusyId(item.registrationId);
    try {
      await api.approveMcpToolPins(item.registrationId);
      await load();
      onChanged?.();
    } catch (err) {
      setError((err as Error)?.message || "Could not approve the new tool list.");
    } finally {
      setBusyId(null);
    }
  }

  if (items.length === 0) return null;

  return (
    <Alert variant="destructive" className="mb-4" data-testid="mcp-tool-pin-review">
      <ShieldAlert className="h-4 w-4" />
      <AlertTitle>Tool definitions changed — review before trusting</AlertTitle>
      <AlertDescription>
        <p className="mb-3 text-sm">
          These servers changed what their tools are or say since you last accepted them. A changed description can
          change how an agent behaves, so read it before approving.
        </p>
        <ul className="flex flex-col gap-3">
          {items.map((item) => (
            <li key={item.registrationId} className="rounded-md border p-3" data-testid={`mcp-tool-pin-${item.name}`}>
              <div className="mb-1 flex flex-wrap items-center gap-2">
                <strong>{item.name}</strong>
                <Badge variant="outline">{item.mode}</Badge>
                {item.changedAt && (
                  <span className="text-xs text-muted-foreground">
                    changed {new Date(item.changedAt).toLocaleString()}
                  </span>
                )}
              </div>
              <ToolList label="Changed" names={item.changedTools} />
              <ToolList label="Added" names={item.added} />
              <ToolList label="Removed" names={item.removed} />
              <p className="my-2 text-xs text-muted-foreground">{pinModeNote(item.mode)}</p>
              <Button
                size="sm"
                variant="outline"
                disabled={busyId !== null}
                onClick={() => void approve(item)}
                data-testid={`mcp-tool-pin-approve-${item.name}`}
              >
                {busyId === item.registrationId ? <Loader2 className="h-3 w-3 animate-spin" /> : null} Approve new tool
                list
              </Button>
            </li>
          ))}
        </ul>
        {error && <p className="mt-2 text-sm">{error}</p>}
      </AlertDescription>
    </Alert>
  );
}
