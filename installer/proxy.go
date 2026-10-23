// Proxy support for Setup's own downloads (the release, its checksums, the
// GitHub lookups). Setup honours the usual environment variables - HTTPS_PROXY
// (https), HTTP_PROXY (http), ALL_PROXY (either, as a fallback) and NO_PROXY -
// and NOT the Windows or macOS system proxy settings, which Go's standard
// library does not read. A double-clicked app inherits none of them, so a
// network failure says exactly how to set them (withProxyHint).
package main

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
)

// newTransport is the default transport with the proxy rules above.
func newTransport() *http.Transport {
	t := http.DefaultTransport.(*http.Transport).Clone()
	t.Proxy = proxyForRequest
	return t
}

func envFirst(keys ...string) string {
	for _, k := range keys {
		if v := strings.TrimSpace(os.Getenv(k)); v != "" {
			return v
		}
	}
	return ""
}

// proxyForRequest picks the proxy for one request, or nil for a direct
// connection. Anything on this computer (the console, Ollama) is never proxied.
func proxyForRequest(req *http.Request) (*url.URL, error) {
	host, port := req.URL.Hostname(), req.URL.Port()
	if isLoopbackHost(host) || noProxyMatch(envFirst("NO_PROXY", "no_proxy"), host, port) {
		return nil, nil
	}
	raw := ""
	if req.URL.Scheme == "https" {
		raw = envFirst("HTTPS_PROXY", "https_proxy")
	} else {
		raw = envFirst("HTTP_PROXY", "http_proxy")
	}
	if raw == "" {
		raw = envFirst("ALL_PROXY", "all_proxy")
	}
	if raw == "" {
		return nil, nil
	}
	if !strings.Contains(raw, "://") {
		raw = "http://" + raw
	}
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return nil, errors.New("the proxy setting is not a valid URL (expected http://host:port)")
	}
	return u, nil
}

func isLoopbackHost(h string) bool {
	if strings.EqualFold(h, "localhost") {
		return true
	}
	ip := net.ParseIP(h)
	return ip != nil && ip.IsLoopback()
}

// noProxyMatch implements NO_PROXY: comma separated host names (a bare name
// also covers its subdomains, a leading dot only the subdomains), IPs, CIDR
// ranges, optional :port, and "*" for everything.
func noProxyMatch(list, host, port string) bool {
	host = strings.ToLower(host)
	for _, e := range strings.Split(list, ",") {
		e = strings.ToLower(strings.TrimSpace(e))
		if e == "" {
			continue
		}
		if e == "*" {
			return true
		}
		if _, cidr, err := net.ParseCIDR(e); err == nil {
			if ip := net.ParseIP(host); ip != nil && cidr.Contains(ip) {
				return true
			}
			continue
		}
		if h, p, err := net.SplitHostPort(e); err == nil {
			if p != port {
				continue
			}
			e = h
		}
		e = strings.TrimPrefix(e, "*")
		if strings.HasPrefix(e, ".") {
			if strings.HasSuffix(host, e) {
				return true
			}
			continue
		}
		if host == e || strings.HasSuffix(host, "."+e) {
			return true
		}
	}
	return false
}

// withProxyHint adds the proxy instructions to a failed connection. Only
// transport failures get it (an HTTP status, a checksum or a cancelled run
// does not mean the network needs a proxy), and the proxy's address - which may
// carry a password - is never repeated.
func withProxyHint(err error) error {
	var ue *url.Error
	if err == nil || !errors.As(err, &ue) || errors.Is(err, context.Canceled) {
		return err
	}
	state := "no proxy variable is set"
	if envFirst("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy") != "" {
		state = "HTTPS_PROXY is set"
		if envFirst("HTTPS_PROXY", "https_proxy") == "" {
			state = "a proxy variable is set"
		}
	}
	return &hintedError{err: err, hint: "If this network needs a proxy, set HTTPS_PROXY (or ALL_PROXY; NO_PROXY lists hosts to reach directly) " +
		"to http://host:port and start Setup again - Setup reads those variables, not the system proxy settings (" + state + ")."}
}

type hintedError struct {
	err  error
	hint string
}

func (h *hintedError) Error() string { return h.err.Error() + ". " + h.hint }
func (h *hintedError) Unwrap() error { return h.err }
