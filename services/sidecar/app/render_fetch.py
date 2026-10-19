"""
Source materialization for the render pipeline (see render.py).

Every render input - narration audio and one clip per beat - arrives as
exactly one of a workspace-relative path, an http(s) URL or inline base64,
and this module is what puts it on local disk. The URL path is the
dangerous one: a clip URL comes from a tenant-authored workflow, so the
fetch below is guarded against server-side request forgery (address
allow-list, hand-followed redirects, DNS-rebinding pin).
"""

from __future__ import annotations

import base64
import binascii
import ipaddress
import os
import shutil
import socket
from collections.abc import Mapping
from typing import Any
from urllib.parse import urljoin, urlparse

import requests
from requests.adapters import HTTPAdapter
from urllib3 import HTTPConnectionPool

from .render_errors import RenderError

MAX_FETCH_BYTES = int(os.environ.get("RENDER_MAX_FETCH_MB", "512")) * 1024 * 1024
# Private-network fetches are refused by default. A clip URL arrives from a
# tenant-authored workflow, so an unguarded fetch here is a server-side
# request forgery primitive pointed at the agent-io network - and this
# container sits on the same network as core and code-intel. The escape
# hatch exists for a local stack where clips are served off the host.
ALLOW_PRIVATE_FETCH = os.environ.get("RENDER_ALLOW_PRIVATE_FETCH", "false").lower() == "true"

_SUPPORTED_CLIP_EXT = (".mp4", ".mov", ".webm", ".mkv", ".m4v", ".avi")

# Security audit 2026-09-18. Addresses that `ipaddress` reports as global but
# that must never be fetched from inside a cloud network. 168.63.129.16 is
# Azure's platform "wire server" (DHCP/DNS/health/instance-metadata helper):
# a genuinely public-range address that is only reachable from INSIDE an Azure
# vnet - and prod runs on Azure Container Apps - so `.is_global` alone lets it
# through. 169.254.169.254 (IMDS) is already non-global (link-local).
_BLOCKED_GLOBAL_ADDRESSES = frozenset({ipaddress.ip_address("168.63.129.16")})

# Redirects are followed by hand so every hop is re-validated (see _fetch).
_MAX_FETCH_REDIRECTS = 5
_REDIRECT_STATUSES = (301, 302, 303, 307, 308)


