package com.agenticai.core.providers.addons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.addons.AddonApi.ChangeResult;
import com.agenticai.core.providers.addons.AddonApi.ReportItem;
import com.agenticai.core.providers.addons.AddonApi.ReportRequest;
import com.agenticai.core.providers.addons.AddonApi.View;
import com.agenticai.core.providers.localmodels.LocalModelPullService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Enable, disable, dependencies, the host bridge and the model download, over in-memory state. */
class AddonServiceTest {

    private final AddonKit kit = new AddonKit();
    private final AddonService service = kit.service;

    private View view(String id) {
        return service.get(id);
    }

    private static AddonException thrown(Runnable r) {
        return assertThrows(AddonException.class, r::run);
    }

    // ------------------------------------------------------------------ reading

    @Test
    void theOverviewListsTheCatalogInOrderWithStateAndTheApplyPlan() {
        AddonApi.Overview o = service.overview();
        assertEquals(List.of("search", "docs", "browser", "work", "m-small", "m-big", "later"),
                o.addons().stream().map(View::id).toList());
        assertEquals(1, o.catalogSchema());
        assertTrue(o.hostBridge().configured());
        assertTrue(o.ollama().reachable());
        assertEquals(16.0, o.hardware().ramGb());
        // the one add-on that is on by default is already part of the plan
        assertEquals("./scripts/vegaduta personal --profile workspace", o.apply().command());
        assertEquals(List.of("work"), o.apply().pending());
    }

    @Test
    void anUnknownAddonIs404() {
        AddonException e = thrown(() -> service.get("ghost"));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
        assertEquals("unknown_addon", e.code());
    }

    @Test
    void aPlannedAddonIsListedButNeverSwitchable() {
        View v = view("later");
        assertEquals("unavailable", v.status());
        assertFalse(v.canEnable());
        assertEquals("planned", v.blockedReason());
        assertFalse(v.desired());
        assertEquals("not_available", thrown(() -> service.enable("later", "u")).code());
        assertEquals("not_available", thrown(() -> service.disable("later", false, "u")).code());
        assertTrue(kit.rows.isEmpty());
    }

    @Test
    void fitAdviceFollowsTheHostMemory() {
        AddonDefinition docs = kit.catalog.find("docs").orElseThrow();
        AddonDefinition model = kit.catalog.find("m-big").orElseThrow();
        var small = new com.agenticai.core.providers.localmodels.HardwareProfile(4, 4, null, false);
        var big = new com.agenticai.core.providers.localmodels.HardwareProfile(32, 8, null, false);
        var unknown = new com.agenticai.core.providers.localmodels.HardwareProfile(0, 4, null, false);
        assertEquals("low_memory", AddonService.fit(docs, small));
        assertEquals("ok", AddonService.fit(docs, big));
        assertEquals("low_memory", AddonService.fit(model, new com.agenticai.core.providers.localmodels.HardwareProfile(12, 4, null, false)));
        assertEquals("ok", AddonService.fit(model, big));
        assertEquals("unknown", AddonService.fit(docs, unknown));
    }

    // ----------------------------------------------------- compose enable / disable

    @Test
    void enablingRecordsTheWishAndWaitsForTheHost() {
        ChangeResult r = service.enable("search", "owner-1");
        assertEquals(List.of("search"), r.changed());
        assertTrue(r.needsHost());
        assertTrue(r.addon().desired());
        assertEquals("enable_requested", r.addon().status());
        assertEquals("./scripts/vegaduta personal --profile workspace --search", r.apply().command());
        assertEquals(List.of("--search"), r.apply().flags());
        assertEquals(1L, kit.rows.get("search").getRev());
        assertEquals(List.of("addon.enable:search:changed=search"), kit.audited);
    }

    @Test
    void enablingTwiceChangesNothingTheSecondTime() {
        service.enable("search", "u");
        ChangeResult again = service.enable("search", "u");
        assertEquals(List.of(), again.changed());
        assertTrue(again.needsHost(), "still waiting for the host");
        kit.hostReports(kit.report("search", "running"));
        assertFalse(service.enable("search", "u").needsHost(), "applied: nothing left for the host");
        assertEquals(1L, kit.rows.get("search").getRev());
    }

    @Test
    void theHostReportingRunningMakesItOnAndAStaleReportDoesNot() {
        service.enable("search", "u");
        assertEquals(1, kit.hostReports(kit.report("search", "running")).accepted());
        assertEquals("on", view("search").status());

        service.disable("search", false, "u");
        assertEquals("disable_requested", view("search").status(), "running at the old wish does not count");
        service.enable("search", "u");
        assertEquals("enable_requested", view("search").status(), "rev 3 has not been reported yet");
    }

