"use client";

// "Connect GitHub" — tenant-facing half of the BYO-repo pivot
// (docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md §4). Build/preview moves
// off our own Docker infra onto the tenant's own GitHub repo + Actions: the
// tenant pastes a repo-scoped fine-grained PAT, app-builder validates + stores
// it, and every future "generate" call pushes a branch and opens a PR on that
// repo instead of writing to a container we run.
//
// Lives under web/app/build/components/ (not web/app/components/build/) on
// purpose: .gitignore has a blanket `**/build/` rule (meant for Android/Gradle
// build-cache dirs) with only `!web/app/build/**` carved back out for this
// route. A components/build/ directory anywhere else silently matches the
// blanket rule and never gets committed — verified with
// `git check-ignore -v` before picking this path.
//
// Backend endpoints (GET/POST/DELETE /projects/github/*) are being built by a
// parallel agent and are not live on any deployment yet as of 2026-07-30 —
// every fetch here degrades to a plain error/disconnected state rather than
// assuming success. Copy is inline (not i18n keys): web/messages/en.json is a
// large, fragile, statically-imported file another session may be editing
// concurrently, and this mirrors the same choice already made for the
// "App Builder is not available" block in ../page.tsx.
import { useCallback, useEffect, useState } from "react";
import { KeyRound, Loader2, ShieldCheck, Unplug, XCircle, ExternalLink } from "lucide-react";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "../../components/ui/card";
import { Alert, AlertDescription } from "../../components/ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "../../components/ui/alert-dialog";
import { buttonVariants } from "../../components/ui/button";
import { Github } from "../../components/lucideBrandIcons";
import { appBuilder, type AppBuilderGithubStatus } from "../lib";
import { useTranslations } from "../../lib/i18n";

const REPO_PATTERN = /^[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/;

// GitHub's fine-grained PAT creation page accepts query params to pre-select
// the resource owner, the single repo, and per-permission read/write level.
// This is best-effort pre-scoping, not a guarantee — GitHub's own page is
// still the source of truth, and the tenant should confirm the scopes shown
// there before creating the token. Contents, Pull requests, and Pages are
// requested (write implies read in GitHub's model), nothing broader. Pages
// is required: without it the backend cannot switch on the repo's GitHub
// Pages site and every preview URL 404s.
function buildGithubPatUrl(repoFullName: string): string {
  const params = new URLSearchParams();
  // App NAME stays ASCII on purpose: GitHub derives the app slug from it and
  // rejects/mangles non-ASCII there. The free-text description carries the
  // real brand spelling.
  params.set("name", "VegaDuta App Builder");
  params.set("description", "Push branches and open PRs for VegaDūta App Builder");
  const parts = repoFullName.trim().split("/");
  if (parts.length === 2 && parts[0] && parts[1]) {
    params.set("target_name", parts[0]);
    params.set("repositories", parts[1]);
  }
  params.set("contents", "write");
  params.set("pull_requests", "write");
  params.set("pages", "write");
  return `https://github.com/settings/personal-access-tokens/new?${params.toString()}`;
}

export interface GithubConnectCardProps {
  /** Fired whenever the known connection status changes (connect, disconnect,
   *  or the initial fetch resolving) — lets a parent screen (e.g. /build's
   *  header pill) stay in sync without fetching status itself. */
  onStatusChange?: (status: AppBuilderGithubStatus | null) => void;
}

export default function GithubConnectCard({ onStatusChange }: GithubConnectCardProps) {
    const tHC = useTranslations("hcBuildComponentsGithubConnectCard");
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState<AppBuilderGithubStatus | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);

  const [token, setToken] = useState("");
  const [repoFullName, setRepoFullName] = useState("");
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);

  const [disconnecting, setDisconnecting] = useState(false);
  const [disconnectError, setDisconnectError] = useState<string | null>(null);

  const applyStatus = useCallback(
    (next: AppBuilderGithubStatus | null) => {
      setStatus(next);
      onStatusChange?.(next);
    },
    [onStatusChange]
  );

  const refreshStatus = useCallback(() => {
    setLoading(true);
    setStatusError(null);
    appBuilder
      .getGithubStatus()
      .then((s) => applyStatus(s))
      .catch((e) => {
        // The backend for this is not deployed everywhere yet — a 404/"not
        // configured" here reads as "not connected", not a hard failure.
        applyStatus({ connected: false, repo_full_name: null });
        setStatusError(e?.notConfigured ? null : e.message || null);
      })
      .finally(() => setLoading(false));
  }, [applyStatus]);

  useEffect(() => {
    refreshStatus();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const canConnect = token.trim().length > 0 && REPO_PATTERN.test(repoFullName.trim());

  const connect = async () => {
    if (!canConnect || connecting) return;
    setConnecting(true);
    setConnectError(null);
    try {
      const result = await appBuilder.connectGithub({
        token: token.trim(),
        repo_full_name: repoFullName.trim(),
      });
      setToken(""); // write-only — never keep the raw token in memory longer than the request
      applyStatus({ connected: true, repo_full_name: result?.repo_full_name || repoFullName.trim() });
    } catch (e: any) {
      setConnectError(e?.message || "Could not connect that repository.");
    } finally {
      setConnecting(false);
    }
  };

  const disconnect = async () => {
    setDisconnecting(true);
    setDisconnectError(null);
    try {
      await appBuilder.disconnectGithub();
      applyStatus({ connected: false, repo_full_name: null });
      setRepoFullName("");
    } catch (e: any) {
      setDisconnectError(e?.message || "Could not disconnect. Try again.");
    } finally {
      setDisconnecting(false);
    }
  };

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-sm">
          <span className="cat-chip cat-chip-sm" style={{ ["--cat" as string]: "var(--cat-automation)" }}>
            <Github aria-hidden="true" size={13} />
          </span>
          {tHC("s35")}
        </CardTitle>
      </CardHeader>
      <CardContent>
        {loading ? (
          <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
            <Loader2 aria-hidden="true" size={15} className="animate-spin" /> {tHC("s34")}
          </div>
        ) : status?.connected ? (
          <ConnectedState
            repoFullName={status.repo_full_name}
            connectedBy={status.connected_by}
            connectedAt={status.connected_at}
            disconnecting={disconnecting}
            disconnectError={disconnectError}
            onDisconnect={disconnect}
          />
        ) : (
          <DisconnectedForm
            token={token}
            setToken={setToken}
            repoFullName={repoFullName}
            setRepoFullName={setRepoFullName}
            canConnect={canConnect}
            connecting={connecting}
            connectError={connectError}
            onConnect={connect}
          />
        )}

        {statusError && (
          <Alert variant="destructive" className="mt-4">
            <XCircle aria-hidden="true" />
            <AlertDescription>{statusError}</AlertDescription>
          </Alert>
        )}
      </CardContent>
    </Card>
  );
}

