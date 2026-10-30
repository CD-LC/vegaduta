"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { motion } from "motion/react";
import { Check, Copy, Download, Link2, Loader2, QrCode, Smartphone } from "lucide-react";
import { Button } from "../ui/button";
import { createShare } from "../try/demoClient";
import { qrPath, QR_QUIET } from "../../lib/qr";
import { getMobileApkDownload } from "../../lib/mobileApp";
import { TONES } from "./studioTheme";
import { useTranslations } from "../../lib/i18n";

// "Scan it. It's on your phone." The share link the demo backend already mints
// (POST /api/demo/session/share -> /a/<id>) shown the way it should be shown on
// a desktop: as a QR code drawn locally (no service sees the link) beside the
// plain URL. Honesty contract carried over from SharePanel: anyone with the link
// can talk to the agent, up to five people, on the same free limits, and it goes
// away with the sandbox.
//
// Below it, the Android app - a sideload APK today, so the size and checksum are
// stated. Signing in there with the account made from this page is what turns a
// demo into an agent that lives on the phone.

type Props = {
  token: string;
  agentName: string | null;
  readOnly: boolean;
  signupHref: string;
  /** Lifted so the sticky phone can show the real QR too. */
  onShareUrl: (url: string) => void;
};

export default function PocketPanel({ token, agentName, readOnly, signupHref, onShareUrl }: Props) {
  const tHC = useTranslations("hcComponentsStudioPocketPanel");
  const apk = getMobileApkDownload();
  const [busy, setBusy] = useState(false);
  const [shareUrl, setShareUrl] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [android, setAndroid] = useState(false);

  useEffect(() => {
    setAndroid(/android/i.test(navigator.userAgent));
  }, []);

  const create = useCallback(async () => {
    if (busy) return;
    setBusy(true);
    setMessage(null);
    const r = await createShare(token);
    if (r.ok && r.shareUrl) {
      setShareUrl(r.shareUrl);
      onShareUrl(r.shareUrl);
    } else {
      setMessage(r.message || "We couldn't create the link just now. Your sandbox is fine - try again in a moment.");
    }
    setBusy(false);
  }, [busy, token, onShareUrl]);

  const copy = async () => {
    if (!shareUrl) return;
    try {
      await navigator.clipboard.writeText(shareUrl);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      // clipboard blocked - the URL is in the box to select by hand
    }
  };

  const qr = useMemo(() => {
    if (!shareUrl) return null;
    try {
      return qrPath(shareUrl, "M");
    } catch {
      return null;
    }
  }, [shareUrl]);

  return (
    <section
      className="overflow-hidden rounded-3xl border border-sky-500/30 bg-gradient-to-br from-sky-500/10 via-background to-violet-500/10"
      data-tour="pocket"
    >
      <div className="grid gap-6 p-5 sm:p-7 md:grid-cols-[minmax(0,1fr)_220px]">
        <div>
          <div
            className={`mb-2 inline-flex items-center gap-2 text-xs font-medium uppercase tracking-wide ${TONES.sky.text}`}
          >
            <Smartphone aria-hidden="true" className="h-4 w-4 shrink-0" /> {tHC("s14")}
          </div>
          <h3 className="text-2xl font-semibold tracking-tight">
            {agentName
              ? `Put “${agentName.length > 26 ? `${agentName.slice(0, 25)}…` : agentName}” on a phone.`
              : "Put your agent on a phone."}
          </h3>
          <p className="mt-1.5 max-w-lg text-sm text-muted-foreground">{tHC("s13")}</p>

          {!shareUrl ? (
            <div className="mt-4">
              <Button className={TONES.sky.cta} size="lg" onClick={create} disabled={busy || readOnly}>
                {busy ? (
                  <Loader2 aria-hidden="true" className="me-2 h-4 w-4 animate-spin shrink-0" />
                ) : (
                  <QrCode aria-hidden="true" className="me-2 h-4 w-4 shrink-0" />
                )}{" "}
                {tHC("s12")}
              </Button>
              {message && <p className="mt-2 text-sm text-destructive">{message}</p>}
              <p className="mt-2 max-w-md text-xs text-muted-foreground">{tHC("s11")}</p>
            </div>
          ) : (
            <div className="mt-4 space-y-2">
              <div className="flex items-center gap-2 rounded-xl border border-border/60 bg-background/70 p-1.5">
                <Link2 aria-hidden="true" className="ms-2 h-4 w-4 shrink-0 text-muted-foreground" />
                <input
                  readOnly
                  value={shareUrl}
                  onFocus={(e) => e.currentTarget.select()}
                  aria-label={tHC("s10")}
                  className="min-w-0 flex-1 bg-transparent px-1 text-sm outline-none"
                />
                <Button size="sm" variant="outline" onClick={copy}>
                  {copied ? (
                    <Check aria-hidden="true" className="me-1 h-3.5 w-3.5 shrink-0" />
                  ) : (
                    <Copy aria-hidden="true" className="me-1 h-3.5 w-3.5 shrink-0" />
                  )}{" "}
                  {copied ? "Copied" : "Copy"}
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">{tHC("s9")}</p>
            </div>
          )}
        </div>

        <div className="flex items-center justify-center">
          <motion.div
            key={shareUrl ?? "empty"}
            initial={{ scale: 0.85, opacity: 0, rotate: -3 }}
            animate={{ scale: 1, opacity: 1, rotate: 0 }}
            transition={{ type: "spring", stiffness: 220, damping: 16 }}
            className="rounded-2xl bg-white p-2 shadow-xl shadow-sky-500/20"
          >
            {qr ? (
              <svg
                viewBox={`0 0 ${qr.size + QR_QUIET * 2} ${qr.size + QR_QUIET * 2}`}
                className="h-[196px] w-[196px]"
                role="img"
                aria-label={tHC("s8")}
              >
                <path d={qr.d} fill="#09090b" />
              </svg>
            ) : (
              <div className="flex h-[196px] w-[196px] flex-col items-center justify-center gap-2 text-center text-xs text-zinc-500">
                <QrCode aria-hidden="true" className="h-10 w-10 text-zinc-300 shrink-0" />
                {tHC("s7")}
              </div>
            )}
          </motion.div>
        </div>
      </div>

      <div className="flex flex-col gap-3 border-t border-border/50 bg-background/50 p-5 sm:flex-row sm:items-center sm:px-7">
        <div className="flex-1">
          <div className="text-sm font-medium">
            {android ? "You are on Android - take it with you." : "Want it as an app?"}
          </div>
          <p className="mt-0.5 text-xs text-muted-foreground">{tHC("s6")}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {apk && (
            <Button asChild variant={android ? "default" : "outline"} size="sm">
              <a href={apk.href} download>
                <Download aria-hidden="true" className="me-1.5 h-3.5 w-3.5 shrink-0" /> {tHC("s5")}
                {apk.sizeMb ? ` · ${apk.sizeMb} MB` : ""}
              </a>
            </Button>
          )}
          <Button asChild size="sm" className={TONES.violet.cta}>
            <a href={signupHref}>{tHC("s4")}</a>
          </Button>
        </div>
      </div>
      {apk && (
        <p className="border-t border-border/40 px-5 py-2 text-[10.5px] text-muted-foreground sm:px-7">
          {tHC("s3")}
          {apk.version ? `, version ${apk.version}` : ""} {tHC("s2")}
          {apk.sha256 && (
            <>
              {" "}
              {tHC("s1")} <span className="break-all font-mono">{apk.sha256}</span>
            </>
          )}
        </p>
      )}
    </section>
  );
}
