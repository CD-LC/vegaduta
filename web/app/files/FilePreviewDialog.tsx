"use client";

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../components/ui/alert-dialog";
import { useTranslations } from "../lib/i18n";
import type { FileRow } from "./fileRows";

export interface FilePreview {
  row: FileRow;
  url: string;
  kind: "image" | "audio" | "video";
}

/** Modal image / audio / video preview of a downloaded blob. The page owns (and revokes) the blob URL. */
export default function FilePreviewDialog({ preview, onClose }: { preview: FilePreview | null; onClose: () => void }) {
  const t = useTranslations("filesPage");
  return (
    <AlertDialog open={!!preview} onOpenChange={(open) => !open && onClose()}>
      <AlertDialogContent className="max-w-2xl">
        <AlertDialogHeader>
          <AlertDialogTitle className="truncate">{preview?.row.name}</AlertDialogTitle>
          <AlertDialogDescription>{preview?.row.detail || t("previewFallback")}</AlertDialogDescription>
        </AlertDialogHeader>
        {preview?.kind === "image" && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={preview.url} alt={preview.row.name} className="max-h-[60vh] w-full rounded-md object-contain" />
        )}
        {preview?.kind === "audio" && <audio src={preview.url} controls autoPlay className="w-full" />}
        {preview?.kind === "video" && <video src={preview.url} controls autoPlay className="max-h-[60vh] w-full rounded-md" />}
        <AlertDialogFooter>
          <AlertDialogCancel>{t("close")}</AlertDialogCancel>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
