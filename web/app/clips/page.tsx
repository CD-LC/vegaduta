"use client";

import { Suspense } from "react";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import ClipStudio from "../components/clips/ClipStudio";
import LearnThisButton from "../learn/LearnThisButton";

/**
 * Clip Studio route (tier V0). Reached from the
 * Files page's per-image "Make a clip" action (/clips?imageId=<id>) or
 * directly. ClipStudio reads the imageId query param, hence the Suspense
 * boundary (useSearchParams requires one in the app router).
 */
export default function ClipsPage() {
  return (
    <AppShell eyebrow="Create">
      <PageHeader
        title="Clip Studio"
        subtitle="Turn a still image into a short pan-and-zoom video clip, rendered entirely in your browser."
        actions={<LearnThisButton path="/clips" />}
      />
      <Suspense fallback={null}>
        <ClipStudio />
      </Suspense>
    </AppShell>
  );
}
