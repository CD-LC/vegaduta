"""Per-tenant cache of verified Java classes, keyed by source SHA-256.

The tenant is part of the cache path even though identical source produces
identical bytes: it keeps one tenant's artifacts off another tenant's path
entirely, makes "forget everything for this tenant" a single directory
removal, and leaves no shared-cache timing signal about what other tenants
have compiled.
"""

from __future__ import annotations

import contextlib
import os
import shutil
import tempfile
from pathlib import Path

from .isolation import TENANT_DIR_MODE, tenant_key
from .settings import Settings


class JavaArtifactCache:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    def _cache_dir(self, tenant: str, digest: str) -> Path:
        return self.settings.cache_root / tenant_key(tenant) / f"java-{digest}"

    def cached(self, tenant: str, digest: str) -> Path | None:
        candidate = self._cache_dir(tenant, digest) / "classes"
        if candidate.is_dir() and any(candidate.rglob("*.class")):
            # Touch so eviction keeps what is actually being used.
            try:
                os.utime(candidate.parent, None)
            except OSError:
                pass
            return candidate
        return None

    def store(self, tenant: str, digest: str, classes_dir: Path | None) -> Path | None:
        """Publish verified classes into the tenant's cache; returns what to run.

        The returned directory is owned by this service and read-only to the
        unprivileged run uid, which is the point: tenant code must never be able
        to rewrite the classes a later run will load, for its own tenant or
        anyone else's.
        """
        if classes_dir is None:
            return None
        target = self._cache_dir(tenant, digest)
        final = target / "classes"
        if final.is_dir():
            return final
        target.parent.mkdir(parents=True, exist_ok=True)
        # Traversable but not listable: one tenant's run cannot enumerate
        # another tenant's cached artifacts (SEC-003).
        with contextlib.suppress(OSError):
            os.chmod(target.parent, TENANT_DIR_MODE)
        staging = Path(tempfile.mkdtemp(prefix=".staging-", dir=str(target.parent)))
        try:
            shutil.copytree(classes_dir, staging / "classes")
            target.mkdir(parents=True, exist_ok=True)
            # Rename into place so a concurrent reader never sees a half-copied
            # artifact; if another request won the race, keep theirs.
            try:
                os.replace(staging / "classes", final)
            except OSError:
                # Another request cached the same source first, or the cache is
                # unwritable. Either way never return None - the caller puts
                # this path straight on a classpath, so falling back to the
                # freshly compiled (and already verified) directory is correct.
                return final if final.is_dir() else classes_dir
        finally:
            shutil.rmtree(staging, ignore_errors=True)
        for path in [final, *final.rglob("*")]:
            try:
                os.chmod(path, 0o755 if path.is_dir() else 0o644)
            except OSError:
                pass
        self._evict(target.parent)
        return final

    def _evict(self, tenant_cache: Path) -> None:
        try:
            entries = [
                p for p in tenant_cache.iterdir()
                if p.is_dir() and not p.name.startswith(".")
            ]
        except OSError:
            return
        limit = self.settings.max_cache_entries_per_tenant
        if len(entries) <= limit:
            return
        entries.sort(key=lambda p: p.stat().st_mtime)
        for stale in entries[: len(entries) - limit]:
            shutil.rmtree(stale, ignore_errors=True)
