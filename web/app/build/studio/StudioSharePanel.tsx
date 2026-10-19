"use client";

// Split out of StudioSurface.tsx (behaviour unchanged).
import Link from "next/link";
import { useEffect, useState } from "react";
import { Check, Copy, Download, Loader2, MessageCircle, Share2, Smartphone } from "lucide-react";
import { Button } from "../../components/ui/button";
import { DomainsPanel } from "./DomainsPanel";
import { appBuilder, APP_BUILDER_CONFIGURED, newProjectId } from "../lib";
import { QR_MAX_URL, downloadHtml, qrSvg, shareUrl } from "./share";
import { titleOf, type StudioPublish } from "./store";

const GITHUB_ERROR = /github|git|repo(sitory)?|installation|409/i;

/** Shows WHY a publish failed; GitHub-shaped failures get the fix, not just the symptom. */
function PublishError({ message, className = "" }: { message: string; className?: string }) {
  const github = GITHUB_ERROR.test(message);
  return (
    <div role="alert" className={`flex flex-col gap-1 rounded-md border border-destructive/30 bg-destructive/5 p-2 text-[11px] ${className}`}>
      <p className="font-medium text-destructive">Publish failed</p>
      {github ? (
        <p className="text-muted-foreground">
          Your GitHub isn&apos;t connected yet. Connect it first, then publish again.{" "}
          <Link href="/build/github" className="font-medium text-primary underline underline-offset-2">
            Open the GitHub connection page
          </Link>
        </p>
      ) : null}
      <p className="break-words font-mono text-muted-foreground">{message}</p>
    </div>
  );
}

