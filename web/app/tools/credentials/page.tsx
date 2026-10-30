"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import {
  ArrowLeft,
  CheckCircle2,
  Eye,
  EyeOff,
  Lock,
  Pencil,
  Plus,
  Trash2,
  ExternalLink,
  HelpCircle,
  X,
} from "lucide-react";
import { api } from "../../lib/api";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { usePageReady } from "../../components/RouteTransition";
import { Button } from "../../components/ui/button";
import { Badge } from "../../components/ui/badge";
import { Card, CardContent } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../components/ui/alert-dialog";
import { useTranslations } from "../../lib/i18n";
import { credentialGuideFor, type McpCredentialGuideField } from "../../lib/credentialGuides";
import { Label } from "../../components/ui/label";
import { Section } from "../../components/ax/page-kit";
import { toast } from "../../lib/toastBus";
import PaginationControls from "../../components/PaginationControls";
import { usePagination } from "../../lib/usePagination";

/**
 * MCP Server Credentials Management - admin-facing page to manage credentials
 * (API keys, tokens, database URLs) for all configured MCP servers.
 *
 * This page shows:
 * 1. A list of all registered MCP servers with credential status
 * 2. For each server, links to credential guides
 * 3. An Edit modal to add/update credentials securely
 * 4. Masked display of stored credentials (dots instead of values)
 *
 * Credentials are encrypted on the backend (McpRegistryService) and never
 * echoed back in API responses, so we can't show their values - only that
 * they're set or not.
 */

interface McpServer {
  id: string;
  name: string;
  type: string;
  description?: string;
  enabled: boolean;
  dynamic: boolean;
  connectionStatus?: string;
  usageStatus?: string;
  command?: string;
  url?: string;
  args?: string;
}

interface CredentialRow {
  key: string;
  value: string;
}

// The read DTO stores args as a JSON-encoded string (McpRegistryService
// writeJson) but the write DTO wants a real list - mirrors McpPanel's own
// parseStoredArgs exactly. A naive `.split(" ")` on that JSON string (the
// previous behavior here) mangled any server whose stored args contained a
// quoted/JSON value with an embedded space (e.g. `["--config","my file.json"]`
// split into `["--config","my`, `file.json"]"`), silently corrupting that
// server's launch command on every credential save/clear.
function parseStoredArgs(args: any): string[] {
  if (Array.isArray(args)) return args.map(String);
  if (typeof args === "string" && args.trim()) {
    try {
      const parsed = JSON.parse(args);
      if (Array.isArray(parsed)) return parsed.map(String);
    } catch {
      // Not JSON - treat as a space-separated legacy string.
    }
    return args.trim().split(/\s+/);
  }
  return [];
}

function CredentialGuide({ server }: { server: McpServer }) {
  const guide = credentialGuideFor(server.name);
  if (!guide) return null;

  return (
    <Alert variant="info" className="mb-4">
      <HelpCircle className="h-4 w-4" aria-hidden="true" />
      <AlertTitle className="text-sm font-semibold">{guide.title}</AlertTitle>
      <AlertDescription className="mt-2 space-y-1 text-xs">
        <ul className="list-disc space-y-1 ps-4">
          {guide.steps.map((step: string, i: number) => (
            <li key={i}>{step}</li>
          ))}
        </ul>
        {guide.href && (
          <div className="mt-2">
            <a
              href={guide.href}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-[var(--info-text)] hover:underline"
            >
              {guide.hrefLabel || "Learn more"} <ExternalLink size={12} aria-hidden="true" />
            </a>
          </div>
        )}
      </AlertDescription>
    </Alert>
  );
}

// True when the field itself should be masked - an explicit `secret` wins,
// otherwise fall back to the same name-sniffing heuristic McpPanel's
// EnableConfirmCard already uses for its freeform rows, so a documented
// field without an explicit `secret` flag still masks sensibly.
function isSecretField(field: McpCredentialGuideField): boolean {
  return field.secret ?? /token|key|secret|auth|password/i.test(field.name);
}