    @Test
    void disablingWaitsForTheHostToStopItThenIsOff() {
        service.enable("search", "u");
        kit.hostReports(kit.report("search", "running"));
        ChangeResult r = service.disable("search", false, "u");
        assertEquals(List.of("search"), r.changed());
        assertEquals("disable_requested", r.addon().status());
        assertFalse(r.addon().desired());
        assertTrue(r.needsHost());
        kit.hostReports(kit.report("search", "stopped"));
        assertEquals("off", view("search").status());
        assertEquals("./scripts/vegaduta personal --profile workspace", r.apply().command());
    }

    @Test
    void failedThenEnableAgainAsksTheHostAgain() {
        service.enable("search", "u");
        kit.hostReports(new ReportItem("search", 1L, "failed", "port 8888 is in use"));
        View failed = view("search");
        assertEquals("failed", failed.status());
        assertEquals("port 8888 is in use", failed.message());
        ChangeResult retry = service.enable("search", "u");
        assertEquals(List.of("search"), retry.changed());
        assertEquals(2L, kit.rows.get("search").getRev());
        assertEquals("enable_requested", retry.addon().status());
    }

    @Test
    void anAddonSeenRunningIsAdoptedAndStaysInThePlan() {
        kit.environment.setProperty("test.search.url", "http://searxng:8080");
        View v = view("search");
        assertTrue(v.desired());
        assertEquals("on", v.status());
        assertTrue(service.overview().apply().flags().contains("--search"));
        // and the person can still switch it off
        service.disable("search", false, "u");
        assertFalse(view("search").desired());
        assertEquals("disable_requested", view("search").status());
    }

    @Test
    void envAddonsReportTheKeysToSetAndToClear() {
        AddonApi.Apply before = service.overview().apply();
        assertEquals(Map.of(), before.envSet());
        assertEquals(List.of("CORE_IMAGE_VARIANT", "CORE_MEM_LIMIT"), before.envUnset());
        AddonApi.Apply after = service.enable("browser", "u").apply();
        assertEquals(Map.of("CORE_IMAGE_VARIANT", "-full", "CORE_MEM_LIMIT", "2560m"), after.envSet());
        assertEquals(List.of(), after.envUnset());
    }

    // ------------------------------------------------------------------ dependencies

    @Test
    void enablingOneTurnsOnWhatItRequiresFirst() {
        ChangeResult r = service.enable("docs", "u");
        assertEquals(List.of("search", "docs"), r.changed());
        assertTrue(view("search").desired());
        assertEquals(List.of("docs"), view("search").requiredBy());
        assertEquals("./scripts/vegaduta personal --profile rag --profile workspace --search", r.apply().command());
    }

    @Test
    void disablingARequirementIsRefusedWhileSomethingNeedsIt() {
        service.enable("docs", "u");
        AddonException e = thrown(() -> service.disable("search", false, "u"));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals("required_by", e.code());
        assertEquals(List.of("docs"), e.details().get("dependents"));
        assertTrue(view("search").desired(), "nothing was changed");
    }

    @Test
    void cascadeDisablesDependentsFirst() {
        service.enable("docs", "u");
        ChangeResult r = service.disable("search", true, "u");
        assertEquals(List.of("docs", "search"), r.changed());
        assertFalse(view("docs").desired());
        assertFalse(view("search").desired());
        assertEquals(List.of(), view("search").requiredBy());
    }

    @Test
    void disablingADependentLeavesItsRequirementOn() {
        service.enable("docs", "u");
        ChangeResult r = service.disable("docs", false, "u");
        assertEquals(List.of("docs"), r.changed());
        assertTrue(view("search").desired());
    }

    @Test
    void disablingSomethingAlreadyOffIsANoOp() {
        ChangeResult r = service.disable("search", false, "u");
        assertEquals(List.of(), r.changed());
        assertTrue(kit.rows.isEmpty());
        assertTrue(kit.audited.isEmpty(), "a request that changes nothing is not an audit event");
    }

    // ----------------------------------------------------------------- model packs

    @Test
    void enablingAModelStartsTheDownloadAndShowsProgress() {
        ChangeResult r = service.enable("m-small", "u");
        assertEquals(List.of("qwen3.5:2b"), kit.startedPulls);
        assertFalse(r.needsHost());
        assertEquals("pulling", r.addon().status());
        assertEquals(5, r.addon().progress());
        kit.jobs.put("job-1", new LocalModelPullService.Status("running", 61, "pulling layer"));
        assertEquals(61, view("m-small").progress());
        // finished: the model is in Ollama now
        kit.jobs.put("job-1", new LocalModelPullService.Status("done", 100, "Installed"));
        kit.installedModels.add("qwen3.5:2b");
        View done = view("m-small");
        assertEquals("on", done.status());
        assertEquals(Boolean.TRUE, done.installed());
    }

