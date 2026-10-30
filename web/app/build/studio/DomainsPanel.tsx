"use client";

import { useCallback, useEffect, useState } from "react";
import { Check, Globe, Loader2, RefreshCw, Trash2 } from "lucide-react";
import { Button } from "../../components/ui/button";
import { domainsApi } from "./domainsApi";
import { checkHostname, dnsRecordRows, domainErrorMessage, domainErrorText, type DomainView } from "./domains";
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

/** Custom domains for a published studio app: add, see DNS records, verify, remove. */
export function DomainsPanel({ appId }: { appId: string }) {
    const tHC = useTranslations("hcBuildStudioDomainsPanel");
  const [domains, setDomains] = useState<DomainView[]>([]);
  const [host, setHost] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [confirmRemove, setConfirmRemove] = useState<DomainView | null>(null);

  const load = useCallback(async () => {
    try {
      setDomains(await domainsApi.list(appId));
    } catch (e) {
      setError(domainErrorText(e, "Could not load domains."));
    }
  }, [appId]);

  useEffect(() => {
    void load();
  }, [load]);

  const replace = (d: DomainView) => setDomains((all) => all.map((x) => (x.id === d.id ? d : x)));

  const add = async () => {
    const checked = checkHostname(host);
    if ("error" in checked) {
      setError(checked.error);
      return;
    }
    setBusy("add");
    setError("");
    try {
      const d = await domainsApi.add(appId, checked.host);
      setDomains((all) => [...all, d]);
      setHost("");
    } catch (e) {
      setError(domainErrorText(e, "Could not add the domain."));
    } finally {
      setBusy(null);
    }
  };

  const verify = async (d: DomainView) => {
    setBusy(d.id);
    setError("");
    try {
      replace(await domainsApi.verify(appId, d.id));
    } catch (e) {
      setError(domainErrorText(e, "Verification failed."));
    } finally {
      setBusy(null);
    }
  };

  const remove = async (d: DomainView) => {
    setBusy(d.id);
    setError("");
    try {
      const removed = await domainsApi.remove(appId, d.id);
      if (removed) setDomains((all) => all.filter((x) => x.id !== d.id));
      else setError("Could not remove the domain. It is still attached - try again.");
    } catch (e) {
      setError(domainErrorText(e, "Could not remove the domain."));
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="rounded-xl border border-border p-3">
      <p className="flex items-center gap-1.5 text-sm font-semibold text-foreground">
        <Globe aria-hidden="true" size={13} /> {tHC("s4")}
      </p>
      <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">
        {tHC("s5")}
      </p>
      <div className="mt-2 flex gap-1.5">
        <input
          value={host}
          onChange={(e) => setHost(e.target.value)}
          placeholder="app.yourcompany.com"
          aria-label={tHC("s4")}
          className="min-w-0 flex-1 rounded-lg border border-border bg-background px-2 py-1.5 text-[12px] text-foreground"
        />
        <Button size="sm" onClick={add} disabled={busy === "add" || !host.trim()} className="h-8 text-[11px]">
          {busy === "add" ? <Loader2 aria-hidden="true" size={11} className="animate-spin" /> : "Add"}
        </Button>
      </div>
      {error && <p className="mt-1.5 text-[11px] text-destructive">{error}</p>}
      <div className="mt-2 flex flex-col gap-2">
        {domains.map((d) => (
          <div key={d.id} className="rounded-lg border border-border bg-background p-2">
            <div className="flex items-center gap-1.5">
              <code className="min-w-0 flex-1 truncate text-[12px] text-foreground">{d.hostname}</code>
              <span
                className={
                  "rounded-full px-2 py-0.5 text-[10px] font-medium " +
                  (d.status === "VERIFIED"
                    ? "bg-emerald-500/15 text-emerald-700 dark:text-emerald-300"
                    : d.status === "FAILED"
                      ? "bg-destructive/15 text-destructive"
                      : "bg-amber-500/15 text-amber-700 dark:text-amber-300")
                }
              >
                {d.status === "VERIFIED" ? (
                  <span className="inline-flex items-center gap-0.5">
                    <Check aria-hidden="true" size={9} /> Verified
                  </span>
                ) : d.status === "FAILED" ? (
                  "Failed"
                ) : (
                  "Pending"
                )}
              </span>
            </div>
            {d.status !== "VERIFIED" && (
              <table className="mt-1.5 w-full text-start text-[11px]">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="pe-2 font-medium">Type</th>
                    <th className="pe-2 font-medium">Name</th>
                    <th className="font-medium">Value</th>
                  </tr>
                </thead>
                <tbody>
                  {dnsRecordRows(d).map((r) => (
                    <tr key={r.type + r.name} title={r.purpose}>
                      <td className="pe-2 align-top font-mono">{r.type}</td>
                      <td className="break-all pe-2 align-top font-mono">{r.name}</td>
                      <td className="break-all align-top font-mono">{r.value}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {d.lastError && d.status !== "VERIFIED" && (
              <p className="mt-1 text-[11px] text-muted-foreground">{domainErrorMessage(d.lastError)}</p>
            )}
            <div className="mt-1.5 flex gap-1.5">
              {d.status !== "VERIFIED" && (
                <Button size="sm" onClick={() => verify(d)} disabled={busy === d.id} className="h-7 gap-1 text-[11px]">
                  {busy === d.id ? (
                    <Loader2 aria-hidden="true" size={11} className="animate-spin" />
                  ) : (
                    <RefreshCw aria-hidden="true" size={11} />
                  )}{" "}
                  Verify
                </Button>
              )}
              <Button
                size="sm"
                variant="outline"
                onClick={() => (d.status === "VERIFIED" ? setConfirmRemove(d) : void remove(d))}
                disabled={busy === d.id}
                className="h-7 gap-1 text-[11px]"
              >
                <Trash2 aria-hidden="true" size={11} /> Remove
              </Button>
            </div>
          </div>
        ))}
      </div>
      <AlertDialog open={!!confirmRemove} onOpenChange={(open) => !open && setConfirmRemove(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Remove {confirmRemove?.hostname}?</AlertDialogTitle>
            <AlertDialogDescription>
              {tHC("s3")} {confirmRemove?.hostname}{tHC("s2")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep it</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => {
                const d = confirmRemove;
                setConfirmRemove(null);
                if (d) void remove(d);
              }}
            >
              {tHC("s1")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
