// Document-generation service (2026-08-05): mirrors the four artifact types
// above (image/audio/video) but the backend returns a real Spring Data Page
// envelope for its list endpoint instead of a bare array, so this gets its
// own generic Page<T> rather than reusing CommunityTemplatePage's
// client-constructed shape.
export type DocumentGenerationType = "DOCX" | "PDF" | "XLSX" | "PPTX" | "CSV" | "TXT" | "MD" | "JSON";
export type DocumentGenerationStatus = "PENDING" | "PROCESSING" | "COMPLETE" | "FAILED" | string;

export interface DocumentGenerationJob {
  id: string;
  status: DocumentGenerationStatus;
  type: DocumentGenerationType;
  filename: string;
  downloadUrl: string;
  createdAt: string;
  completedAt?: string | null;
  errorMessage?: string | null;
}

// Generic Spring Data Page envelope - `content` plus the pagination fields
// Spring always serializes (there are more fields on the real response -
// sort/pageable/etc. - but these are the only ones any caller here reads).
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// POST /api/documentgen/documents body - mirrors core's DocumentGenerationRequest
// record exactly: {type, filename, content}. The body content is NESTED under
// `content` (DocumentContent); an earlier flat shape here ({title, sections,
// rawText} at the top level) never matched the backend and failed validation
// with "content must not be null". `filename` carries no extension - the
// server appends the one for `type`.
export interface DocumentContentSection {
  heading: string | null;
  // Drawn in order, before bulletPoints.
  paragraphs: string[];
  bulletPoints: string[];
}

export interface DocumentContentTable {
  headers: string[];
  rows: string[][];
}

// Generators read whichever fields suit their format (CSV/XLSX: table; TXT:
// rawText; JSON: json; DOCX/PDF/MD: title + sections, rawText as a fallback
// when sections is empty) - leave the rest null.
export interface DocumentContent {
  title?: string | null;
  sections: DocumentContentSection[];
  table?: DocumentContentTable | null;
  rawText?: string | null;
  json?: unknown;
}

export interface DocumentGenerationRequest {
  type: DocumentGenerationType;
  filename: string;
  content: DocumentContent;
}
