/* VegaDūta Platform Documentation — shared shell (topbar, sidebar, nav filter).
   Every page sets <body data-section="NN-slug" data-page="file-stem"> and
   includes this file. Paths are resolved against <body data-base="../"> —
   pages at the site root omit it (or use ""), subfolder pages use "../". */

(function () {
  var body = document.body;
  var BASE = body.getAttribute("data-base") || "";

  var NAV = [
    { num: "00", slug: "overview", title: "Overview", items: [
      ["index.html", "Documentation Home", ""],
      ["platform-at-a-glance.html", "Platform at a Glance"],
      ["glossary.html", "Glossary & Terminology"]
    ]},
    { num: "01", slug: "architecture", title: "Architecture", items: [
      ["system-architecture.html", "System Architecture"],
      ["backend-core-modules.html", "Core Module Catalogue"],
      ["ai-llm-layer.html", "AI / LLM Layer (Spring AI)"],
      ["data-architecture.html", "Data Architecture & Tenancy"],
      ["identity-access.html", "Identity & Access (Keycloak)"],
      ["channels-integrations.html", "Channels & Integrations"],
      ["service-topology.html", "Service Topology & Runtime Map"]
    ]},
    { num: "02", slug: "decisions", title: "Architecture Decisions (ADR)", items: [
      ["index.html", "ADR Index & Process"],
      ["adr-0001-modular-monolith.html", "ADR-0001 Modular Monolith"],
      ["adr-0002-schema-per-tenant.html", "ADR-0002 Schema-per-Tenant"],
      ["adr-0003-spring-ai-dynamic-clients.html", "ADR-0003 Spring AI Dynamic Clients"],
      ["adr-0004-hosted-container-runtime.html", "ADR-0004 Hosted Container Runtime"],
      ["adr-0005-dual-issuer-keycloak.html", "ADR-0005 Dual-Issuer JWT Validation"],
      ["adr-0006-managed-redis.html", "ADR-0006 Managed Redis (Hosted)"],
      ["adr-0007-build-time-frontend-config.html", "ADR-0007 Frontend Public Config"],
      ["adr-0008-single-revision-deploys.html", "ADR-0008 Verified, Reversible Deploys"],
      ["adr-0009-untrusted-code-sandboxing.html", "ADR-0009 Untrusted-Code Sandboxing"],
      ["adr-0010-secret-injection.html", "ADR-0010 Secret Injection Model"]
    ]},
    { num: "03", slug: "functional", title: "Functional Specification", items: [
      ["index.html", "Functional Overview & Personas"],
      ["agents-runtime.html", "Agents & Agent Runtime"],
      ["workflows.html", "Workflow Engine"],
      ["channels-omnichannel.html", "Channels & Omnichannel"],
      ["knowledge-rag.html", "Knowledge Base & RAG"],
      ["governance-guardrails.html", "Governance, Guardrails & Approvals"]
    ]},
    { num: "04", slug: "api", title: "API Reference", items: [
      ["overview.html", "API Surface Overview"],
      ["authentication.html", "Authentication & Authorization"],
      ["streaming.html", "Streaming: SSE & AG-UI"],
      ["webhooks.html", "Webhooks & Callbacks"],
      ["developer-api.html", "Developer API & MCP Server"]
    ]},
    { num: "05", slug: "deployment", title: "Deployment", items: [
      ["index.html", "Deployment Guide — Start Here"],
      ["ci-cd-pipeline.html", "CI/CD Pipelines"],
      ["compose-stack.html", "Docker Compose Stack"],
      ["local-development.html", "Local Development Setup"],
      ["keycloak-provisioning.html", "Keycloak Provisioning"],
      ["rollback-recovery.html", "Rollback & Recovery"]
    ]},
    { num: "06", slug: "operations", title: "Operations", items: [
      ["observability.html", "Observability: Logs, Metrics, Traces"],
      ["backup-restore.html", "Backup & Restore"]
    ]},
    { num: "07", slug: "security", title: "Security & Compliance", items: [
      ["security-architecture.html", "Security Architecture"],
      ["secrets-management.html", "Secrets & Key Management"],
      ["compliance-posture.html", "Compliance Posture & Controls"],
      ["threat-model.html", "Threat Model & Known Exposures"]
    ]},
    { num: "08", slug: "development", title: "Developer Guide", items: [
      ["getting-started.html", "Getting Started"],
      ["build-and-test.html", "Build, Test & Verify"],
      ["repository-map.html", "Repository Map"],
      ["conventions.html", "Conventions & Standards"],
      ["git-workflow.html", "Git & Contribution Workflow"]
    ]},
    { num: "09", slug: "rfc", title: "RFC Process", items: [
      ["index.html", "RFC Index & Process"],
      ["rfc-0000-template.html", "RFC-0000 Template"]
    ]},
    { num: "10", slug: "testing", title: "Testing & Quality", items: [
      ["test-strategy.html", "Test Strategy"],
      ["e2e-suites.html", "End-to-End Suites"],
      ["quality-gates.html", "Quality Gates & CI Checks"]
    ]},
    { num: "11", slug: "appendix", title: "Appendix", items: [
      ["command-reference.html", "Command Reference (A–Z)"],
      ["environment-variables.html", "Environment Variable Reference"],
      ["ports-services.html", "Ports & Service Matrix"]
    ]}
  ];

  /* brand favicon on every page (no per-page <head> edits) */
  if (!document.querySelector('link[rel~="icon"]')) {
    var ic = document.createElement("link");
    ic.rel = "icon"; ic.type = "image/png"; ic.href = BASE + "assets/img/favicon-32.png";
    document.head.appendChild(ic);
    var ap = document.createElement("link");
    ap.rel = "apple-touch-icon"; ap.href = BASE + "assets/img/apple-touch-icon.png";
    document.head.appendChild(ap);
  }

  var section = body.getAttribute("data-section") || "";
  var page = body.getAttribute("data-page") || "";

  /* ---------- top bar ---------- */
  var header = document.getElementById("site-header");
  if (header) {
    header.innerHTML =
      '<button id="nav-toggle" aria-label="Toggle navigation">&#9776;</button>' +
      '<a class="brand" href="' + BASE + 'index.html">' +
      '<span class="mark"><img src="' + BASE + 'assets/img/logo-mark.png" alt="VegaDūta" width="34" height="25"></span>' +
      '<span>VegaDūta <small>Platform Documentation</small></span></a>' +
      '<span class="spacer"></span>' +
      '<input id="nav-filter" type="search" placeholder="Filter pages…" aria-label="Filter pages">';
  }

  /* ---------- sidebar ---------- */
  var nav = document.getElementById("site-nav");
  if (nav) {
    var html = "";
    NAV.forEach(function (sec) {
      var inSection = (sec.slug === section);
      html += '<div class="nav-section"><div class="nav-section-title"><span class="nav-num">' +
        sec.num + '</span>' + sec.title + '</div>';
      sec.items.forEach(function (it) {
        var href = (it[0] === "index.html" && sec.slug === "overview") ? BASE + it[0] : BASE + sec.slug + "/" + it[0];
        var active = inSection && it[0] === page ? " active" : "";
        html += '<a class="nav-link' + active + '" href="' + href + '" data-title="' +
          (it[1] + " " + sec.title).toLowerCase() + '">' + it[1] + '</a>';
      });
      html += "</div>";
    });
    nav.innerHTML = html;
  }

  /* ---------- nav filter ---------- */
  var filter = document.getElementById("nav-filter");
  if (filter && nav) {
    filter.addEventListener("input", function () {
      var q = filter.value.trim().toLowerCase();
      nav.querySelectorAll("a.nav-link").forEach(function (a) {
        a.classList.toggle("nav-hidden", q && a.getAttribute("data-title").indexOf(q) < 0);
      });
      nav.querySelectorAll(".nav-section").forEach(function (s) {
        var any = s.querySelector("a.nav-link:not(.nav-hidden)");
        s.style.display = any ? "" : "none";
      });
    });
  }

  /* ---------- mobile toggle ---------- */
  var toggle = document.getElementById("nav-toggle");
  if (toggle && nav) {
    toggle.addEventListener("click", function () { nav.classList.toggle("open"); });
  }

  /* ---------- auto doc-meta from data attributes ---------- */
  var meta = document.querySelector(".doc-meta[data-auto]");
  if (meta) {
    var parts = [];
    if (body.dataset.doc) parts.push('<span class="doc-id">' + body.dataset.doc + "</span>");
    if (body.dataset.status) parts.push('<span class="badge badge-' + body.dataset.status + '">' + body.dataset.status + "</span>");
    if (body.dataset.updated) parts.push("<span>Last verified: " + body.dataset.updated + "</span>");
    if (body.dataset.owner) parts.push("<span>Owner: " + body.dataset.owner + "</span>");
    meta.innerHTML = parts.join("");
  }

  /* ---------- footer ---------- */
  var content = document.getElementById("content");
  if (content && !content.querySelector("footer.doc-footer")) {
    var f = document.createElement("footer");
    f.className = "doc-footer";
    f.innerHTML = "VegaDūta Platform Documentation · " +
      "Sources: repository code, Compose files and Helm charts. " +
      "Verification markers: <span class=\"badge badge-verified\">verified</span> " +
      "<span class=\"badge badge-unverified\">unverified</span>";
    content.appendChild(f);
  }
})();