    @Test
    void aFailedDownloadShowsWhyAndCanBeRetried() {
        service.enable("m-small", "u");
        kit.jobs.put("job-1", new LocalModelPullService.Status("failed", 20, "Not enough disk space"));
        View v = view("m-small");
        assertEquals("failed", v.status());
        assertEquals("Not enough disk space", v.message());
        service.enable("m-small", "u");
        assertEquals(List.of("qwen3.5:2b", "qwen3.5:2b"), kit.startedPulls, "enabling again downloads again");
    }

    @Test
    void aModelNeedsTheModelServerAndNothingIsRecordedWithoutIt() {
        kit.ollamaUp = false;
        View blocked = view("m-small");
        assertFalse(blocked.canEnable());
        assertEquals("ollama_unreachable", blocked.blockedReason());
        AddonException e = thrown(() -> service.enable("m-small", "u"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.status());
        assertEquals("ollama_unreachable", e.code());
        assertTrue(kit.rows.isEmpty());
        assertTrue(kit.startedPulls.isEmpty());
    }

    @Test
    void aModelThatIsAlreadyInstalledIsAdoptedWithoutDownloading() {
        kit.installedModels.add("qwen3.5:2b");
        View v = view("m-small");
        assertTrue(v.desired());
        assertEquals("on", v.status());
        ChangeResult r = service.enable("m-small", "u");
        assertEquals(List.of(), r.changed());
        assertTrue(kit.startedPulls.isEmpty());
    }

    @Test
    void enablingTheBigModelDownloadsTheSmallOneItNeedsFirst() {
        ChangeResult r = service.enable("m-big", "u");
        assertEquals(List.of("qwen3.5:2b", "qwen3.5:9b"), kit.startedPulls);
        assertEquals(List.of("m-small", "m-big"), r.changed());
    }

    @Test
    void switchingAModelOffKeepsTheFilesAndRefusesWhileADependentIsOn() {
        kit.installedModels.addAll(List.of("qwen3.5:2b", "qwen3.5:9b"));
        service.enable("m-big", "u");
        assertEquals("required_by", thrown(() -> service.disable("m-small", false, "u")).code());
        service.disable("m-big", false, "u");
        service.disable("m-small", false, "u");
        View v = view("m-small");
        assertEquals("off", v.status());
        assertFalse(v.desired());
        assertEquals(Boolean.TRUE, v.installed(), "the weights stay on disk");
    }

    @Test
    void tooManyDownloadsIsReportedAsSuch() {
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "x"))
                .when(kit.localModels).startPull(org.mockito.ArgumentMatchers.anyString());
        AddonException e = thrown(() -> service.enable("m-small", "u"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.status());
        assertEquals("too_many_downloads", e.code());
        assertTrue(kit.rows.isEmpty());
    }

    // ------------------------------------------------------------------ host bridge

    @Test
    void hostWantedListsOnlyHostManagedAddonsWithTheirWishAndRevision() {
        service.enable("docs", "u");
        AddonApi.HostWanted w = service.hostWanted();
        assertEquals(List.of("search", "docs", "browser", "work"), w.addons().stream().map(AddonApi.HostItem::id).toList());
        AddonApi.HostItem docs = w.addons().get(1);
        assertTrue(docs.wanted());
        assertEquals(1L, docs.rev());
        assertEquals(List.of("rag"), docs.compose().profiles());
        assertFalse(w.addons().get(2).wanted());
        assertEquals(0L, w.addons().get(2).rev());
        assertEquals("work", w.addons().get(3).id());
        assertTrue(w.addons().get(3).wanted(), "default-on");
        assertEquals("enable_requested", docs.status());
        assertNotEquals("", w.generatedAt());
        assertEquals(64, w.fingerprint().length());
    }

    @Test
    void theFingerprintChangesWithAnyWishAndOnlyThen() {
        String start = service.hostWanted().fingerprint();
        assertEquals(start, service.hostWanted().fingerprint());
        service.enable("browser", "u");
        String changed = service.hostWanted().fingerprint();
        assertNotEquals(start, changed);
        kit.hostReports(kit.report("browser", "running"));
        assertEquals(changed, service.hostWanted().fingerprint(), "a report is not a new wish");
        service.disable("browser", false, "u");
        assertNotEquals(changed, service.hostWanted().fingerprint());
    }

    @Test
    void aFirstReportForAnUntouchedAddonKeepsItsEffectiveWish() {
        kit.environment.setProperty("test.sidecar", "true");
        kit.environment.setProperty("test.search.url", "http://x");
        // docs is running (seen) and wanted by adoption; the host reports it before anyone clicked
        AddonApi.ReportResult r = kit.hostReports(new ReportItem("docs", 0L, "running", "up 3 days"));
        assertEquals(1, r.accepted());
        assertTrue(kit.rows.get("docs").isDesired(), "the report must not flip an adopted wish to off");
        assertEquals(0L, kit.rows.get("docs").getRev());
        assertEquals("on", view("docs").status());
    }

