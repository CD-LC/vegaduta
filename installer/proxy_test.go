package main

import (
	"errors"
	"net/http"
	"net/url"
	"strings"
	"testing"
)

func proxyFor(t *testing.T, target string) string {
	t.Helper()
	req, _ := http.NewRequest("GET", target, nil)
	u, err := proxyForRequest(req)
	if err != nil {
		t.Fatal(err)
	}
	if u == nil {
		return ""
	}
	return u.String()
}

func clearProxyEnv(t *testing.T) {
	for _, k := range []string{"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy"} {
		t.Setenv(k, "")
	}
}

func TestProxyFromEnvironment(t *testing.T) {
	clearProxyEnv(t)
	if got := proxyFor(t, "https://github.com/x"); got != "" {
		t.Errorf("no variables, no proxy: %q", got)
	}
	t.Setenv("ALL_PROXY", "proxy.corp:3128")
	if got := proxyFor(t, "https://github.com/x"); got != "http://proxy.corp:3128" {
		t.Errorf("ALL_PROXY (scheme-less) = %q", got)
	}
	t.Setenv("all_proxy", "")
	t.Setenv("HTTPS_PROXY", "http://secure.corp:8080")
	if got := proxyFor(t, "https://github.com/x"); got != "http://secure.corp:8080" {
		t.Errorf("HTTPS_PROXY beats ALL_PROXY: %q", got)
	}
	if got := proxyFor(t, "http://example.com/x"); got != "http://proxy.corp:3128" {
		t.Errorf("plain http falls back to ALL_PROXY: %q", got)
	}
	t.Setenv("ALL_PROXY", "socks5://127.0.0.1:1080")
	t.Setenv("HTTPS_PROXY", "")
	if got := proxyFor(t, "https://github.com/x"); got != "socks5://127.0.0.1:1080" {
		t.Errorf("socks5 ALL_PROXY = %q", got)
	}
}

func TestProxyNoProxyAndLoopback(t *testing.T) {
	clearProxyEnv(t)
	t.Setenv("ALL_PROXY", "http://proxy.corp:3128")
	for _, loop := range []string{"http://localhost:3000/", "http://127.0.0.1:3000/", "http://[::1]:3000/"} {
		if got := proxyFor(t, loop); got != "" {
			t.Errorf("the console on this computer must never go through a proxy: %s -> %q", loop, got)
		}
	}
	t.Setenv("NO_PROXY", "internal.example.com, .corp.example, 10.0.0.0/8, github.com:8443")
	for target, want := range map[string]string{
		"https://internal.example.com/x":  "",
		"https://a.internal.example.com/": "",
		"https://x.corp.example/":         "",
		"https://10.1.2.3/":               "",
		"https://github.com/x":            "http://proxy.corp:3128",
		"https://github.com:8443/x":       "",
		"https://example.com/":            "http://proxy.corp:3128",
		"https://notinternal.example.com": "http://proxy.corp:3128",
	} {
		if got := proxyFor(t, target); got != want {
			t.Errorf("%s -> %q want %q", target, got, want)
		}
	}
	t.Setenv("NO_PROXY", "*")
	if got := proxyFor(t, "https://github.com/x"); got != "" {
		t.Errorf("NO_PROXY=* -> %q", got)
	}
}

func TestNetworkHintNamesTheProxyVariablesWithoutLeakingTheirValues(t *testing.T) {
	clearProxyEnv(t)
	base := &url.Error{Op: "Get", URL: "https://github.com/x", Err: errors.New("dial tcp: i/o timeout")}
	msg := withProxyHint(base).Error()
	for _, want := range []string{"HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "system proxy", "no proxy variable is set"} {
		if !strings.Contains(msg, want) {
			t.Errorf("hint misses %q: %s", want, msg)
		}
	}
	t.Setenv("HTTPS_PROXY", "http://user:secret@proxy.corp:8080")
	msg = withProxyHint(base).Error()
	if strings.Contains(msg, "secret") || strings.Contains(msg, "proxy.corp") || !strings.Contains(msg, "HTTPS_PROXY is set") {
		t.Errorf("the hint must say a proxy variable is set, not print it: %s", msg)
	}
	if !errors.Is(withProxyHint(base), base) {
		t.Error("the original error must stay wrapped")
	}
	if withProxyHint(nil) != nil {
		t.Error("nil stays nil")
	}
	plain := errors.New("checksum mismatch")
	if withProxyHint(plain) != plain {
		t.Error("only network errors get the hint")
	}
}
