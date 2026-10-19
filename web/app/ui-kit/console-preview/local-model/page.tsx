"use client";

// /ui-kit/console-preview/local-model - the real /providers/local-model route (the local-model wizard and its
// catalog) rendered against sample data: a 16 GB machine, so the catalog shows both groups, "Fits your machine"
// and "Needs more memory". See ../preview-harness.tsx; the /api/local-models/* answers are in
// ../../dashboard-preview/mock-routes.ts and come from components/localModel/fixtures.ts.
import ConsolePreview from "../preview-harness";
import LocalModelSetupPage from "../../../providers/local-model/page";

export default function Preview() {
  return (
    <ConsolePreview label="/providers/local-model">
      <LocalModelSetupPage />
    </ConsolePreview>
  );
}