    @Test
    void reportsAboutUnknownNonHostOrFutureThingsAreNamedNotFatal() {
        service.enable("search", "u");
        AddonApi.ReportResult r = kit.hostReports(
                new ReportItem("ghost", 0L, "running", null),
                new ReportItem("m-small", 0L, "running", null),
                new ReportItem("later", 0L, "running", null),
                new ReportItem("search", 9L, "running", null),
                new ReportItem("browser", 3L, "running", null),
                new ReportItem("search", 1L, "running", null));
        assertEquals(1, r.accepted());
        assertEquals(
                List.of("ghost:unknown_addon", "m-small:not_host_managed", "later:not_host_managed", "search:rev_ahead", "browser:rev_ahead"),
                r.ignored().stream().map(i -> i.id() + ":" + i.reason()).toList());
    }

    @Test
    void aStaleReportNeverOverwritesANewerOne() {
        service.enable("search", "u");
        service.enable("docs", "u");
        service.disable("search", true, "u"); // search rev 2
        service.enable("search", "u"); // search rev 3
        kit.hostReports(new ReportItem("search", 3L, "running", null));
        AddonApi.ReportResult late = kit.hostReports(new ReportItem("search", 2L, "stopped", null));
        assertEquals(0, late.accepted());
        assertEquals("stale", late.ignored().get(0).reason());
        assertEquals("running", kit.rows.get("search").getReportedStatus());
    }

    @Test
    void malformedReportsAre400() {
        for (ReportRequest bad : List.of(
                new ReportRequest(null),
                new ReportRequest(List.of()),
                new ReportRequest(List.of(new ReportItem("search", null, "running", null))),
                new ReportRequest(List.of(new ReportItem("search", -1L, "running", null))),
                new ReportRequest(List.of(new ReportItem("search", 0L, "exploded", null))),
                new ReportRequest(List.of(new ReportItem(" ", 0L, "running", null))))) {
            AddonException e = thrown(() -> service.hostReport(bad));
            assertEquals(HttpStatus.BAD_REQUEST, e.status());
            assertEquals("invalid_request", e.code());
        }
        assertEquals(400, thrown(() -> service.hostReport(null)).status().value());
        List<ReportItem> tooMany = java.util.stream.IntStream.range(0, 51).mapToObj(i -> new ReportItem("search", 0L, "running", null)).toList();
        assertEquals("invalid_request", thrown(() -> service.hostReport(new ReportRequest(tooMany))).code());
    }

    @Test
    void aHostMessageIsStoredAsOneBoundedLineWithNoControlCharacters() {
        service.enable("search", "u");
        String nasty = "line one\nline two\u0000\u001b[31m" + "x".repeat(900);
        kit.hostReports(new ReportItem("search", 1L, "failed", nasty));
        String stored = kit.rows.get("search").getReportedMessage();
        assertEquals(500, stored.length());
        assertFalse(stored.contains("\n") || stored.contains("\u0000") || stored.contains("\u001b"));
        assertNull(AddonStateStore.clean("   "));
        assertNull(AddonStateStore.clean(null));
    }

    // ------------------------------------------------------------------------ audit

    @Test
    void everyChangeIsAudited() {
        service.enable("docs", "u");
        service.disable("search", true, "u");
        assertEquals(
                List.of("addon.enable:docs:changed=search,docs", "addon.disable:search:changed=docs,search; cascade=true"),
                kit.audited);
    }

    @Test
    void aBrokenAuditLogDoesNotUndoTheChange() {
        org.mockito.Mockito.doThrow(new IllegalStateException("audit table gone"))
                .when(kit.auditLog).record(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(com.agenticai.core.audit.AuditOutcome.class),
                        org.mockito.ArgumentMatchers.any());
        ChangeResult r = service.enable("search", "u");
        assertEquals(List.of("search"), r.changed());
        assertTrue(view("search").desired());
    }

    // --------------------------------------------------------------- apply, pure

    @Test
    void computeApplyBuildsOneCommandFromTheWantedSet() {
        List<AddonDefinition> compose = kit.catalog.all().stream().filter(d -> d.compose() != null).toList();
        AddonApi.Apply none = AddonService.computeApply(compose, d -> new AddonStateRules.Result(false, AddonStatus.OFF, null, null));
        assertEquals("./scripts/vegaduta personal", none.command());
        assertEquals(List.of(), none.profiles());
        AddonApi.Apply all = AddonService.computeApply(compose, d -> new AddonStateRules.Result(true, AddonStatus.ENABLE_REQUESTED, null, null));
        assertEquals("./scripts/vegaduta personal --profile rag --profile workspace --search", all.command());
        assertEquals(List.of("search", "docs", "browser", "work"), all.pending());
    }
}
