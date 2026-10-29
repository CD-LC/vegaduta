"use client";

import { Suspense } from "react";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import ClipStudio from "../components/clips/ClipStudio";
import { useTranslations } from "../lib/i18n";

/**
 * Clip Studio route (tier V0). Reached from the
 * Files page's per-image "Make a clip" action (/clips?imageId=<id>) or
 * directly. ClipStudio reads the imageId query param, hence the Suspense
 * boundary (useSearchParams requires one in the app router).
 */
export default function ClipsPage() {
    const tHC = useTranslations("hcClipsPage");
  return (
    <AppShell eyebrow="Create">
      <PageHeader
        title={tHC("hcClipsPage.s1")}
        subtitle="Turn a still image into a short pan-and-zoom video clip, rendered entirely in your browser."
      />
      <Suspense fallback={null}>
        <ClipStudio />
      </Suspense>
    </AppShell>
  );
}
