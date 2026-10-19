"use client";

import { useCallback, useEffect, useState } from "react";
import { api, API_BASE } from "../lib/api";
import { auth } from "../lib/auth";
import { filenameFromContentDisposition } from "../lib/contentDisposition";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { viewAsHeaders } from "../lib/viewAs";

// Master Roadmap 2026 item #22 (GDPR/data-residency basics, §G) - tenant-wide
// "Request data export" / "Request account deletion", extracted verbatim from
// admin/maintenance/page.tsx in the granular-RBAC pass (2026-09-23) so the
// same section renders in BOTH places it belongs: the full-admin
// /admin/maintenance "data-privacy" tab and the scoped /admin/data-privacy
// page a delegated MANAGE_DATA_GOVERNANCE holder can reach (maintenance
// itself is still role-gated and would be a dead end for them).
//
// Deliberately NOT routed through app/lib/api.ts (same local-fetch shape the
// maintenance page already used): POST /api/tenant/data-export returns a
// file download, POST /api/tenant/data-deletion a JSON ack - both now gated
// by PERM_MANAGE_DATA_GOVERNANCE server-side (SecurityConfig).
export default function DataPrivacySection() {
  const [organizationName, setOrganizationName] = useState<string | null>(null);
  const [exportStatus, setExportStatus] = useState("idle"); // idle | loading | error | done
  const [exportError, setExportError] = useState<string | null>(null);
  const [deletionConfirmText, setDeletionConfirmText] = useState("");
  const [deletionStatus, setDeletionStatus] = useState("idle"); // idle | loading | error | done
  const [deletionError, setDeletionError] = useState<string | null>(null);

  useEffect(() => {
    api
      .getTenantSettings()
      .then((settings) => setOrganizationName(settings?.organizationName ?? null))
      .catch(() => setOrganizationName(null));
  }, []);

  const requestDataExport = useCallback(async () => {
    setExportStatus("loading");
    setExportError(null);
    try {
      const token = await auth.getToken();
      const response = await fetch(`${API_BASE}/api/tenant/data-export`, {
        method: "POST",
        headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...viewAsHeaders() },
      });
      if (!response.ok) {
        throw new Error(`Export failed (HTTP ${response.status})`);
      }
      const blob = await response.blob();
      const filename = filenameFromContentDisposition(
        response.headers.get("Content-Disposition"),
        "tenant-data-export.json"
      );
      const objectUrl = window.URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = objectUrl;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(objectUrl);
      setExportStatus("done");
    } catch (err) {
      setExportStatus("error");
      setExportError((err as Error).message);
    }
  }, []);

  const requestDataDeletion = useCallback(async () => {
    setDeletionStatus("loading");
    setDeletionError(null);
    try {
      const token = await auth.getToken();
      const response = await fetch(`${API_BASE}/api/tenant/data-deletion`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...viewAsHeaders(),
        },
        body: JSON.stringify({ confirmOrgName: deletionConfirmText }),
      });
      if (!response.ok) {
        let message = `Deletion failed (HTTP ${response.status})`;
        try {
          const body = await response.json();
          if (body?.message) message = body.message;
        } catch {
          // response body wasn't JSON - keep the generic message above
        }
        throw new Error(message);
      }
      setDeletionStatus("done");
    } catch (err) {
      setDeletionStatus("error");
      setDeletionError((err as Error).message);
    }
  }, [deletionConfirmText]);

  const deletionConfirmMatches = organizationName != null && deletionConfirmText.trim() === organizationName;

  const hintStyle = {
    margin: 0,
    fontSize: "0.8125rem",
    lineHeight: 1.55,
    color: "var(--text-muted)",
  } as const;

  return (
    <section className="ax-mnt-section" aria-labelledby="data-privacy-heading">
      <div className="ax-mnt-head">
        <div>
          <h2 className="ax-mnt-title" id="data-privacy-heading">
            Data &amp; privacy
          </h2>
          <div className="ax-mnt-sub">
            Tenant-wide export and deletion. Applies to the entire workspace, not a single user.
          </div>
        </div>
      </div>

      <div
        style={{
          display: "flex",
          flexWrap: "wrap",
          alignItems: "flex-start",
          justifyContent: "space-between",
          gap: "1rem",
          paddingBottom: "1.5rem",
          marginBottom: "1.5rem",
          borderBottom: "1px solid var(--surface-border)",
        }}
      >
        <div style={{ flex: "1 1 22rem", minWidth: "16rem" }}>
          <div
            style={{
              fontWeight: 600,
              fontSize: "0.8125rem",
              color: "var(--text-primary)",
              marginBottom: "0.25rem",
            }}
          >
            Request data export
          </div>
          <p style={hintStyle}>
            Downloads a JSON file containing your agents, workflows, knowledge documents, chat/ session history, and
            audit log. Platform-internal configuration is not included.
          </p>
          {exportStatus === "done" && (
            <p style={{ ...hintStyle, marginTop: "0.5rem", color: "var(--success-text)" }}>Export downloaded.</p>
          )}
          {exportStatus === "error" && (
            <div className="error-box" style={{ margin: "0.5rem 0 0" }}>
              {exportError}
            </div>
          )}
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={requestDataExport}
          disabled={exportStatus === "loading"}
          style={{ flexShrink: 0 }}
        >
          {exportStatus === "loading" ? "Preparing export…" : "Request data export"}
        </Button>
      </div>

      <div
        style={{
          border: "1px solid var(--danger-border)",
          borderRadius: "var(--radius-lg)",
          padding: "1rem 1.25rem 1.25rem",
          display: "flex",
          flexDirection: "column",
          gap: "0.5rem",
          maxWidth: "44rem",
        }}
      >
        <div style={{ fontWeight: 600, fontSize: "0.8125rem", color: "var(--danger-text)" }}>
          Request account deletion
        </div>
        <p style={hintStyle}>
          This permanently deletes your entire workspace - every agent, workflow, knowledge document, chat history, and
          user. This cannot be undone.
        </p>
        {deletionStatus === "done" ? (
          <p style={hintStyle}>This workspace&apos;s data has been deleted. You will be signed out shortly.</p>
        ) : (
          <>
            <label
              htmlFor="deletion-confirm-input"
              style={{
                ...hintStyle,
                marginTop: "0.5rem",
                fontWeight: 500,
                color: "var(--text-secondary)",
              }}
            >
              {organizationName ? `Type "${organizationName}" to confirm.` : "Type this workspace's name to confirm."}
            </label>
            <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: "0.5rem" }}>
              <Input
                id="deletion-confirm-input"
                type="text"
                value={deletionConfirmText}
                onChange={(e) => setDeletionConfirmText(e.target.value)}
                placeholder={organizationName || ""}
                style={{ flex: "0 1 20rem", minWidth: "12rem" }}
              />
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={requestDataDeletion}
                disabled={!deletionConfirmMatches || deletionStatus === "loading"}
                style={{
                  borderColor: "var(--danger-border)",
                  backgroundColor: "var(--danger-bg)",
                  color: "var(--danger-text)",
                }}
              >
                {deletionStatus === "loading" ? "Deleting…" : "Permanently delete this workspace"}
              </Button>
            </div>
            {deletionStatus === "error" && (
              <div className="error-box" style={{ margin: "0.5rem 0 0" }}>
                {deletionError}
              </div>
            )}
          </>
        )}
      </div>
    </section>
  );
}
