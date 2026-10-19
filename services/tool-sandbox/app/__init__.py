"""tool-sandbox: isolated execution of tenant-authored Java and Python tools.

Intentionally free of imports so that ``harness/py_harness.py`` can pull in
``app.policy`` and ``app.pyvalidate`` inside the sandboxed interpreter without
dragging FastAPI - or anything else - along with them.
"""
