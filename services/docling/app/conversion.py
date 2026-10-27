"""
Docling-backed document conversion for /extract.

A thin adapter over IBM's Docling parser (docling-project.github.io/docling):
DocumentConverter turns PDF, Office, HTML, CSV and image bytes into a unified
document model, exported here as Markdown - richer structure (layout, tables,
reading order, OCR built in) than the sidecar's per-format extractors produce,
which is exactly why core's DoclingConversionService prefers this service for
the formats it covers before optionally falling back to the sidecar.

Same failure contract as services/sidecar/app/extraction.py: a file the parser
cannot handle raises ValueError with a specific message - main.py's /extract
turns a ValueError into a 400, not a 500, since a bad upload is a client
problem, not a service bug. Anything else propagates so an unexpected parser
bug is never relabeled a client mistake. And same isolation posture: file
parsers are a real, historically-exploited attack surface, so conversion runs
here, in a separate process, where a parser crash takes down only this request.
"""

import io
import logging

logger = logging.getLogger(__name__)

# The formats this service accepts. Mirrors DoclingConversionService.EXTENSIONS
# in core (core/knowledge/.../DoclingConversionService.java) - the two lists
# MUST move together: the Java side pre-filters uploads against its own copy
# before any bytes are sent, so an extension it accepts but this rejects
# surfaces as a 400 where the caller expected a clean pre-flight rejection.
SUPPORTED_EXTENSIONS = {
    ".pdf",
    ".docx",
    ".pptx",
    ".xlsx",
    ".html",
    ".htm",
    ".csv",
    ".png",
    ".jpg",
    ".jpeg",
    ".webp",
    ".tiff",
    ".tif",
    ".bmp",
}

_converter = None


def _document_converter():
    """Lazy singleton.

    DocumentConverter() pulls the whole pipeline stack (torch-scale dependency
    trees, the layout and OCR model wrappers) - far too heavy to build at
    module import, where it would slow every app boot and break tests that
    exercise /health or the auth layer without docling installed. The FIRST
    conversion still pays it, plus the model-artifact download Docling needs
    (the Dockerfile pre-bakes those via `docling-tools models download`; the
    README documents the download-on-first-use cold start for builds that opt
    out).
    """
    global _converter
    if _converter is None:
        from docling.document_converter import DocumentConverter

        _converter = DocumentConverter()
    return _converter


def convert_to_markdown(filename: str, content: bytes) -> str:
    """Convert raw file bytes to Markdown.

    Raises ValueError naming the file problem - unsupported extension, bytes
    Docling cannot parse, or an empty result - which main.py's /extract maps
    to a 400. Anything else propagates as a 500.
    """
    _extension_of(filename)

    # Imported here rather than at module top for the same reason the
    # converter itself is lazy: docling's import cost is real, and a request
    # that dies in the extension check should not have paid it.
    from docling.datamodel.document import DocumentStream
    from docling.exceptions import ConversionError

    try:
        result = _document_converter().convert(
            DocumentStream(name=filename, stream=io.BytesIO(content))
        )
    except ConversionError as e:
        # Docling's own "I could not make a document out of this" (the default
        # raises_on_error=True rejects instead of returning a failed result) -
        # the bad-upload case, so ValueError like the sidecar's extractors.
        raise ValueError(f"Could not convert {filename}: {e}") from e

    try:
        text = result.document.export_to_markdown() if result.document is not None else ""
    except Exception as e:
        raise ValueError(f"Could not export {filename} to markdown: {e}") from e

    if not text.strip():
        raise ValueError(f"Conversion produced no readable text for {filename}")
    return text


def _extension_of(filename: str) -> str:
    lowered = filename.lower()
    for ext in SUPPORTED_EXTENSIONS:
        if lowered.endswith(ext):
            return ext
    raise ValueError(f"Unsupported file extension for conversion: {filename}")