/* ------------------------------------------------------------------ */

function DisconnectedForm({
  token,
  setToken,
  repoFullName,
  setRepoFullName,
  canConnect,
  connecting,
  connectError,
  onConnect,
}: {
  token: string;
  setToken: (v: string) => void;
  repoFullName: string;
  setRepoFullName: (v: string) => void;
  canConnect: boolean;
  connecting: boolean;
  connectError: string | null;
  onConnect: () => void;
}) {
    const tHC = useTranslations("hcBuildComponentsGithubConnectCard");
  return (
    <div>
      <p className="mb-4 text-sm leading-relaxed text-muted-foreground">
        {tHC("s33")} <span className="font-medium text-foreground">{tHC("s32")}</span> {tHC("s31")}
        <span className="font-medium text-foreground"> {tHC("s30")}</span>{tHC("s29")}
      </p>

      <div className="mb-4 rounded-lg border border-border bg-muted/30 p-3">
        <div className="mb-1 flex items-center gap-1.5 text-xs font-medium text-foreground">
          <ShieldCheck
            aria-hidden="true"
            size={13}
            className="cat-tint"
            style={{ ["--cat" as string]: "var(--cat-automation)" }}
          />
          {tHC("s28")}
        </div>
        <p className="text-xs leading-relaxed text-muted-foreground">
          {tHC("s27")}{" "}
          <span className="font-medium text-foreground">fine-grained</span> {tHC("s26")}
        </p>
      </div>

      <div className="mb-4">
        <Label
          htmlFor="gh-repo"
          className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground"
        >
          Repository
        </Label>
        <Input
          id="gh-repo"
          placeholder="owner/repo"
          value={repoFullName}
          onChange={(e) => setRepoFullName(e.target.value)}
          autoComplete="off"
          spellCheck={false}
        />
        <p className="mt-1 text-xs text-muted-foreground">{tHC("s25")}</p>
      </div>

      <div className="mb-4 rounded-lg border border-border bg-muted/30 p-3">
        <div className="mb-1.5 flex items-center gap-1.5 text-xs font-medium text-foreground">
          <KeyRound
            aria-hidden="true"
            size={13}
            className="cat-tint"
            style={{ ["--cat" as string]: "var(--cat-automation)" }}
          />
          {tHC("s24")}
        </div>
        <ol className="list-decimal space-y-1 ps-4 text-xs leading-relaxed text-muted-foreground">
          <li>
            Click <span className="font-medium text-foreground">{tHC("s5")}</span> {tHC("s23")} <span className="font-medium text-foreground">fine-grained</span> {tHC("s22")}
          </li>
          <li>
            <span className="font-medium text-foreground">{tHC("s21")}</span> {tHC("s20")}
          </li>
          <li>
            <span className="font-medium text-foreground">{tHC("s19")}</span> {tHC("s18")}{" "}
            <span className="font-medium text-foreground">{tHC("s17")}</span> {tHC("s16")}
          </li>
          <li>
            <span className="font-medium text-foreground">{tHC("s15")}</span> — set{" "}
            <span className="font-medium text-foreground">{tHC("s14")}</span>,{" "}
            <span className="font-medium text-foreground">{tHC("s13")}</span>, and{" "}
            <span className="font-medium text-foreground">{tHC("s12")}</span> {tHC("s11")}
          </li>
          <li>
            <span className="font-medium text-foreground">Expiration</span> {tHC("s10")}
          </li>
          <li>
            Click <span className="font-medium text-foreground">{tHC("s9")}</span> {tHC("s8")}{" "}
            <code className="rounded bg-muted px-1 py-0.5">github_pat_</code> {tHC("s7")}
          </li>
        </ol>
      </div>

      <div className="mb-2">
        <div className="mb-1.5 flex items-center justify-between">
          <Label htmlFor="gh-token" className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {tHC("s6")}
          </Label>
          <a
            href={buildGithubPatUrl(repoFullName)}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline"
          >
            {tHC("s5")} <ExternalLink aria-hidden="true" size={11} />
          </a>
        </div>
        <div className="relative">
          <KeyRound
            aria-hidden="true"
            size={14}
            className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            id="gh-token"
            type="password"
            placeholder={tHC("s4")}
            value={token}
            onChange={(e) => setToken(e.target.value)}
            className="ps-9"
            autoComplete="off"
            spellCheck={false}
          />
        </div>
        <p className="mt-1 text-xs text-muted-foreground">
          {tHC("s3")}
        </p>
      </div>

      {connectError && (
        <Alert variant="destructive" className="mt-3">
          <XCircle aria-hidden="true" />
          <AlertDescription>{connectError}</AlertDescription>
        </Alert>
      )}

      <Button className="mt-4" disabled={!canConnect || connecting} onClick={onConnect}>
        {connecting ? (
          <Loader2 aria-hidden="true" size={15} className="animate-spin" />
        ) : (
          <Github aria-hidden="true" size={15} />
        )}
        {connecting ? "Connecting…" : "Connect repository"}
      </Button>
    </div>
  );
}