def _is_public_address(address: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    """True only for an address that is safe to fetch from this container.

    Security audit 2026-09-18: the previous guard was a deny-list of
    is_private/is_loopback/... flags, which (on the Python this image ships)
    misses CGNAT 100.64.0.0/10 - `is_private` is False for it - and never
    looked inside an IPv4-mapped IPv6 address, so `::ffff:169.254.169.254`
    walked straight past it. This is an allow-list instead: `.is_global` is
    False for loopback, RFC1918, link-local (169.254/16, so the metadata
    endpoint), CGNAT, 0.0.0.0/8, reserved ranges and IPv6 ULA/link-local in
    one test. Multicast is checked explicitly because parts of 224.0.0.0/4
    and ff00::/8 ARE global-scope, and they are never a clip host.
    """
    mapped = getattr(address, "ipv4_mapped", None)
    if mapped is not None:
        # Judge ::ffff:a.b.c.d by the IPv4 address the kernel will actually
        # connect to, not by the IPv6 wrapper.
        address = mapped
    if address in _BLOCKED_GLOBAL_ADDRESSES:
        return False
    if address.is_multicast or address.is_unspecified or address.is_loopback:
        return False
    return bool(address.is_global)


def _assert_public_host(url: str) -> str | None:
    """Validate `url`'s scheme and host; return the address to connect to.

    Returns the one resolved address the fetch must then connect to (every
    address the name resolved to has been checked, and the connection is
    pinned to the first of them - see _PinnedIPAdapter), or None when
    RENDER_ALLOW_PRIVATE_FETCH disables the guard and the fetch may resolve
    normally.
    """
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise RenderError(f"clip URL scheme must be http or https, got {parsed.scheme!r}")
    host = parsed.hostname
    if not host:
        raise RenderError(f"clip URL has no host: {url}")
    if ALLOW_PRIVATE_FETCH:
        return None
    try:
        infos = socket.getaddrinfo(host, None)
    except OSError as e:
        raise RenderError(f"could not resolve clip host {host}: {e}") from e
    if not infos:
        raise RenderError(f"could not resolve clip host {host}: no addresses")
    # EVERY resolved address must be public, not just the first: a host that
    # answers with one public and one internal A record is a rebinding setup,
    # and which record the HTTP client then picks is not ours to choose.
    validated: list[str] = []
    for info in infos:
        # Strip an IPv6 zone id ("fe80::1%eth0") - ip_address() rejects it.
        raw = str(info[4][0]).split("%", 1)[0]
        try:
            address = ipaddress.ip_address(raw)
        except ValueError as e:
            raise RenderError(f"clip host {host} resolved to an unparseable address {raw!r}") from e
        if not _is_public_address(address):
            raise RenderError(
                f"refusing to fetch clip from {host} - it resolves to the non-public "
                f"address {address}. Set RENDER_ALLOW_PRIVATE_FETCH=true only on a "
                f"local stack where this is intended."
            )
        validated.append(str(address))
    return validated[0]


class _PinnedIPAdapter(HTTPAdapter):
    """Connect to one pre-validated IP while keeping the URL's hostname.

    DNS-rebinding fix (2026-09-30): the guard above resolves the clip host
    and checks every address, but a plain `requests.get` then resolves the
    name AGAIN to connect - a near-zero-TTL attacker could answer public to
    the first lookup and 169.254.169.254 / core's address to the second.
    This adapter removes the second lookup: the TCP connection goes to the
    exact address the guard approved, while the hostname is still used for
    the Host header, TLS SNI (`server_hostname`) and certificate hostname
    verification (`assert_hostname`), so HTTPS stays fully verified against
    the name the tenant supplied, not against the bare IP.
    """

    def __init__(self, hostname: str, ip: str) -> None:
        self._hostname = hostname
        self._ip = ip
        super().__init__(max_retries=0)

    def get_connection_with_tls_context(
        self,
        request: requests.PreparedRequest,
        verify: bool | str,
        proxies: Mapping[str, str] | None = None,
        cert: tuple[str, str] | str | None = None,
    ) -> HTTPConnectionPool:
        # Proxies are ignored on purpose (the session is built with
        # trust_env=False): through a proxy the PROXY resolves the name, and
        # the pin would be meaningless.
        host = urlparse(request.url or "").hostname or ""
        if host.lower() != self._hostname.lower():
            raise RenderError(f"pinned connection for {self._hostname} was asked to reach {host}")
        host_params, pool_kwargs = self.build_connection_pool_key_attributes(request, verify, cert)
        host_params = {**host_params, "host": self._ip}
        if host_params["scheme"] == "https":
            pool_kwargs = {
                **pool_kwargs,
                "server_hostname": self._hostname,
                "assert_hostname": self._hostname,
            }
        return self.poolmanager.connection_from_host(**host_params, pool_kwargs=pool_kwargs)

    def send(  # type: ignore[override]
        self, request: requests.PreparedRequest, **kwargs: Any
    ) -> requests.Response:
        # urllib3 would otherwise send `Host: <ip>`; the origin server (and
        # any virtual-hosting CDN in front of it) needs the real name.
        netloc = urlparse(request.url or "").netloc.rsplit("@", 1)[-1]
        request.headers["Host"] = netloc
        return super().send(request, **kwargs)


def _http_get(url: str, *, pinned_ip: str | None, **kwargs: Any) -> requests.Response:
    """One GET hop - pinned to `pinned_ip` unless the guard is disabled."""
    if pinned_ip is None:
        return requests.get(url, **kwargs)
    session = requests.Session()
    session.trust_env = False
    adapter = _PinnedIPAdapter(urlparse(url).hostname or "", pinned_ip)
    session.mount("http://", adapter)
    session.mount("https://", adapter)
    return session.get(url, **kwargs)


def _open_validated(url: str) -> requests.Response:
    """GET `url`, following redirects by hand so each hop is re-validated.

    Security audit 2026-09-18: `requests.get` follows redirects itself by
    default, and the guard only ever saw the FIRST url - so a public host
    answering `302 Location: http://169.254.169.254/...` (or http://core:8080/)
    turned the guard into a formality. Redirects are now off at the client and
    each hop's URL goes back through the full _assert_public_host check
    (scheme included, so a redirect to file:// or gopher:// is refused too).

    DNS rebinding (closed 2026-09-30): each hop connects to the address the
    guard validated for it - see _PinnedIPAdapter - so there is no second,
    unchecked resolution between the check and the connect. Network-level
    egress control (no route to the metadata endpoint) remains the
    recommended outer layer.
    """
    current = url
    for _hop in range(_MAX_FETCH_REDIRECTS + 1):
        pinned_ip = _assert_public_host(current)
        response = _http_get(
            current, pinned_ip=pinned_ip, stream=True, timeout=120, allow_redirects=False
        )
        if response.status_code not in _REDIRECT_STATUSES:
            return response
        location = response.headers.get("Location")
        response.close()
        if not location:
            raise RenderError(
                f"fetching {current} returned HTTP {response.status_code} with no Location header"
            )
        # Location may be relative; resolve it against the hop that sent it.
        current = urljoin(current, location)
    raise RenderError(
        f"clip at {url} redirected more than {_MAX_FETCH_REDIRECTS} times - refusing to follow"
    )


def _fetch(url: str, dest: str) -> None:
    try:
        with _open_validated(url) as response:
            if response.status_code != 200:
                raise RenderError(f"fetching {url} returned HTTP {response.status_code}")
            written = 0
            with open(dest, "wb") as handle:
                for block in response.iter_content(chunk_size=1024 * 256):
                    written += len(block)
                    if written > MAX_FETCH_BYTES:
                        raise RenderError(
                            f"clip at {url} exceeds RENDER_MAX_FETCH_MB "
                            f"({MAX_FETCH_BYTES // (1024 * 1024)}MB)"
                        )
                    handle.write(block)
    except requests.RequestException as e:
        raise RenderError(f"could not fetch clip from {url}: {e}") from e


def safe_workspace_path(workspace: str, relative: str) -> str:
    """Resolve a caller-supplied workspace-relative path, refusing escapes."""
    root = os.path.realpath(workspace)
    candidate = os.path.realpath(os.path.join(root, relative))
    if candidate != root and not candidate.startswith(root + os.sep):
        raise RenderError(f"path {relative!r} escapes the render workspace")
    if not os.path.isfile(candidate):
        raise RenderError(f"no such file in the render workspace: {relative}")
    return candidate


def materialize(source: dict, dest: str, *, what: str, workspace: str) -> str:
    """Put one input on local disk. Exactly one of path/url/content_base64.

    `workspace` is the render workspace root a `path` source is confined to.
    """
    provided = [k for k in ("path", "url", "content_base64") if source.get(k)]
    if len(provided) != 1:
        raise RenderError(
            f"{what}: give exactly one of path, url or content_base64 (got {provided or 'none'})"
        )
    kind = provided[0]
    if kind == "path":
        resolved = safe_workspace_path(workspace, source["path"])
        # Copy rather than reference: the job's own directory is what gets
        # cleaned up, and a render must not mutate or hold open a file that
        # another job owns.
        shutil.copyfile(resolved, dest)
    elif kind == "url":
        _fetch(source["url"], dest)
    else:
        try:
            raw = base64.b64decode(source["content_base64"], validate=True)
        except (binascii.Error, ValueError) as e:
            raise RenderError(f"{what}: content_base64 is not valid base64: {e}") from e
        if not raw:
            raise RenderError(f"{what}: content_base64 decoded to zero bytes")
        with open(dest, "wb") as handle:
            handle.write(raw)
    if os.path.getsize(dest) == 0:
        raise RenderError(f"{what}: resolved to an empty file")
    return dest


def clip_extension(source: dict) -> str:
    for key in ("path", "url"):
        value = source.get(key)
        if not value:
            continue
        name = urlparse(value).path if key == "url" else value
        ext = os.path.splitext(name)[1].lower()
        if ext in _SUPPORTED_CLIP_EXT:
            return ext
    # ffmpeg probes by content, not by name, so an unknown extension is not
    # fatal - .mp4 is only a filename here.
    return ".mp4"
