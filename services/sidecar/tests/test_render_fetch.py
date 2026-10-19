"""
Tests for app/render_fetch.py - how a render input reaches local disk.

Covers the source validation, the workspace path guard and the SSRF guard on
clip URLs (address allow-list, hand-followed redirects, DNS-rebinding pin).
All mocked - no DNS, no outbound sockets (one loopback server stands in for a
pinned address).
"""

import http.server
import ipaddress
import threading

import pytest
import requests

from app import render_fetch
from app.render_errors import RenderError


@pytest.fixture
def workspace(tmp_path):
    return tmp_path


class TestMaterializeSourceValidation:
    def test_exactly_one_source_is_required(self, tmp_path):
        with pytest.raises(RenderError, match="exactly one of path, url or content_base64"):
            render_fetch.materialize(
                {}, str(tmp_path / "out.mp4"), what="audio", workspace=str(tmp_path)
            )

    def test_two_sources_are_refused(self, tmp_path):
        with pytest.raises(RenderError, match="exactly one of"):
            render_fetch.materialize(
                {"path": "a.mp4", "url": "https://example.com/b.mp4"},
                str(tmp_path / "out.mp4"),
                what="beat b1 clip",
                workspace=str(tmp_path),
            )

    def test_base64_source_is_written_to_disk(self, tmp_path):
        dest = str(tmp_path / "out.bin")
        render_fetch.materialize(
            {"content_base64": "aGVsbG8="}, dest, what="audio", workspace=str(tmp_path)
        )
        assert open(dest, "rb").read() == b"hello"

    def test_invalid_base64_is_a_named_error(self, tmp_path):
        with pytest.raises(RenderError, match="not valid base64"):
            render_fetch.materialize(
                {"content_base64": "!!!not base64!!!"},
                str(tmp_path / "out.bin"),
                what="audio",
                workspace=str(tmp_path),
            )

    def test_an_empty_base64_string_reads_as_no_source_at_all(self, tmp_path):
        # Not a separate "zero bytes" path: `source.get(k)` is a truthiness
        # test, so "" never counts as a provided source and this lands on the
        # exactly-one guard. Asserted so the behaviour is deliberate rather
        # than incidental - both messages are actionable, and this one is
        # more accurate about what the caller actually sent.
        with pytest.raises(RenderError, match="exactly one of"):
            render_fetch.materialize(
                {"content_base64": ""},
                str(tmp_path / "o.bin"),
                what="audio",
                workspace=str(tmp_path),
            )

    def test_a_decoded_empty_payload_is_refused(self, tmp_path):
        # base64 of nothing that is NOT the empty string - "====" style
        # padding-only input decodes to b"" while still being truthy.
        with pytest.raises(RenderError, match="zero bytes|not valid base64"):
            render_fetch.materialize(
                {"content_base64": "===="},
                str(tmp_path / "o.bin"),
                what="audio",
                workspace=str(tmp_path),
            )


class TestWorkspacePathGuard:
    def test_resolves_a_real_file(self, workspace):
        (workspace / "clip.mp4").write_bytes(b"data")
        assert render_fetch.safe_workspace_path(str(workspace), "clip.mp4") == str(
            (workspace / "clip.mp4").resolve()
        )

    def test_refuses_a_traversal_escape(self, workspace):
        # A clip path arrives from a tenant-authored workflow. Without this
        # the path is an arbitrary-file-read primitive into this container.
        with pytest.raises(RenderError, match="escapes the render workspace"):
            render_fetch.safe_workspace_path(str(workspace), "../../etc/passwd")

    def test_refuses_an_absolute_path(self, workspace):
        with pytest.raises(RenderError):
            render_fetch.safe_workspace_path(str(workspace), "/etc/hostname")

    def test_missing_file_says_so_rather_than_failing_later_in_ffmpeg(self, workspace):
        with pytest.raises(RenderError, match="no such file in the render workspace"):
            render_fetch.safe_workspace_path(str(workspace), "never-written.mp4")


class TestFetchGuard:
    def test_non_http_scheme_is_refused(self):
        with pytest.raises(RenderError, match="scheme must be http or https"):
            render_fetch._assert_public_host("file:///etc/passwd")

    def test_loopback_is_refused_by_default(self, monkeypatch):
        # This container sits on agent-io alongside core and code-intel, so an
        # unguarded clip fetch is a server-side request forgery primitive
        # pointed straight at them.
        monkeypatch.setattr(render_fetch, "ALLOW_PRIVATE_FETCH", False)
        with pytest.raises(RenderError, match="non-public address"):
            render_fetch._assert_public_host("http://127.0.0.1:8080/clip.mp4")

    def test_private_range_is_refused_by_default(self, monkeypatch):
        monkeypatch.setattr(render_fetch, "ALLOW_PRIVATE_FETCH", False)
        with pytest.raises(RenderError, match="non-public address"):
            render_fetch._assert_public_host("https://10.0.0.5/clip.mp4")

    def test_the_escape_hatch_allows_it(self, monkeypatch):
        monkeypatch.setattr(render_fetch, "ALLOW_PRIVATE_FETCH", True)
        render_fetch._assert_public_host("http://127.0.0.1:8080/clip.mp4")