/* ------------------------------------------------------------------ */

function ConnectedState({
  repoFullName,
  connectedBy,
  connectedAt,
  disconnecting,
  disconnectError,
  onDisconnect,
}: {
  repoFullName: string | null;
  connectedBy?: string | null;
  connectedAt?: string | null;
  disconnecting: boolean;
  disconnectError: string | null;
  onDisconnect: () => void;
}) {
    const tHC = useTranslations("hcBuildComponentsGithubConnectCard");
  return (
    <div>
      <div className="mb-4 flex items-center gap-2">
        <span className="state-pill" style={{ ["--state" as string]: "var(--state-success)" }}>
          <span className="state-dot" /> Connected
        </span>
        <span className="font-mono text-sm text-foreground">{repoFullName}</span>
      </div>

      <p className="mb-1 text-sm leading-relaxed text-muted-foreground">
        {tHC("s2")}
      </p>
      {(connectedBy || connectedAt) && (
        <p className="mb-4 text-xs text-muted-foreground">
          {connectedBy ? `Connected by ${connectedBy}` : "Connected"}
          {connectedAt ? ` on ${new Date(connectedAt).toLocaleString()}` : ""}
        </p>
      )}

      {disconnectError && (
        <Alert variant="destructive" className="mb-3">
          <XCircle aria-hidden="true" />
          <AlertDescription>{disconnectError}</AlertDescription>
        </Alert>
      )}

      <AlertDialog>
        <AlertDialogTrigger asChild>
          <Button
            variant="ghost"
            size="sm"
            className="text-destructive hover:bg-destructive/10 hover:text-destructive"
            disabled={disconnecting}
          >
            {disconnecting ? (
              <Loader2 aria-hidden="true" size={14} className="animate-spin" />
            ) : (
              <Unplug aria-hidden="true" size={14} />
            )}
            Disconnect
          </Button>
        </AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Disconnect {repoFullName}?</AlertDialogTitle>
            <AlertDialogDescription>
              {tHC("s1")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction className={buttonVariants({ variant: "destructive" })} onClick={onDisconnect}>
              <Unplug aria-hidden="true" size={14} />
              Disconnect
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
