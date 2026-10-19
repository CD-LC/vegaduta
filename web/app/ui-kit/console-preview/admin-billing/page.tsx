"use client";

// /ui-kit/console-preview/admin-billing - the real /admin/billing route rendered against
// sample data (see ../preview-harness.tsx).
import ConsolePreview from "../preview-harness";
import AdminBillingPage from "../../../admin/billing/page";

export default function Preview() {
  return (
    <ConsolePreview label="/admin/billing" admin>
      <AdminBillingPage />
    </ConsolePreview>
  );
}
