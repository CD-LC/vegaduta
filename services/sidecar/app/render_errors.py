"""The one exception type every render module raises (see render.py)."""

from __future__ import annotations


class RenderError(ValueError):
    """Render failed for a reason the caller can act on.

    Subclasses ValueError so main.py's existing convention (ValueError from
    a worker module -> 400) applies without a new handler.
    """