# --------------------------------------------------------------------------
# Security audit 2026-09-18: redirect re-validation and the widened address
# guard (callback-header filtering is in test_render_callback.py). All
# mocked - no DNS, no sockets.
# --------------------------------------------------------------------------


def _resolves_to(monkeypatch, table):
    """Make socket.getaddrinfo answer from `table` (host -> [addresses])."""

    def fake_getaddrinfo(host, *_args, **_kwargs):
        try:
            # An IP literal "resolves" to itself, as the real resolver does.
            answers = [str(ipaddress.ip_address(host))]
        except ValueError:
            if host not in table:
                raise OSError(f"unexpected lookup of {host}") from None
            answers = table[host]
        return [
            (10 if ":" in a else 2, 1, 6, "", (a, 0, 0, 0) if ":" in a else (a, 0)) for a in answers
        ]

    monkeypatch.setattr(render_fetch.socket, "getaddrinfo", fake_getaddrinfo)


class _FakeResponse:
    def __init__(self, status_code, *, location=None, body=b""):
        self.status_code = status_code
        self.headers = {"Location": location} if location is not None else {}
        self._body = body
        self.closed = False

    def iter_content(self, chunk_size=1):
        yield self._body

    def close(self):
        self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        self.close()


def _serve(monkeypatch, routes):
    """Replace the per-hop GET with a table of url -> _FakeResponse. Returns
    the list of (url, kwargs) actually requested, in order; kwargs includes
    the `pinned_ip` the hop was pinned to."""
    calls = []

    def fake_get(url, *, pinned_ip, **kwargs):
        calls.append((url, {**kwargs, "pinned_ip": pinned_ip}))
        return routes[url]

    monkeypatch.setattr(render_fetch, "_http_get", fake_get)
    return calls


class TestAddressGuard:
    @pytest.mark.parametrize(
        "address",
        [
            "127.0.0.1",
            "10.1.2.3",
            "172.16.0.9",
            "192.168.1.1",
            "169.254.169.254",  # cloud instance metadata
            "100.64.0.1",  # CGNAT - is_private is False for this one
            "0.0.0.0",
            "0.1.2.3",
            "224.0.0.1",
            "239.255.255.250",
            "168.63.129.16",  # Azure wire server - a GLOBAL-range address
            "::1",
            "fc00::1",  # IPv6 unique-local
            "fd12:3456::1",
            "fe80::1",  # IPv6 link-local
            "ff02::1",  # IPv6 multicast
            "ff0e::1",  # IPv6 GLOBAL-scope multicast
            "::ffff:169.254.169.254",  # IPv4-mapped metadata
            "::ffff:10.0.0.1",
            "::ffff:168.63.129.16",
        ],
    )
    def test_non_public_addresses_are_refused(self, monkeypatch, address):
        monkeypatch.setattr(render_fetch, "ALLOW_PRIVATE_FETCH", False)
        _resolves_to(monkeypatch, {"clips.example.com": [address]})
        with pytest.raises(RenderError, match="non-public address"):
            render_fetch._assert_public_host("https://clips.example.com/a.mp4")

    @pytest.mark.parametrize("address", ["93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946"])
    def test_public_addresses_pass(self, monkeypatch, address):
        monkeypatch.setattr(render_fetch, "ALLOW_PRIVATE_FETCH", False)
        _resolves_to(monkeypatch, {"clips.example.com": [address]})
        render_fetch._assert_public_host("https://clips.example.com/a.mp4")

    def test_every_resolved_address_must_be_public(self, monkeypatch):
        # One public A record in front of one internal one is a rebinding
        # setup; the public record must not vouch for the other.
        monkeypatch.setattr(render_fetch, "ALLOW_PRIVATE_FETCH", False)
        _resolves_to(monkeypatch, {"clips.example.com": ["93.184.216.34", "10.0.0.5"]})
        with pytest.raises(RenderError, match="non-public address 10.0.0.5"):
            render_fetch._assert_public_host("https://clips.example.com/a.mp4")


