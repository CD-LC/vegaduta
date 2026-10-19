"use client";

import { Loader2, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import type { FaceEnrollmentItem } from "./knowledgeFormat";

/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * Face recognition (task #15) controls under the knowledge upload button:
 * the per-agent consent banner, the optional person-name field for the next
 * upload, and the collection's current enrollments. `biometricAgent` is the
 * single agent exposed to the collection (null when zero or several are).
 */
export default function FaceEnrollmentControls({
  biometricAgent,
  biometricBannerDismissed,
  setBiometricBannerDismissed,
  biometricBusy,
  setConsentConfirmOpen,
  personName,
  setPersonName,
  uploading,
  faceEnrollments,
  setForgetTarget,
}: {
  biometricAgent: any;
  biometricBannerDismissed: boolean;
  setBiometricBannerDismissed: (dismissed: boolean) => void;
  biometricBusy: boolean;
  setConsentConfirmOpen: (open: boolean) => void;
  personName: string;
  setPersonName: (name: string) => void;
  uploading: boolean;
  faceEnrollments: FaceEnrollmentItem[];
  setForgetTarget: (enrollment: FaceEnrollmentItem) => void;
}) {
  const tFace = useTranslations("faceRecognition");
  return (
    <>
      {/* Face recognition (task #15) - consent banner shown only when
       * exactly one agent is exposed to this collection (see
       * `biometricAgent`) and that agent hasn't opted in yet. */}
      {biometricAgent && biometricAgent.biometricConsentEnabled !== true && !biometricBannerDismissed && (
        <div className="mt-3 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-[var(--ax-amber)]/30 bg-[var(--ax-amber)]/10 px-3 py-2 text-sm">
          <span className="text-foreground">{tFace("bannerBody")}</span>
          <span className="flex shrink-0 items-center gap-2">
            <Button type="button" size="sm" onClick={() => setConsentConfirmOpen(true)} disabled={biometricBusy}>
              {biometricBusy ? (
                <Loader2 aria-hidden="true" size={12} className="animate-spin" />
              ) : (
                tFace("bannerEnable")
              )}
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="size-6 text-muted-foreground hover:text-foreground"
              aria-label={tFace("bannerDismiss")}
              onClick={() => setBiometricBannerDismissed(true)}
            >
              <X size={13} aria-hidden="true" />
            </Button>
          </span>
        </div>
      )}

      {/* Optional person-name field for the next upload, only offered
       * once consent is on. We don't know the file's extension until
       * after it's chosen, so this is offered up front (hinted below)
       * rather than gated on a specific already-picked file - only
       * used at upload time if that file turns out to be a photo. */}
      {biometricAgent?.biometricConsentEnabled === true && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <label htmlFor="face-person-name" className="sr-only">
            {tFace("personNamePlaceholder")}
          </label>
          <Input
            id="face-person-name"
            type="text"
            placeholder={tFace("personNamePlaceholder")}
            value={personName}
            onChange={(e) => setPersonName(e.target.value)}
            className="max-w-56"
            disabled={uploading}
          />
          <span className="text-xs text-muted-foreground">{tFace("personNameHint")}</span>
        </div>
      )}

      {biometricAgent?.biometricConsentEnabled === true && faceEnrollments.length > 0 && (
        <div className="mt-4">
          <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {tFace("enrollmentsLabel")}
          </div>
          <div className="flex flex-wrap gap-2">
            {faceEnrollments.map((enrollment) => (
              <span
                key={enrollment.id}
                className="flex items-center gap-1.5 rounded-full border border-border bg-card px-2.5 py-1 text-xs text-foreground"
              >
                {enrollment.personName} ·{" "}
                {tFace("enrolledOn", { date: new Date(enrollment.createdAt).toLocaleDateString() })}
                <button
                  type="button"
                  className="flex size-6 shrink-0 items-center justify-center rounded text-muted-foreground hover:bg-secondary hover:text-foreground"
                  aria-label={tFace("deleteEnrollmentTitle", { name: enrollment.personName })}
                  title={tFace("deleteEnrollmentTitle", { name: enrollment.personName })}
                  onClick={() => setForgetTarget(enrollment)}
                >
                  <X size={12} aria-hidden="true" />
                </button>
              </span>
            ))}
          </div>
        </div>
      )}
    </>
  );
}
