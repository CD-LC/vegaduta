"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { ssoApi, type MintedScimToken, type ScimTokenDto } from "../../lib/api/sso";
import { useTranslations } from "../../lib/i18n";
import { usePagination } from "../../lib/usePagination";
import PaginationControls from "../PaginationControls";
import { EmptyState, Section } from "../ax/page-kit";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Skeleton } from "../ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : "-");
const msg = (e: unknown) => (e instanceof Error ? e.message : String(e));

export default function ScimTokensTable() {
    const tHC = useTranslations("hcComponentsSsoScimTokensTable");
  const t = useTranslations("privacySso");
  const [tokens, setTokens] = useState<ScimTokenDto[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [label, setLabel] = useState("");
  const [minting, setMinting] = useState(false);
  const [mintError, setMintError] = useState<string | null>(null);
  const [revealed, setRevealed] = useState<MintedScimToken | null>(null);
  const [copied, setCopied] = useState(false);
  const [revoking, setRevoking] = useState<ScimTokenDto | null>(null);
  const [revokeError, setRevokeError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoadError(null);
    setTokens(null);
    ssoApi
      .listTokens()
      .then(setTokens)
      .catch((e: unknown) => setLoadError(msg(e)));
  }, []);
  useEffect(load, [load]);

  const pg = usePagination(useMemo(() => tokens ?? [], [tokens]));

  const mint = async () => {
    setMinting(true);
    setMintError(null);
    try {
      const res = await ssoApi.mintToken(label.trim());
      setRevealed(res);
      setCopied(false);
      setLabel("");
      load();
    } catch (e) {
      setMintError(msg(e));
    } finally {
      setMinting(false);
    }
  };

  const revoke = async () => {
    const target = revoking;
    setRevoking(null);
    if (!target) return;
    setRevokeError(null);
    try {
      await ssoApi.revokeToken(target.id);
      load();
    } catch (e) {
      setRevokeError(msg(e));
    }
  };

  const copy = async () => {
    if (!revealed) return;
    try {
      await navigator.clipboard.writeText(revealed.token);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };

  return (
    <Section title={t("scimTitle")} description={t("scimIntro")}>
      <form
        className="flex flex-col gap-2 sm:flex-row sm:items-end"
        onSubmit={(e) => {
          e.preventDefault();
          void mint();
        }}
      >
        <div className="grid flex-1 gap-1.5">
          <Label htmlFor="scim-label">{t("scimLabel")}</Label>
          <Input
            id="scim-label"
            value={label}
            maxLength={120}
            placeholder={tHC("s1")}
            onChange={(e) => setLabel(e.target.value)}
          />
        </div>
        <Button type="submit" disabled={minting}>
          {minting ? t("scimCreating") : t("scimCreate")}
        </Button>
      </form>
      {mintError && (
        <Alert variant="destructive">
          <AlertDescription>{mintError}</AlertDescription>
        </Alert>
      )}
      {revokeError && (
        <Alert variant="destructive">
          <AlertDescription>{revokeError}</AlertDescription>
        </Alert>
      )}

      {revealed && (
        <Alert variant="warning">
          <AlertTitle>{t("scimRevealTitle", { label: revealed.label })}</AlertTitle>
          <AlertDescription>
            <p>{t("scimRevealBody")}</p>
            <div className="mt-2 flex flex-col gap-2 sm:flex-row">
              <Input
                readOnly
                aria-label={t("scimTokenValue")}
                value={revealed.token}
                onFocus={(e) => e.currentTarget.select()}
              />
              <Button type="button" variant="outline" onClick={() => void copy()}>
                {copied ? t("copied") : t("copy")}
              </Button>
              <Button type="button" variant="ghost" onClick={() => setRevealed(null)}>
                {t("scimDismiss")}
              </Button>
            </div>
          </AlertDescription>
        </Alert>
      )}

      {loadError ? (
        <Alert variant="destructive">
          <AlertTitle>{t("loadFailedTitle")}</AlertTitle>
          <AlertDescription>
            <p>{loadError}</p>
            <Button type="button" variant="outline" size="sm" className="mt-2" onClick={load}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      ) : tokens === null ? (
        <Skeleton className="h-24 w-full" aria-label={t("loading")} />
      ) : tokens.length === 0 ? (
        <EmptyState title={t("scimEmptyTitle")} description={t("scimEmptyBody")} />
      ) : (
        <div className="space-y-3">
          <div className="overflow-x-auto rounded-lg border border-border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("scimLabel")}</TableHead>
                  <TableHead>{t("created")}</TableHead>
                  <TableHead>{t("lastUsed")}</TableHead>
                  <TableHead>
                    <span className="sr-only">{t("actions")}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {pg.pageItems.map((tok) => (
                  <TableRow key={tok.id}>
                    <TableCell className="font-medium">{tok.label}</TableCell>
                    <TableCell className="text-muted-foreground">{fmt(tok.createdAt)}</TableCell>
                    <TableCell className="text-muted-foreground">
                      {tok.lastUsedAt ? fmt(tok.lastUsedAt) : t("never")}
                    </TableCell>
                    <TableCell className="text-end">
                      <Button type="button" variant="ghost" size="sm" onClick={() => setRevoking(tok)}>
                        {t("scimRevoke")}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <PaginationControls
            page={pg.page}
            totalPages={pg.totalPages}
            totalItems={pg.totalItems}
            pageSize={pg.pageSize}
            onPageChange={pg.setPage}
            onPageSizeChange={pg.setPageSize}
            itemLabel={t("tokensLabel")}
          />
        </div>
      )}

      <AlertDialog open={revoking !== null} onOpenChange={(o) => !o && setRevoking(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("scimRevokeTitle")}</AlertDialogTitle>
            <AlertDialogDescription>{t("scimRevokeBody", { label: revoking?.label ?? "" })}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
            <AlertDialogAction onClick={() => void revoke()}>{t("scimRevokeAction")}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Section>
  );
}