class TestFetchRedirects:
    @pytest.fixture(autouse=True)
    def guarded(self, monkeypatch):
        monkeypatch.setattr(render_fetch, "ALLOW_PRIVATE_FETCH", False)

    def test_the_client_never_follows_redirects_itself(self, monkeypatch, tmp_path):
        _resolves_to(monkeypatch, {"clips.example.com": ["93.184.216.34"]})
        calls = _serve(
            monkeypatch, {"https://clips.example.com/a.mp4": _FakeResponse(200, body=b"clip")}
        )
        dest = str(tmp_path / "a.mp4")
        render_fetch._fetch("https://clips.example.com/a.mp4", dest)
        assert open(dest, "rb").read() == b"clip"
        assert all(kwargs.get("allow_redirects") is False for _url, kwargs in calls)

    def test_a_redirect_to_the_metadata_endpoint_is_refused(self, monkeypatch, tmp_path):
        _resolves_to(monkeypatch, {"clips.example.com": ["93.184.216.34"]})
        calls = _serve(
            monkeypatch,
            {
                "https://clips.example.com/a.mp4": _FakeResponse(
                    302, location="http://169.254.169.254/metadata/instance"
                )
            },
        )
        with pytest.raises(RenderError, match="non-public address 169.254.169.254"):
            render_fetch._fetch("https://clips.example.com/a.mp4", str(tmp_path / "a.mp4"))
        # The guard fired BEFORE any request to the redirect target.
        assert [url for url, _ in calls] == ["https://clips.example.com/a.mp4"]

    def test_a_redirect_to_an_internal_hostname_is_refused(self, monkeypatch, tmp_path):
        _resolves_to(monkeypatch, {"clips.example.com": ["93.184.216.34"], "core": ["172.18.0.4"]})
        calls = _serve(
            monkeypatch,
            {"https://clips.example.com/a.mp4": _FakeResponse(307, location="http://core:8080/x")},
        )
        with pytest.raises(RenderError, match="non-public address"):
            render_fetch._fetch("https://clips.example.com/a.mp4", str(tmp_path / "a.mp4"))
        assert len(calls) == 1

    def test_a_redirect_to_a_non_http_scheme_is_refused(self, monkeypatch, tmp_path):
        _resolves_to(monkeypatch, {"clips.example.com": ["93.184.216.34"]})
        _serve(
            monkeypatch,
            {"https://clips.example.com/a.mp4": _FakeResponse(301, location="file:///etc/passwd")},
        )
        with pytest.raises(RenderError, match="scheme must be http or https"):
            render_fetch._fetch("https://clips.example.com/a.mp4", str(tmp_path / "a.mp4"))

    def test_a_public_redirect_is_followed_and_revalidated(self, monkeypatch, tmp_path):
        _resolves_to(
            monkeypatch,
            {"clips.example.com": ["93.184.216.34"], "cdn.example.net": ["151.101.1.69"]},
        )
        calls = _serve(
            monkeypatch,
            {
                # Relative Location: resolved against the hop that sent it.
                "https://clips.example.com/a.mp4": _FakeResponse(302, location="/v2/a.mp4"),
                "https://clips.example.com/v2/a.mp4": _FakeResponse(
                    302, location="https://cdn.example.net/a.mp4"
                ),
                "https://cdn.example.net/a.mp4": _FakeResponse(200, body=b"clip"),
            },
        )
        dest = str(tmp_path / "a.mp4")
        render_fetch._fetch("https://clips.example.com/a.mp4", dest)
        assert open(dest, "rb").read() == b"clip"
        assert [url for url, _ in calls] == [
            "https://clips.example.com/a.mp4",
            "https://clips.example.com/v2/a.mp4",
            "https://cdn.example.net/a.mp4",
        ]

    def test_a_redirect_loop_stops_at_five_hops(self, monkeypatch, tmp_path):
        _resolves_to(monkeypatch, {"clips.example.com": ["93.184.216.34"]})
        calls = _serve(
            monkeypatch,
            {
                "https://clips.example.com/a.mp4": _FakeResponse(
                    302, location="https://clips.example.com/a.mp4"
                )
            },
        )
        with pytest.raises(RenderError, match="redirected more than 5 times"):
            render_fetch._fetch("https://clips.example.com/a.mp4", str(tmp_path / "a.mp4"))
        assert len(calls) == 6  # the original request plus five followed hops

    def test_a_redirect_without_a_location_is_a_named_error(self, monkeypatch, tmp_path):
        _resolves_to(monkeypatch, {"clips.example.com": ["93.184.216.34"]})
        _serve(monkeypatch, {"https://clips.example.com/a.mp4": _FakeResponse(302)})
        with pytest.raises(RenderError, match="no Location header"):
            render_fetch._fetch("https://clips.example.com/a.mp4", str(tmp_path / "a.mp4"))


