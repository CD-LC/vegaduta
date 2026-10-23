package main

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// fakeGitHub serves the two things Setup asks: the github.com/<repo>/releases/latest
// redirect and the api.github.com release list. It points githubWeb/githubAPI at itself.
type fakeGitHub struct {
	redirectTo string // Location for /releases/latest; "" = 404
	apiStatus  int
	apiBody    string
	apiHits    atomic.Int32
	webHits    atomic.Int32
}

func newFakeGitHub(t *testing.T, g *fakeGitHub) {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/releases/latest") && !strings.HasPrefix(r.URL.Path, "/repos/"):
			g.webHits.Add(1)
			if g.redirectTo == "" {
				http.NotFound(w, r)
				return
			}
			w.Header().Set("Location", g.redirectTo)
			w.WriteHeader(http.StatusFound)
		case strings.HasPrefix(r.URL.Path, "/repos/o/r/releases"):
			g.apiHits.Add(1)
			w.WriteHeader(g.apiStatus)
			fmt.Fprint(w, g.apiBody)
		default:
			http.NotFound(w, r)
		}
	}))
	ow, oa := githubWeb, githubAPI
	githubWeb, githubAPI = srv.URL, srv.URL
	t.Cleanup(func() { githubWeb, githubAPI = ow, oa; srv.Close() })
}

func latest(t *testing.T) (string, error) {
	t.Helper()
	return resolveVersion(context.Background(), config{repo: "o/r"})
}

// "latest" is the github.com redirect (the way install.sh does it): no API quota is spent.
func TestLatestUsesTheRedirectNotTheAPI(t *testing.T) {
	g := &fakeGitHub{redirectTo: "/o/r/releases/tag/v0.1.9", apiStatus: 403}
	newFakeGitHub(t, g)
	v, err := latest(t)
	if err != nil || v != "0.1.9" {
		t.Fatalf("latest = %q, %v", v, err)
	}
	if g.apiHits.Load() != 0 {
		t.Errorf("the API was called %d times; the redirect is enough", g.apiHits.Load())
	}
}

// GitHub once marked the Java SDK release (tag sdk-v0.1.0) as Latest: it is not a VegaDuta release.
func TestLatestIgnoresReleasesThatAreNotVTags(t *testing.T) {
	g := &fakeGitHub{redirectTo: "/o/r/releases/tag/sdk-v0.1.0", apiStatus: 200, apiBody: `[
		{"tag_name":"sdk-v0.1.0","prerelease":false,"draft":false},
		{"tag_name":"v1.0.0-rc.1","prerelease":true,"draft":false},
		{"tag_name":"v0.2.0","prerelease":false,"draft":true},
		{"tag_name":"v0.1.9","prerelease":false,"draft":false},
		{"tag_name":"v0.1.8","prerelease":false,"draft":false}]`}
	newFakeGitHub(t, g)
	v, err := latest(t)
	if err != nil || v != "0.1.9" {
		t.Fatalf("latest = %q, %v; want 0.1.9 (not the SDK, the pre-release or the draft)", v, err)
	}
}

func TestLatestNeverReturnsAPreRelease(t *testing.T) {
	g := &fakeGitHub{redirectTo: "/o/r/releases/tag/v1.0.0-rc.1", apiStatus: 200,
		apiBody: `[{"tag_name":"v1.0.0-rc.1","prerelease":false,"draft":false},{"tag_name":"v0.1.9"}]`}
	newFakeGitHub(t, g)
	if v, err := latest(t); err != nil || v != "0.1.9" {
		t.Fatalf("latest = %q, %v", v, err)
	}
}

func TestLatestErrorsSayWhatReallyHappened(t *testing.T) {
	t.Run("rate limited", func(t *testing.T) {
		newFakeGitHub(t, &fakeGitHub{redirectTo: "/o/r/releases/tag/sdk-v0.1.0", apiStatus: 403})
		_, err := latest(t)
		if err == nil || !strings.Contains(err.Error(), "rate-limit") || !strings.Contains(err.Error(), "--version") ||
			strings.Contains(err.Error(), "no published release") || !strings.Contains(err.Error(), "sdk-v0.1.0") {
			t.Errorf("err = %v", err)
		}
	})
	t.Run("no release at all", func(t *testing.T) {
		newFakeGitHub(t, &fakeGitHub{apiStatus: 200, apiBody: `[]`})
		_, err := latest(t)
		if err == nil || !strings.Contains(err.Error(), "no published release") {
			t.Errorf("err = %v", err)
		}
	})
	t.Run("only other projects releases", func(t *testing.T) {
		newFakeGitHub(t, &fakeGitHub{redirectTo: "/o/r/releases/tag/sdk-v0.1.0", apiStatus: 200, apiBody: `[{"tag_name":"sdk-v0.1.0"},{"tag_name":"python-sdk-v0.1.0"}]`})
		_, err := latest(t)
		if err == nil || !strings.Contains(err.Error(), "vX.Y.Z") || !strings.Contains(err.Error(), "sdk-v0.1.0") {
			t.Errorf("err = %v", err)
		}
	})
	t.Run("github unreachable", func(t *testing.T) {
		ow, oa := githubWeb, githubAPI
		githubWeb, githubAPI = "http://127.0.0.1:1", "http://127.0.0.1:1"
		t.Cleanup(func() { githubWeb, githubAPI = ow, oa })
		t.Setenv("HTTPS_PROXY", "")
		t.Setenv("ALL_PROXY", "")
		_, err := latest(t)
		if err == nil || !strings.Contains(err.Error(), "HTTPS_PROXY") || !strings.Contains(err.Error(), "ALL_PROXY") ||
			!strings.Contains(err.Error(), "NO_PROXY") || !strings.Contains(err.Error(), "--version") {
			t.Errorf("a network failure must say how to set a proxy: %v", err)
		}
	})
}
