"""
Tests for app/render_callback.py - the completion push a finished render
sends: the host allowlist and the caller-supplied header filter.
"""

import pytest

from app import render_callback
from app.render import RenderJob
from app.render_errors import RenderError


class _PostResponse:
    status_code = 200


class TestCallbackAllowlist:
    def test_core_is_allowed(self, monkeypatch):
        monkeypatch.setattr(render_callback, "CALLBACK_ALLOWED_HOSTS", {"core"})
        render_callback.validate_callback("http://core:8080/api/workflows/x/nodes/y/webhook")

    def test_anything_else_is_refused_at_submit_time(self, monkeypatch):
        # Validated on the request that SETS it, not at completion, so a bad
        # URL is a 400 now rather than a silent failure forty minutes later.
        monkeypatch.setattr(render_callback, "CALLBACK_ALLOWED_HOSTS", {"core"})
        with pytest.raises(RenderError, match="is not allowed"):
            render_callback.validate_callback("https://attacker.example.com/collect")

    def test_the_message_lists_what_is_allowed(self, monkeypatch):
        monkeypatch.setattr(render_callback, "CALLBACK_ALLOWED_HOSTS", {"core"})
        with pytest.raises(RenderError, match="RENDER_CALLBACK_ALLOWED_HOSTS"):
            render_callback.validate_callback("https://elsewhere.invalid/hook")


class TestCallbackHeaderFilter:
    def test_ordinary_headers_survive(self):
        supplied = {"X-Webhook-Token": "abc", "X-Request-Id": "r-1"}
        assert render_callback._filter_callback_headers(supplied) == supplied

    def test_authorization_is_kept_on_purpose(self):
        # core's workflow webhook has an OAUTH2 auth mode that REQUIRES an
        # Authorization: Bearer header - authenticating the callback is what
        # this field is for. The host allowlist bounds where it can be sent.
        kept = render_callback._filter_callback_headers({"Authorization": "Bearer t"})
        assert kept == {"Authorization": "Bearer t"}

    @pytest.mark.parametrize(
        "name",
        [
            "Host",
            "host",
            "Content-Length",
            "CONTENT-TYPE",
            "Transfer-Encoding",
            "Connection",
            "Cookie",
            "Proxy-Authorization",
            "proxy-connection",
            "X-Forwarded-For",
            "x-forwarded-host",
            "X-Forwarded-Proto",
        ],
    )
    def test_routing_framing_and_trust_headers_are_dropped(self, name):
        assert render_callback._filter_callback_headers({name: "x", "X-Ok": "1"}) == {"X-Ok": "1"}

    @pytest.mark.parametrize(
        "name,value",
        [
            ("X-Evil\r\nHost: internal", "x"),
            ("X-Evil\nX-Other", "x"),
            ("X-Evil", "a\r\nX-Forwarded-For: 127.0.0.1"),
            ("X-Evil", "a\nb"),
            ("X-Evil", "a\rb"),
            ("X Evil", "x"),
            ("X-Evil:", "x"),
            ("", "x"),
        ],
    )
    def test_crlf_and_malformed_names_are_dropped(self, name, value):
        assert render_callback._filter_callback_headers({name: value}) == {}

    def test_an_oversized_value_is_dropped_and_the_limit_is_inclusive(self):
        kept = render_callback._filter_callback_headers({"X-Big": "a" * 2049, "X-Fits": "a" * 2048})
        assert list(kept) == ["X-Fits"]

    def test_at_most_twenty_headers_are_forwarded(self):
        supplied = {f"X-H{i}": str(i) for i in range(30)}
        kept = render_callback._filter_callback_headers(supplied)
        assert len(kept) == 20
        assert list(kept) == [f"X-H{i}" for i in range(20)]

    def test_denied_headers_do_not_consume_the_cap(self):
        supplied = {"Host": "evil", **{f"X-H{i}": str(i) for i in range(20)}}
        assert len(render_callback._filter_callback_headers(supplied)) == 20

    @pytest.mark.parametrize("supplied", [None, "Host: x", ["Host"], 7])
    def test_a_non_dict_is_no_headers(self, supplied):
        assert render_callback._filter_callback_headers(supplied) == {}

    def test_notify_sends_only_filtered_headers_and_does_not_follow_redirects(self, monkeypatch):
        sent = {}

        def fake_post(url, **kwargs):
            sent.update(kwargs, url=url)
            return _PostResponse()

        monkeypatch.setattr(render_callback.requests, "post", fake_post)
        job = RenderJob(job_id="cb-1", status="completed")
        render_callback.notify(
            job,
            {
                "callback_url": "http://core:8080/api/webhooks/workflows/tok",
                "callback_headers": {
                    "Host": "keycloak:8080",
                    "X-Forwarded-For": "127.0.0.1",
                    "Content-Type": "text/plain",
                    "Authorization": "Bearer t",
                    "X-Webhook-Token": "abc",
                },
            },
        )
        assert sent["headers"] == {
            "Content-Type": "application/json",
            "Authorization": "Bearer t",
            "X-Webhook-Token": "abc",
        }
        assert sent["allow_redirects"] is False