class TestDnsRebindPinning:
    """2026-09-30: the address the guard validated is the address the fetch
    connects to - no second, unchecked DNS lookup in between."""

    @pytest.fixture(autouse=True)
    def guarded(self, monkeypatch):
        monkeypatch.setattr(render_fetch, "ALLOW_PRIVATE_FETCH", False)

    def test_guard_returns_the_validated_address(self, monkeypatch):
        _resolves_to(monkeypatch, {"clips.example.com": ["93.184.216.34"]})
        assert (
            render_fetch._assert_public_host("https://clips.example.com/a.mp4") == "93.184.216.34"
        )

    def test_guard_returns_none_when_disabled(self, monkeypatch):
        monkeypatch.setattr(render_fetch, "ALLOW_PRIVATE_FETCH", True)
        assert render_fetch._assert_public_host("http://127.0.0.1:8080/clip.mp4") is None

    def test_a_rebinding_resolver_cannot_redirect_the_connection(self, monkeypatch, tmp_path):
        # Public on the first lookup, the metadata endpoint on every later one.
        answers = iter([["93.184.216.34"]])
        lookups = []

        def rebinding_getaddrinfo(host, *_args, **_kwargs):
            lookups.append(host)
            batch = next(answers, ["169.254.169.254"])
            return [(2, 1, 6, "", (a, 0)) for a in batch]

        monkeypatch.setattr(render_fetch.socket, "getaddrinfo", rebinding_getaddrinfo)
        calls = _serve(
            monkeypatch, {"https://clips.example.com/a.mp4": _FakeResponse(200, body=b"clip")}
        )
        render_fetch._fetch("https://clips.example.com/a.mp4", str(tmp_path / "a.mp4"))
        # One lookup (the guard's), and the hop is pinned to what it approved.
        assert lookups == ["clips.example.com"]
        assert calls[0][1]["pinned_ip"] == "93.184.216.34"

    def test_every_redirect_hop_is_pinned_to_its_own_validated_address(self, monkeypatch, tmp_path):
        _resolves_to(
            monkeypatch,
            {"clips.example.com": ["93.184.216.34"], "cdn.example.net": ["151.101.1.69"]},
        )
        calls = _serve(
            monkeypatch,
            {
                "https://clips.example.com/a.mp4": _FakeResponse(
                    302, location="https://cdn.example.net/a.mp4"
                ),
                "https://cdn.example.net/a.mp4": _FakeResponse(200, body=b"clip"),
            },
        )
        render_fetch._fetch("https://clips.example.com/a.mp4", str(tmp_path / "a.mp4"))
        assert [kw["pinned_ip"] for _url, kw in calls] == ["93.184.216.34", "151.101.1.69"]

    def test_https_pool_targets_the_ip_but_verifies_the_hostname(self):
        adapter = render_fetch._PinnedIPAdapter("clips.example.com", "93.184.216.34")
        request = requests.Request("GET", "https://clips.example.com:8443/a.mp4").prepare()
        pool = adapter.get_connection_with_tls_context(request, True)
        assert pool.host == "93.184.216.34"
        assert pool.port == 8443
        assert pool.conn_kw["server_hostname"] == "clips.example.com"
        assert pool.assert_hostname == "clips.example.com"

    def test_the_adapter_refuses_a_different_host(self):
        adapter = render_fetch._PinnedIPAdapter("clips.example.com", "93.184.216.34")
        request = requests.Request("GET", "https://evil.example.org/a.mp4").prepare()
        with pytest.raises(RenderError, match="was asked to reach evil.example.org"):
            adapter.get_connection_with_tls_context(request, True)

    def test_a_real_request_connects_to_the_pinned_ip_with_the_original_host_header(self):
        # A loopback server stands in for "the validated address"; the URL's
        # hostname does not resolve anywhere, so the response can only arrive
        # if the connection really went to the pinned IP without a lookup.
        seen = {}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                seen["host"] = self.headers.get("Host")
                body = b"pinned"
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_args):
                pass

        server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        port = server.server_address[1]
        thread = threading.Thread(target=server.handle_request, daemon=True)
        thread.start()
        try:
            response = render_fetch._http_get(
                f"http://clips.rebind.invalid:{port}/a.mp4",
                pinned_ip="127.0.0.1",
                timeout=10,
                allow_redirects=False,
            )
            assert response.status_code == 200
            assert response.content == b"pinned"
            assert seen["host"] == f"clips.rebind.invalid:{port}"
        finally:
            thread.join(timeout=10)
            server.server_close()
