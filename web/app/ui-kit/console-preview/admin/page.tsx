"use client";

// /ui-kit/console-preview/admin - the real /admin route rendered against
// sample data (see ../preview-harness.tsx).
import ConsolePreview from "../preview-harness";
import AdminOverviewPage from "../../../admin/page";

export default function Preview() {
  return (
    <ConsolePreview label="/admin" admin>
      <AdminOverviewPage />
    </ConsolePreview>
  );
}