function CredentialEditDialog({
  server,
  onSave,
  onCancel,
  isBusy,
}: {
  server: McpServer;
  onSave: (credentials: CredentialRow[]) => Promise<void>;
  onCancel: () => void;
  isBusy: boolean;
}) {
    const tHC = useTranslations("hcToolsCredentialsPage");
  const guide = credentialGuideFor(server.name);
  const knownFields: McpCredentialGuideField[] = guide?.fields || [];
  const hasKnownFields = knownFields.length > 0;

  // Known-field servers (atlassian, github, slack, ...) get one labeled row
  // per documented field (value only - the key is fixed to the real field
  // name) plus optional freeform "extra" rows for anything beyond what's
  // documented. Anything without a guide/fields keeps the original generic
  // freeform key+value UI unchanged - custom/unknown servers have no way to
  // know their field names in advance.
  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});
  const [rows, setRows] = useState<CredentialRow[]>(hasKnownFields ? [] : [{ key: "", value: "" }]);
  const [showValues, setShowValues] = useState(false);
  const isRemote = server.url && !server.command;

  const entries: CredentialRow[] = hasKnownFields
    ? [
        ...knownFields
          .filter((f) => (fieldValues[f.name] || "").trim())
          .map((f) => ({ key: f.name, value: fieldValues[f.name] })),
        ...rows.filter((r) => r.key.trim()),
      ]
    : rows.filter((r) => r.key.trim());

  const setRow = (i: number, field: "key" | "value", v: string) => {
    setRows((prev) => prev.map((r, idx) => (idx === i ? { ...r, [field]: v } : r)));
  };

  async function handleSave() {
    if (entries.length === 0) {
      toast.info("Add at least one credential before saving");
      return;
    }
    await onSave(entries);
  }

  return (
    <AlertDialog open onOpenChange={(open) => !open && !isBusy && onCancel()}>
      <AlertDialogContent className="max-w-md">
        <AlertDialogHeader>
          <div className="flex items-center justify-between gap-3">
            <AlertDialogTitle>{tHC("s14")}</AlertDialogTitle>
            <Button
              variant="ghost"
              size="icon"
              className="size-8 text-muted-foreground hover:text-foreground"
              onClick={onCancel}
              disabled={isBusy}
              aria-label="Close"
              title="Close"
            >
              <X size={16} aria-hidden="true" />
            </Button>
          </div>
          <AlertDialogDescription>
            {isRemote
              ? "Credentials are sent as HTTP headers (e.g., Authorization: Bearer token)"
              : "Credentials are passed as environment variables to the server"}
          </AlertDialogDescription>
        </AlertDialogHeader>

        <CredentialGuide server={server} />

        <div className="space-y-2">
          {hasKnownFields && (
            <div className="space-y-3">
              {knownFields.map((field) => {
                const fieldId = `cred-field-${field.name}`;
                return (
                  <div key={field.name} className="space-y-1">
                    <Label htmlFor={fieldId} className="text-xs font-medium">
                      {field.name}
                      {field.required ? <span className="text-destructive"> *</span> : null}
                    </Label>
                    {field.description && <p className="text-xs text-muted-foreground">{field.description}</p>}
                    <div className="relative">
                      <Input
                        id={fieldId}
                        type={showValues ? "text" : isSecretField(field) ? "password" : "text"}
                        placeholder="Value"
                        value={fieldValues[field.name] || ""}
                        onChange={(e) => setFieldValues((prev) => ({ ...prev, [field.name]: e.target.value }))}
                        disabled={isBusy}
                      />
                      <button
                        type="button"
                        className="absolute end-3 top-1/2 -translate-y-1/2 border-0 bg-transparent p-0 text-muted-foreground shadow-none hover:text-foreground"
                        onClick={() => setShowValues(!showValues)}
                        aria-label={showValues ? "Hide credential value" : "Show credential value"}
                        title={showValues ? "Hide credential value" : "Show credential value"}
                      >
                        {showValues ? <EyeOff size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />}
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
          {rows.map((row, i) => {
            const rowKeyId = `cred-row-key-${i}`;
            const rowValueId = `cred-row-value-${i}`;
            return (
              <div key={i} className="flex gap-2">
                <div className="flex-1 space-y-1">
                  <Label htmlFor={rowKeyId} className="sr-only">
                    {hasKnownFields ? "Extra header/variable name" : isRemote ? "Header name" : "Variable name"}
                  </Label>
                  <Input
                    id={rowKeyId}
                    type="text"
                    placeholder={
                      hasKnownFields
                        ? "Extra header/variable name"
                        : isRemote
                          ? "Header name (e.g., Authorization)"
                          : "Variable name (e.g., API_KEY)"
                    }
                    value={row.key}
                    onChange={(e) => setRow(i, "key", e.target.value)}
                    disabled={isBusy}
                  />
                </div>
                <div className="relative flex-1 space-y-1">
                  <Label htmlFor={rowValueId} className="sr-only">
                    Value
                  </Label>
                  <Input
                    id={rowValueId}
                    type={showValues ? "text" : "password"}
                    placeholder="Value"
                    value={row.value}
                    onChange={(e) => setRow(i, "value", e.target.value)}
                    disabled={isBusy}
                  />
                  <button
                    type="button"
                    className="absolute end-3 top-1/2 -translate-y-1/2 border-0 bg-transparent p-0 text-muted-foreground shadow-none hover:text-foreground"
                    onClick={() => setShowValues(!showValues)}
                    aria-label={showValues ? "Hide credential value" : "Show credential value"}
                    title={showValues ? "Hide credential value" : "Show credential value"}
                  >
                    {showValues ? <EyeOff size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />}
                  </button>
                </div>
                {(hasKnownFields || rows.length > 1) && (
                  <Button
                    variant="ghost"
                    size="icon"
                    className="text-muted-foreground hover:text-destructive"
                    disabled={isBusy}
                    onClick={() => setRows((prev) => prev.filter((_, idx) => idx !== i))}
                    aria-label={tHC("s16")}
                    title={tHC("s16")}
                  >
                    <Trash2 size={16} aria-hidden="true" />
                  </Button>
                )}
              </div>
            );
          })}
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="gap-1"
            disabled={isBusy}
            onClick={() => setRows((prev) => [...prev, { key: "", value: "" }])}
          >
            <Plus size={14} aria-hidden="true" /> {hasKnownFields ? "Add another" : "Add credential"}
          </Button>
        </div>

        <AlertDialogFooter>
          <AlertDialogCancel
            disabled={isBusy}
            onClick={(e) => {
              e.preventDefault();
              onCancel();
            }}
          >
            Cancel
          </AlertDialogCancel>
          <AlertDialogAction
            disabled={isBusy || entries.length === 0}
            onClick={(e) => {
              e.preventDefault();
              void handleSave();
            }}
          >
            {isBusy ? "Saving..." : "Save credentials"}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function StaticServerCard({ server }: { server: McpServer }) {
    const tHC = useTranslations("hcToolsCredentialsPage");
  return (
    <Card>
      <CardContent className="pt-6">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="min-w-0 break-words font-semibold">{server.name}</h3>
              <Badge variant="outline" className="shrink-0">
                {server.type}
              </Badge>
              <Badge variant="secondary" className="shrink-0">
                Built-in
              </Badge>
            </div>
            {server.description && <p className="mt-1 text-xs text-muted-foreground">{server.description}</p>}
            <p className="mt-2 text-xs text-muted-foreground">
              {tHC("s15")}
            </p>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

function ServerCredentialCard({
  server,
  onEdit,
  onDelete,
}: {
  server: McpServer;
  onEdit: () => void;
  onDelete: () => void;
}) {
    const tHC = useTranslations("hcToolsCredentialsPage");
  const statusColor =
    server.usageStatus === "READY" || server.usageStatus === "ACKNOWLEDGED"
      ? "text-[var(--state-success)]"
      : server.usageStatus === "FAILED"
        ? "text-[var(--state-error)]"
        : "text-[var(--state-warning)]";

  return (
    <Card>
      <CardContent className="pt-6">
        <div className="mb-3 flex items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="min-w-0 break-words font-semibold">{server.name}</h3>
              <Badge variant="outline" className="shrink-0">
                {server.type}
              </Badge>
              {server.dynamic && (
                <Badge variant="secondary" className="shrink-0">
                  {server.enabled ? (
                    <>
                      <CheckCircle2 size={12} aria-hidden="true" /> Enabled
                    </>
                  ) : (
                    "Disabled"
                  )}
                </Badge>
              )}
            </div>
            {server.description && <p className="mt-1 text-xs text-muted-foreground">{server.description}</p>}
          </div>
          <div className={`text-xs font-medium ${statusColor}`}>{server.usageStatus || "—"}</div>
        </div>

        {server.dynamic && (
          <div className="mt-4 flex gap-2">
            <Button variant="outline" size="sm" onClick={onEdit} className="gap-1">
              <Pencil size={14} aria-hidden="true" /> {tHC("s14")}
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={onDelete}
              className="gap-1 text-destructive hover:bg-destructive/10 hover:text-destructive"
            >
              <Trash2 size={14} aria-hidden="true" /> {tHC("s13")}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export default function CredentialsPage() {
    const tHC = useTranslations("hcToolsCredentialsPage");
  const t = useTranslations("toolsPage");
  const [servers, setServers] = useState<McpServer[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [editingServer, setEditingServer] = useState<McpServer | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [deletingServer, setDeletingServer] = useState<McpServer | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const list = await api.listMcpServers();
      setServers(list);
    } catch (err) {
      toast.error("Failed to load MCP servers");
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  usePageReady(loaded);

  async function handleSaveCredentials(credentials: CredentialRow[]) {
    if (!editingServer) return;
    setIsSaving(true);
    try {
      const env: Record<string, string> = {};
      credentials.forEach((cred) => {
        env[cred.key] = cred.value;
      });

      await api.registerMcpServer({
        name: editingServer.name,
        type: editingServer.type,
        description: editingServer.description,
        enabled: editingServer.enabled,
        command: editingServer.command,
        url: editingServer.url,
        args: editingServer.command ? parseStoredArgs(editingServer.args) : undefined,
        env,
      });

      toast.success("Credentials saved successfully");
      setEditingServer(null);
      await refresh();
    } catch (err) {
      toast.error("Failed to save credentials");
    } finally {
      setIsSaving(false);
    }
  }

  // Re-registers the server with an empty env/header map. McpRegistryService
  // .register() upserts by name and configureDynamic/configureRemote fully
  // overwrite the stored encrypted env (not a merge) - encryptEnv({}) persists
  // as null - so this is a genuine clear, not a no-op, even though core/mcp
  // has no dedicated DELETE-credentials endpoint.
  async function handleClearCredentials() {
    if (!deletingServer) return;
    setIsDeleting(true);
    try {
      await api.registerMcpServer({
        name: deletingServer.name,
        type: deletingServer.type,
        description: deletingServer.description,
        enabled: deletingServer.enabled,
        command: deletingServer.command,
        url: deletingServer.url,
        args: deletingServer.command ? parseStoredArgs(deletingServer.args) : undefined,
        env: {},
      });

      toast.success(`Credentials cleared for ${deletingServer.name}`);
      setDeletingServer(null);
      await refresh();
    } catch (err) {
      toast.error("Failed to clear credentials");
    } finally {
      setIsDeleting(false);
    }
  }

  const dynamicServers = servers.filter((s) => s.dynamic);
  const staticServers = servers.filter((s) => !s.dynamic);
  const dynamicPagination = usePagination(dynamicServers);
  const staticPagination = usePagination(staticServers);

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader
        title={tHC("s12")}
        subtitle="Manage API keys and tokens for your connected MCP servers"
        actions={
          <>
            <Button variant="ghost" size="sm" className="gap-1" asChild>
              <Link href="/help/docs#tools" target="_blank" rel="noopener noreferrer">
                <HelpCircle size={14} aria-hidden="true" /> {tHC("s11")}
              </Link>
            </Button>
            <Button variant="outline" size="sm" className="gap-1" asChild>
              <Link href="/tools/servers">
                <ArrowLeft size={14} aria-hidden="true" /> {tHC("s10")}
              </Link>
            </Button>
          </>
        }
      />

      {servers.length === 0 ? (
        <div className="flex flex-col items-center justify-center rounded-lg border border-dashed p-12 text-center">
          <Lock size={32} className="mb-3 text-muted-foreground" aria-hidden="true" />
          <h3 className="font-semibold">{tHC("s9")}</h3>
          <p className="mt-1 text-sm text-muted-foreground">{tHC("s8")}</p>
          <Button className="mt-4" size="sm" asChild>
            <Link href="/tools">
              <Plus size={14} aria-hidden="true" /> {tHC("s7")}
            </Link>
          </Button>
        </div>
      ) : (
        <div className="space-y-8">
          <Section title={tHC("s6")} className="space-y-4">
            {dynamicServers.length === 0 ? (
              <Alert>
                <AlertDescription>
                  {tHC("s5")}
                </AlertDescription>
              </Alert>
            ) : (
              dynamicPagination.pageItems.map((server) => (
                <ServerCredentialCard
                  key={server.id}
                  server={server}
                  onEdit={() => setEditingServer(server)}
                  onDelete={() => setDeletingServer(server)}
                />
              ))
            )}
            <PaginationControls
              page={dynamicPagination.page}
              totalPages={dynamicPagination.totalPages}
              totalItems={dynamicPagination.totalItems}
              pageSize={dynamicPagination.pageSize}
              onPageChange={dynamicPagination.setPage}
              onPageSizeChange={dynamicPagination.setPageSize}
              itemLabel="servers"
            />
          </Section>

          {staticServers.length > 0 && (
            <Section
              title={tHC("s4")}
              description="Shared platform-wide and wired at deployment via an env var on the server — not a per-workspace credential, so there's nothing to edit here."
              className="space-y-3"
            >
              <div className="space-y-4">
                {staticPagination.pageItems.map((server) => (
                  <StaticServerCard key={server.id} server={server} />
                ))}
              </div>
              <PaginationControls
                page={staticPagination.page}
                totalPages={staticPagination.totalPages}
                totalItems={staticPagination.totalItems}
                pageSize={staticPagination.pageSize}
                onPageChange={staticPagination.setPage}
                onPageSizeChange={staticPagination.setPageSize}
                itemLabel="servers"
              />
            </Section>
          )}
        </div>
      )}

      {editingServer && (
        <CredentialEditDialog
          server={editingServer}
          onSave={handleSaveCredentials}
          onCancel={() => setEditingServer(null)}
          isBusy={isSaving}
        />
      )}

      <AlertDialog open={!!deletingServer} onOpenChange={(open: boolean) => !open && setDeletingServer(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{tHC("s3")} {deletingServer?.name}?</AlertDialogTitle>
            <AlertDialogDescription>
              {tHC("s2")}{" "}
              {deletingServer?.url && !deletingServer?.command ? "headers" : "environment variables"} {tHC("s1")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={isDeleting}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={isDeleting}
              onClick={(e: any) => {
                e.preventDefault();
                void handleClearCredentials();
              }}
            >
              {isDeleting ? "Clearing..." : "Clear credentials"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </AppShell>
  );
}
