package com.agenticai.core.knowledge.ocr;

/**
 * Tenant-admin-selectable OCR backend for image ingestion (Knowledge Stage
 * F3b follow-up - "give an option to use any OCR AI service like google ocr
 * or any other platform in tenant admin if required, else use open source
 * OCR reading library"). Only {@link #GOOGLE_VISION} has a real sidecar call
 * implemented in this pass (see {@code extraction.py}'s {@code
 * _call_google_vision}) - {@link #AWS_TEXTRACT}/{@link
 * #AZURE_DOCUMENT_INTELLIGENCE} are modeled here so the config/credential
 * storage shape doesn't need to change when their sidecar calls are added,
 * but selecting them today falls back to Tesseract with a clear log line,
 * the same "never silently do nothing" posture as every other
 * not-yet-wired integration point in this codebase.
 */
public enum OcrProvider {
    GOOGLE_VISION,
    AWS_TEXTRACT,
    AZURE_DOCUMENT_INTELLIGENCE
}
