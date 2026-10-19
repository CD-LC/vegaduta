"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { api } from "../lib/api";
import EmptyState from "./EmptyState";
import PageHeader from "./ax/PageHeader";
import { Alert, AlertDescription } from "./ui/alert";
import { Badge } from "./ui/badge";
import { Button } from "./ui/button";
import { Card, CardContent } from "./ui/card";
import {
  Boxes,
  Link2,
  HardDrive,
  Mail,
  Calendar as CalendarIcon,
  Table as TableIcon,
  AtSign,
  KeyRound,
  Youtube,
} from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { errorMessage } from "../lib/errors";

// Maps a granted OAuth scope to a short label + a generic lucide icon (same
// "no external brand-asset dependency, plain icon per product area" idiom
// McpPanel's catalog cards already use) - not a real Google product mark.
//
// Covers every scope a connection can actually carry: the 7 GoogleScopes
// .REQUESTED (core/google) asks for on the base consent - GoogleScopes.EMAIL
// bundles two identity scopes ("openid" and userinfo.email) ahead of the 5
// product scopes - plus youtube.readonly, which is NOT in REQUESTED (Google
// rejects drive.file+youtube.readonly in one request) and is granted via the
// incremental consent the OAuth callback chains into after a base connect.
// The first version of this map only had the 5 product scopes, so
// the 2 identity ones fell through to the raw-URL fallback below and, being
// far longer than every other chip, forced the whole row to wrap one chip
// per line - exactly the "not properly arranged" layout a user reported
// after connecting for real. Fixed by mapping every scope Google can
// actually grant here, not just the ones that felt most "product-y".
const SCOPE_INFO = {
  openid: { labelKey: "scopeSignInIdentity", icon: KeyRound },
  "https://www.googleapis.com/auth/userinfo.email": { labelKey: "scopeEmailAddress", icon: AtSign },
  "https://www.googleapis.com/auth/drive.file": { labelKey: "scopeDrive", icon: HardDrive },
  "https://www.googleapis.com/auth/gmail.readonly": { labelKey: "scopeGmailRead", icon: Mail },
  "https://www.googleapis.com/auth/gmail.send": { labelKey: "scopeGmailSend", icon: Mail },
  "https://www.googleapis.com/auth/calendar.events": { labelKey: "scopeCalendar", icon: CalendarIcon },
  "https://www.googleapis.com/auth/spreadsheets": { labelKey: "scopeSheets", icon: TableIcon },
  "https://www.googleapis.com/auth/youtube.readonly": { labelKey: "scopeYoutube", icon: Youtube },
  // Not in GoogleScopes.REQUESTED (the scopes the base "Connect Google" flow
  // asks for) - only ever granted via the separate incremental
  // youtubeUploadAuthorizeUrl consent below, which is why it needs its own
  // "Enable YouTube Publishing" button rather than showing up automatically
  // after connecting like the 8 REQUESTED scopes above.
  "https://www.googleapis.com/auth/youtube.upload": { labelKey: "scopeYoutubeUpload", icon: Youtube },
};

// Kept as its own named constant (rather than a magic string used in two
// places below) because GoogleScopes.YOUTUBE_UPLOAD (core/google) is
// deliberately NOT part of the base "Connect Google" consent - see
// GoogleOAuthController#youtubeUploadAuthorizeUrl's javadoc.
const YOUTUBE_UPLOAD_SCOPE = "https://www.googleapis.com/auth/youtube.upload";

