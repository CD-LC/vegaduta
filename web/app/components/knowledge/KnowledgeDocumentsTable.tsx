"use client";

import { useTranslations } from "../../lib/i18n";
import { Badge } from "../ui/badge";
import { Table, TableBody, TableRow, TableCell } from "../ui/table";
import KnowledgeDocumentActions from "./KnowledgeDocumentActions";
import { extensionOf, fileBadge, type KnowledgeDocumentItem } from "./knowledgeFormat";

/** The paged file table: file-type chip, real pipeline status chip, failure reason and row actions. */
export default function KnowledgeDocumentsTable({
  docs,
  onError,
  onDeleted,
}: {
  docs: KnowledgeDocumentItem[];
  onError: (message: string) => void;
  onDeleted: () => void;
}) {
  const t = useTranslations("knowledgePanel");
  return (
    <Table>
      <TableBody>
        {docs.map((doc) => {
          const ext = doc.fileExtension || extensionOf(doc.filename) || "";
          const badge = fileBadge(ext);
          // Real pipeline stages (KnowledgeDocumentStatus) - see
          // that enum's javadoc for what each one means and why
          // EXTRACTING/EMBEDDING are often only visible for
          // milliseconds on the synchronous upload path.
          const statusText =
            doc.status === "UPLOADED"
              ? t("statusUploaded")
              : doc.status === "QUEUED"
                ? t("statusQueued")
                : doc.status === "EXTRACTING"
                  ? t("statusExtracting")
                  : doc.status === "EMBEDDING"
                    ? t("statusEmbedding")
                    : doc.status === "READY"
                      ? t("statusReady")
                      : doc.status === "FAILED"
                        ? t("statusFailed")
                        : doc.status.toLowerCase();
          // One hue per state, via the shared .state-pill utility
          // (Badge variant="state") - same chip every other console
          // table uses, instead of raw coloured text.
          const statusHue =
            doc.status === "FAILED"
              ? "var(--state-error)"
              : doc.status === "READY"
                ? "var(--state-success)"
                : doc.status === "UPLOADED" ||
                    doc.status === "QUEUED" ||
                    doc.status === "EXTRACTING" ||
                    doc.status === "EMBEDDING"
                  ? "var(--state-warning)"
                  : "var(--state-idle)";
          // A failure reason belongs with the file, not in the
          // status column: rendered in the chip it wrapped to
          // ~7 lines and blew the row to 130px (QA-08). The chip
          // stays a chip; the reason sits under the filename.
          const failureReason = doc.status === "FAILED" && doc.errorMessage ? doc.errorMessage : null;
          return (
            <TableRow key={doc.id}>
              <TableCell className="w-full">
                <span className="flex min-w-0 items-center gap-2">
                  <span
                    className="flex h-6 w-9 shrink-0 items-center justify-center rounded text-xs font-bold"
                    style={{ background: badge.bg, color: badge.color }}
                  >
                    {badge.label}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm text-foreground">{doc.filename}</span>
                    {failureReason && (
                      <span className="mt-0.5 block text-xs leading-snug text-muted-foreground" title={failureReason}>
                        {failureReason}
                      </span>
                    )}
                  </span>
                </span>
              </TableCell>
              <TableCell className="hidden whitespace-nowrap text-xs text-muted-foreground sm:table-cell">
                {ext.replace(".", "").toUpperCase()}
              </TableCell>
              <TableCell className="whitespace-nowrap text-right text-sm text-muted-foreground tabular-nums">
                {doc.chunkCount ?? "—"}
              </TableCell>
              <TableCell className="whitespace-nowrap">
                <Badge variant="state" state={statusHue} title={failureReason ?? undefined}>
                  {statusText}
                </Badge>
              </TableCell>
              <TableCell className="text-right">
                <KnowledgeDocumentActions doc={doc} onError={onError} onDeleted={onDeleted} />
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