export default function SharePanel({
  html,
  name,
  publish,
  canPublish,
  onPublish,
  onUnpublish,
  onError,
  onOpenApp,
}: {
  html: string;
  name: string;
  publish: StudioPublish | null;
  /** Only the app's owner may publish / take offline / manage domains. */
  canPublish: boolean;
  onPublish: () => Promise<void>;
  onUnpublish: () => Promise<void>;
  onError: (message: string) => void;
  onOpenApp?: (projectId: string) => void;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [pubCopied, setPubCopied] = useState(false);
  const [pubState, setPubState] = useState<"idle" | "working" | "error">("idle");
  const [pubError, setPubError] = useState("");
  const [apk, setApk] = useState<{ state: "idle" | "working" | "done" | "error"; message?: string; projectId?: string }>({ state: "idle" });

  useEffect(() => {
    let live = true;
    setUrl(null);
    setQr(null);
    const appName = titleOf(html, name);
    void shareUrl(window.location.origin, html, appName)
      .then(async (u) => {
        if (!live) return;
        setUrl(u);
        const svg = await qrSvg(u);
        if (live) setQr(svg);
      })
      .catch(() => live && setUrl(""));
    return () => {
      live = false;
    };
  }, [html, name]);

  const copy = async () => {
    if (!url) return;
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 1600);
    } catch {
      /* clipboard blocked */
    }
  };

  const nativeShare = async () => {
    if (!url) return;
    try {
      await (navigator as Navigator & { share?: (d: ShareData) => Promise<void> }).share?.({ title: name, url });
    } catch {
      /* cancelled */
    }
  };

  const buildApk = async () => {
    setApk({ state: "working" });
    try {
      const projectId = newProjectId();
      await appBuilder.createProject({ project_id: projectId, template_id: "capacitor-app" });
      const res = await appBuilder.applyEdits(projectId, {
        instruction: `Phone app studio: ${name}`,
        files: [
          { path: "index.html", content: html },
          { path: "src/main.js", content: "// The UI lives in index.html (built in the VegaDuta Phone app studio).\n" },
        ],
      });
      if (res && res.success === false) throw new Error("The build check failed. Open the project to see why.");
      setApk({ state: "done", projectId });
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      setApk({
        state: "error",
        message: GITHUB_ERROR.test(msg) || /connect/i.test(msg) ? "Connect your GitHub first - the APK is built in your own repository." : msg,
      });
    }
  };

  const hasShare = typeof navigator !== "undefined" && "share" in navigator;
  const fits = url !== null && url.length <= QR_MAX_URL;

  const doPublish = async () => {
    setPubState("working");
    setPubError("");
    try {
      await onPublish();
      setPubState("idle");
    } catch (e) {
      const msg = e instanceof Error && e.message ? e.message : "Publish failed.";
      setPubState("error");
      setPubError(msg);
      onError(msg);
    }
  };
  const doUnpublish = async () => {
    setPubState("working");
    try {
      await onUnpublish();
      setPubState("idle");
    } catch (e) {
      setPubState("error");
      onError(e instanceof Error ? e.message : "Couldn't take it offline.");
    }
  };
  const copyPublish = async () => {
    if (!publish?.publicUrl) return;
    try {
      await navigator.clipboard.writeText(publish.publicUrl);
      setPubCopied(true);
      setTimeout(() => setPubCopied(false), 1600);
    } catch {
      /* clipboard blocked */
    }
  };

  return (
    <div className="flex flex-col gap-4">
      {/* Publish - the "real product" path: server-side copy, short link,
          live sign-in, hosted AI, per-app end users. */}
      <fieldset disabled={!canPublish} className="m-0 min-w-0 rounded-xl border border-border bg-accent/30 p-3">
        <legend className="sr-only">Publish</legend>
        <p className="text-sm font-semibold text-foreground">Publish it</p>
        {!canPublish && <p className="mt-0.5 text-[11px] text-amber-700 dark:text-amber-300">Only the app&apos;s owner can publish it.</p>}
        <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">
          A short link anyone can open - with working sign-in for your users, hosted AI and your wired
          agents/workflows behind it. Republish any time to push the latest version to the same link.
        </p>
        {publish?.slug ? (
          <div className="mt-2 flex flex-col gap-2">
            {publish.offlineAt ? (
              <p className="rounded-md bg-amber-500/10 p-2 text-[11px] text-amber-700 dark:text-amber-300">
                Offline right now - the link 404s. Publish again to bring the same link back.
              </p>
            ) : (
              <div className="flex items-center gap-1.5">
                <code className="min-w-0 flex-1 truncate rounded-lg border border-border bg-background px-2 py-1.5 text-[11px] text-foreground">
                  {publish.publicUrl}
                </code>
                <Button size="sm" variant="outline" onClick={copyPublish} className="h-7 gap-1 text-[11px]">
                  {pubCopied ? <Check aria-hidden="true" size={11} /> : <Copy aria-hidden="true" size={11} />} {pubCopied ? "Copied" : "Copy"}
                </Button>
              </div>
            )}
            <div className="flex items-center gap-1.5">
              <Button size="sm" onClick={doPublish} disabled={pubState === "working"} className="h-7 gap-1 text-[11px]">
                {pubState === "working" ? <Loader2 aria-hidden="true" size={11} className="animate-spin" /> : <Share2 aria-hidden="true" size={11} />}{" "}
                {publish.offlineAt ? "Publish again" : "Republish latest"}
              </Button>
              {!publish.offlineAt && (
                <Button size="sm" variant="outline" onClick={doUnpublish} disabled={pubState === "working"} className="h-7 text-[11px]">
                  Take offline
                </Button>
              )}
            </div>
            {pubState === "error" && <PublishError message={pubError} />}
          </div>
        ) : (
          <div className="mt-2">
            <Button size="sm" onClick={doPublish} disabled={pubState === "working"} className="gap-1.5">
              {pubState === "working" ? <Loader2 aria-hidden="true" size={13} className="animate-spin" /> : <Share2 aria-hidden="true" size={13} />} Publish this app
            </Button>
            {pubState === "error" && <PublishError message={pubError} className="mt-1.5" />}
          </div>
        )}
      </fieldset>

      {canPublish && publish?.appId && !publish.offlineAt && <DomainsPanel appId={publish.appId} />}

      <div>
        <p className="text-sm font-semibold text-foreground">Put it on your phone</p>
        <p className="mt-0.5 text-xs text-muted-foreground">
          Or send the self-contained link - the whole app travels inside it, nothing is uploaded. On the phone, choose &quot;Add to Home Screen&quot; to keep it as an app.
        </p>
      </div>
      <div className="flex justify-center">
        {url === null ? (
          <Loader2 aria-hidden="true" size={18} className="my-10 animate-spin text-muted-foreground" />
        ) : fits && qr ? (
          <div className="rounded-xl bg-white p-2" style={{ width: 200 }} dangerouslySetInnerHTML={{ __html: qr }} aria-label="QR code for the app link" />
        ) : (
          <p className="rounded-lg bg-secondary p-3 text-xs text-muted-foreground">
            {fits ? "QR code unavailable right now." : "This app is too big for a QR code, so send the link to your phone instead."}
          </p>
        )}
      </div>
      <div className="grid grid-cols-2 gap-2">
        <Button size="sm" variant="outline" onClick={copy} disabled={!url} className="gap-1.5">
          {copied ? <Check aria-hidden="true" size={13} /> : <Copy aria-hidden="true" size={13} />} {copied ? "Copied" : "Copy link"}
        </Button>
        <Button asChild size="sm" variant="outline" className="gap-1.5">
          <a href={url ? `https://wa.me/?text=${encodeURIComponent(`${name}: ${url}`)}` : undefined} target="_blank" rel="noopener noreferrer">
            <MessageCircle aria-hidden="true" size={13} /> WhatsApp
          </a>
        </Button>
        {hasShare && (
          <Button size="sm" variant="outline" onClick={nativeShare} disabled={!url} className="gap-1.5">
            <Share2 aria-hidden="true" size={13} /> Share…
          </Button>
        )}
        <Button size="sm" variant="outline" onClick={() => downloadHtml(html, name)} className="gap-1.5">
          <Download aria-hidden="true" size={13} /> .html file
        </Button>
        {url && (
          <Button asChild size="sm" variant="ghost" className="col-span-2 gap-1.5">
            <a href={url} target="_blank" rel="noopener noreferrer">
              <Smartphone aria-hidden="true" size={13} /> Open full screen
            </a>
          </Button>
        )}
      </div>
      {APP_BUILDER_CONFIGURED && (
        <div className="flex flex-col gap-2 border-t border-border pt-3">
          <p className="text-sm font-semibold text-foreground">Make an Android app (APK)</p>
          <p className="text-xs text-muted-foreground">
            Pushes this app into a Capacitor project in your GitHub. The repository&apos;s Actions then build an installable APK.
          </p>
          {apk.state === "done" ? (
            <Button size="sm" onClick={() => apk.projectId && onOpenApp?.(apk.projectId)}>
              Open the project
            </Button>
          ) : (
            <Button size="sm" variant="outline" onClick={buildApk} disabled={apk.state === "working"} className="gap-1.5">
              {apk.state === "working" ? <Loader2 aria-hidden="true" size={13} className="animate-spin" /> : <Smartphone aria-hidden="true" size={13} />} Build Android app
            </Button>
          )}
          {apk.state === "error" && <PublishError message={apk.message || "The build failed."} />}
        </div>
      )}
    </div>
  );
}
