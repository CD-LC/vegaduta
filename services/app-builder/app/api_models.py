"""Request bodies of the app-builder API (see main.py)."""

from __future__ import annotations

from pydantic import BaseModel, Field

from . import projects as _projects


class CreateProjectRequest(BaseModel):
    project_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._\-]{0,127}$")
    template_id: str
    prompt: str | None = None
    # Disposable software: ephemeral=True builds a micro-app meant for ONE
    # task - it self-archives after ttl_hours (default 72, max 720) unless a
    # human extends it or POST /keep makes it permanent.
    ephemeral: bool = False
    ttl_hours: int | None = Field(
        default=None, ge=_projects.MIN_EPHEMERAL_TTL_HOURS, le=_projects.MAX_EPHEMERAL_TTL_HOURS
    )


class ImportProjectRequest(BaseModel):
    """Register an EXISTING repo as a project (no scaffolding, nothing is
    committed into it)."""

    repo_full_name: str = Field(
        min_length=3,
        max_length=200,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._\-]{0,99}/[A-Za-z0-9][A-Za-z0-9._\-]{0,99}$",
    )
    base_branch: str | None = Field(default=None, max_length=200)
    subdir: str | None = Field(default=None, max_length=300)
    project_id: str | None = Field(default=None, pattern=r"^[A-Za-z0-9][A-Za-z0-9._\-]{0,127}$")


class ImageAttachment(BaseModel):
    """A design input image (screenshot, sketch photo, Figma PNG export)
    forwarded to a vision-capable model. base64, capped at the request
    level — clients downscale before attaching."""

    name: str = Field(min_length=1, max_length=200)
    media_type: str = Field(min_length=3, max_length=100)
    data_base64: str = Field(min_length=1, max_length=6 * 1024 * 1024)


class GenerateRequest(BaseModel):
    instruction: str = Field(min_length=1)
    # Structured design context composed client-side (wireframe spec from
    # the sketch pad, extracted palette hexes, SVG markup, asset names) —
    # free for everyone because it is TEXT, not a vision call.
    context: str | None = Field(default=None, max_length=16000)
    # Raster design inputs for vision-capable providers (sketch photos,
    # Figma PNGs). Omitted on the on-device path — WebLLM's text models
    # get the extracted palette + caption instead.
    images: list[ImageAttachment] | None = Field(default=None, max_length=4)


class FileWrite(BaseModel):
    path: str = Field(min_length=1, max_length=512)
    content: str = Field(max_length=1024 * 1024)


class ApplyFilesRequest(BaseModel):
    """A file set generated client-side (on-device WebLLM/Chrome built-in —
    the "edge" leg; see generate.py's apply_files_to_project docstring).
    The server never calls a model for this route: it applies the files in
    a code-intel workspace, runs the project's check command, and commits
    to the tenant's branch on success — the client owns the retry loop via
    the returned check_output."""

    instruction: str = Field(default="", max_length=4000)
    files: list[FileWrite] = Field(min_length=1, max_length=100)


class ConnectGitHubRequest(BaseModel):
    """A tenant's fine-grained GitHub PAT plus the repo it is scoped to.

    `token` is WRITE-ONLY: it is validated against GitHub, encrypted, stored,
    and never returned by any route again — not by /status, not by
    /projects, not in an error message.
    """

    token: str = Field(min_length=1, max_length=512)
    repo_full_name: str = Field(
        min_length=3,
        max_length=200,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._\-]{0,99}/[A-Za-z0-9][A-Za-z0-9._\-]{0,99}$",
    )


class ExtendProjectRequest(BaseModel):
    """Hours to add to an ephemeral project's remaining lifetime (counted
    from now if it already expired but is not yet archived)."""

    ttl_hours: float = Field(gt=0, le=_projects.MAX_EPHEMERAL_TTL_HOURS)


class EditContextRequest(BaseModel):
    instruction: str = Field(min_length=1, max_length=16000)


class FigmaImportRequest(BaseModel):
    """The token is per-request and never stored, logged or returned."""

    # No pydantic length constraints: a violation makes FastAPI's 422 body
    # echo the offending input, which would return the token. Bounds are
    # enforced in figma.import_frame with fixed-string errors instead.
    url: str
    token: str


class ApplySchemaRequest(BaseModel):
    sql: str = Field(min_length=1, max_length=200 * 1024)


class UploadAssetRequest(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    content_base64: str = Field(min_length=1, max_length=12 * 1024 * 1024)


class DeleteAssetRequest(BaseModel):
    path: str = Field(min_length=1, max_length=512)


class GenerateImageRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=1000)
    kind: str = Field(default="illustration", max_length=20)
    size: str | None = Field(default=None, max_length=20)


class StoreConfigRequest(BaseModel):
    app_name: str = Field(min_length=1, max_length=200)
    package_id: str = Field(min_length=1, max_length=200)
    version_name: str = Field(min_length=1, max_length=64)
    short_description: str = Field(min_length=1, max_length=2000)
    full_description: str = Field(min_length=1, max_length=20000)