// Defensive fallback only - every scope this platform can actually grant is
// mapped above, so this only fires if a future scope is added to
// GoogleScopes.REQUESTED without a matching entry here. Turns a raw
// "https://www.googleapis.com/auth/foo.bar" into "Foo bar" instead of
// dumping the full URL into a chip (which is what broke the layout before).
function friendlyScopeLabel(scope: string) {
  const tail = scope.split("/").pop() || scope;
  const words = tail.replace(/[._-]/g, " ").trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

// Semantic status-badge classes on the shared --success-*/--danger-* tokens
// (same convention ProvidersPanel.tsx's OK_BADGE/WARN_BADGE and
// ChannelsPanel's status pills already use) so ACTIVE/suspended keep their
// meaning regardless of the tenant's accent palette or light/dark theme.
const OK_BADGE = "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]";
const WARN_BADGE = "border-[var(--danger-border)] bg-[var(--danger-bg)] text-[var(--danger-text)]";

/**
 * ShadCN redesign pass (pixel-to-pixel rework of the Integrations screen).
 * Replaces the legacy `.ax-int-*`/`.ax-intg-screen` bespoke CSS classes
 * (still kept in globals.css for the unrelated workflow-runs pages that also
 * reuse `.ax-int-card` - don't touch those) with the same ShadCN primitives
 * + token conventions already established by the other migrated admin
 * panels: PageHeader for the page-level heading (ChannelsPanel/ProvidersPanel/
 * the /tools/servers rework all use it), `Alert`/`AlertDescription` for the
 * error/success banners (exact pattern ChannelsPanel.tsx uses:
 * `<Alert variant="destructive|success"><AlertDescription>`), and `Card`/
 * `Badge`/`Button` for the connection surface itself. Typography comes from
 * the shared body font stack (Geist via --font-inter, see globals.css) -
 * no font classes needed here.
 *
 * Still one "Connect Google" OAuth flow (GoogleOAuthController), not a
 * credential form - see ChannelsPanel for the pasted-key pattern this
 * deliberately does NOT follow, since Google's OAuth requires a real
 * browser redirect through accounts.google.com. Once connected, every agent
 * automatically gets the google_drive/google_gmail/google_calendar/
 * google_sheets/youtube_content_performance tools plus the booking tools
 * (google_calendar_find_free_slots, google_calendar_move_event,
 * google_calendar_cancel_event; GoogleWorkspaceToolSource) - no per-agent
 * toggle to configure.
 */
export default function GoogleIntegrationPanel() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const t = useTranslations("googleIntegrationPanel");

  const [connection, setConnection] = useState(null);
  const [oauthConfig, setOauthConfig] = useState<{ configured: boolean; redirectUri: string; mode?: string; problem?: string } | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);

  const refresh = useCallback(async () => {
    try {
      setConnection(await api.getGoogleConnection());
      // Best effort: an older core without the endpoint simply keeps the plain Connect button.
      setOauthConfig(await api.getGoogleOAuthConfig().catch((): null => null));
    } catch {
      // Deliberately NOT err.message: api.ts's shared apiFetch/toJson error
      // path returns generic, service-wide copy ("service is temporarily
      // unavailable...team has been notified"), which reads as the whole
      // platform being down. This one status check failing shouldn't imply
      // that - the rest of this panel (the Connect Google card below) still
      // renders and works fine, so the inline error stays scoped to what
      // actually failed: checking the connection status.
      setError(t("loadStatusError"));
    } finally {
      setLoaded(true);
    }
  }, [t]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Reads the ?google=connected|error param GoogleOAuthController#callback's
  // redirect lands with, shows the right banner, then strips the param so a
  // page refresh doesn't re-show it.
  useEffect(() => {
    const googleParam = searchParams.get("google");
    if (!googleParam) return;
    if (googleParam === "connected") {
      setSuccess(t("connectedSuccess"));
      void refresh();
    } else if (googleParam === "error") {
      const reason = searchParams.get("reason");
      setError(reason === "consent_declined" ? t("connectionCancelledError") : t("connectFailedError"));
    }
    router.replace("/admin/integrations");
  }, [searchParams, router, refresh, t]);

  async function handleConnect() {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      const { url } = await api.getGoogleAuthorizeUrl();
      window.location.href = url;
    } catch (err) {
      setError(errorMessage(err, t("startConnectError")));
      setBusy(false);
    }
  }

  async function handleEnableYoutubeUpload() {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      const { url } = await api.getYoutubeUploadAuthorizeUrl();
      window.location.href = url;
    } catch (err) {
      setError(errorMessage(err, t("startEnableYoutubeUploadError")));
      setBusy(false);
    }
  }

  async function handleDisconnect() {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await api.disconnectGoogle();
      setSuccess(t("disconnectedSuccess"));
      await refresh();
    } catch (err) {
      setError(errorMessage(err, t("disconnectError")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="max-w-2xl space-y-6">
      <PageHeader title={t("panelTitle")} subtitle={t("panelDesc")} />

      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {success && (
        <Alert variant="success">
          <AlertDescription>{success}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardContent className="space-y-5">
          {!loaded && <p className="text-sm text-muted-foreground">{t("checkingStatus")}</p>}

          {loaded && connection && (
            <>
              <div className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-muted/40 px-4 py-3">
                <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary text-sm font-semibold text-primary-foreground">
                  {(connection.connectedEmail || "?")[0].toUpperCase()}
                </span>
                <div className="min-w-0 flex-1 space-y-0.5" style={{ overflowWrap: "anywhere" }}>
                  <div className="font-mono text-sm font-semibold text-foreground">{connection.connectedEmail}</div>
                </div>
                <Badge variant="outline" className={connection.status === "ACTIVE" ? OK_BADGE : WARN_BADGE}>
                  {connection.status === "ACTIVE" ? t("connectedStatus") : connection.status.toLowerCase()}
                </Badge>
              </div>

              <div className="space-y-2">
                <div className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
                  {t("grantedScopesLabel")}
                </div>
                <div className="flex flex-wrap gap-1.5">
                  {connection.grantedScopes.map((s: string) => {
                    const info = SCOPE_INFO[s as keyof typeof SCOPE_INFO];
                    const Icon = info?.icon || Link2;
                    const label = info ? t(info.labelKey) : friendlyScopeLabel(s);
                    return (
                      <Badge key={s} variant="outline" className="gap-1 font-normal text-muted-foreground" title={s}>
                        <Icon size={12} /> {label}
                      </Badge>
                    );
                  })}
                </div>
              </div>

              <div className="flex flex-wrap gap-2 pt-1">
                {!connection.grantedScopes.includes(YOUTUBE_UPLOAD_SCOPE) && (
                  <Button type="button" size="sm" disabled={busy} onClick={handleEnableYoutubeUpload}>
                    <Youtube aria-hidden="true" size={14} /> {t("enableYoutubeUploadButton")}
                  </Button>
                )}
                <Button type="button" variant="outline" size="sm" disabled={busy} onClick={handleDisconnect}>
                  {t("disconnectButton")}
                </Button>
              </div>
            </>
          )}

          {loaded && !connection && oauthConfig && !oauthConfig.configured && (
            <EmptyState
              icon={Link2}
              title={t("notConfiguredTitle")}
              hint={
                oauthConfig.problem === "redirect_not_loopback"
                  ? t("notLoopbackHint", { redirectUri: oauthConfig.redirectUri })
                  : oauthConfig.mode === "loopback"
                    ? t("notConfiguredHint")
                    : t("notConfiguredWebHint", { redirectUri: oauthConfig.redirectUri || "-" })
              }
            />
          )}

          {loaded && !connection && !(oauthConfig && !oauthConfig.configured) && (
            <EmptyState
              icon={Link2}
              title={t("emptyTitle")}
              hint={t("emptyHint")}
              action={
                <Button type="button" size="sm" disabled={busy} onClick={handleConnect}>
                  <Boxes aria-hidden="true" size={14} /> {t("connectButton")}
                </Button>
              }
            />
          )}
        </CardContent>
      </Card>
    </div>
  );
}
