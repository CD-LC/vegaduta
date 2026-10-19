package com.agenticai.core.providers.addons;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.providers.addons.AddonApi.Apply;
import com.agenticai.core.providers.addons.AddonApi.ChangeResult;
import com.agenticai.core.providers.addons.AddonApi.DefinitionCompose;
import com.agenticai.core.providers.addons.AddonApi.HostBridge;
import com.agenticai.core.providers.addons.AddonApi.HostItem;
import com.agenticai.core.providers.addons.AddonApi.HostWanted;
import com.agenticai.core.providers.addons.AddonApi.IgnoredReport;
import com.agenticai.core.providers.addons.AddonApi.OllamaInfo;
import com.agenticai.core.providers.addons.AddonApi.Overview;
import com.agenticai.core.providers.addons.AddonApi.ReportItem;
import com.agenticai.core.providers.addons.AddonApi.ReportRequest;
import com.agenticai.core.providers.addons.AddonApi.ReportResult;
import com.agenticai.core.providers.addons.AddonApi.View;
import com.agenticai.core.providers.addons.AddonDefinition.Kind;
import com.agenticai.core.providers.addons.AddonDefinition.Observe;
import com.agenticai.core.providers.addons.AddonStateRules.Facts;
import com.agenticai.core.providers.addons.AddonStateRules.Pull;
import com.agenticai.core.providers.addons.AddonStateRules.Result;
import com.agenticai.core.providers.localmodels.HardwareDetector;
import com.agenticai.core.providers.localmodels.HardwareProfile;
import com.agenticai.core.providers.localmodels.LocalModelOllamaGateway;
import com.agenticai.core.providers.localmodels.LocalModelPullService;
import com.agenticai.core.providers.localmodels.LocalModelsService;
import com.agenticai.core.tenancy.PlatformScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The add-on registry: the catalog ({@link AddonCatalog}) joined with what people asked for and what
 * is going on ({@link AddonStateStore}, {@link AddonStateRules}).
 *
 * <p><b>Who does the work.</b> Core runs in a container and cannot start containers, so for a
 * {@link Kind#COMPOSE_PROFILE} add-on core only records the wish and tells the host what to do
 * ({@link Apply}); the installer on the host reads the wish (see {@link #hostWanted}) and reports
 * back ({@link #hostReport}). A {@link Kind#MODEL_PULL} add-on core does itself, through the same
 * download job the local-models page uses, with progress.
 *
 * <p><b>Dependencies.</b> Turning an add-on on turns on what it requires, requirements first; turning
 * one off is refused while something that requires it is on, unless the caller asks to cascade.
 */
@Service
public class AddonService {

    private static final Logger log = LoggerFactory.getLogger(AddonService.class);

    /** Memory the base personal stack is budgeted for (personal.yml): postgres, valkey, core and web limits. */
    static final int BASE_STACK_MB = 2400;

    static final int MAX_REPORTS = 50;

    /** The command a host runs to bring the stack in line with the wanted set. */
    static final String BASE_COMMAND = "./scripts/vegaduta personal";
    /** Same wish applied to the lite tier (`up --lite` accepts the same --profile/--search/--browser flags). */
    static final String BASE_COMMAND_LITE = "./scripts/vegaduta up --lite";
    /** On the full stack the catalog's single-box add-ons do not apply; still emit an honest command shape. */
    static final String BASE_COMMAND_FULL = "./scripts/vegaduta up --full";

    private final AddonCatalog catalog;
    private final AddonStateStore store;
    private final LocalModelsService localModels;
    private final LocalModelPullService pulls;
    private final LocalModelOllamaGateway ollama;
    private final HardwareDetector hardware;
    private final Environment environment;
    private final AddonHostAuth hostAuth;
    private final ObjectProvider<AuditLogService> audit;
    /** add-on id -> the latest download job core started for it. In memory: a restart forgets a download, not an install. */
    private final Map<String, String> pullJobs = new ConcurrentHashMap<>();

    public AddonService(
            AddonCatalog catalog,
            AddonStateStore store,
            LocalModelsService localModels,
            LocalModelPullService pulls,
            LocalModelOllamaGateway ollama,
            HardwareDetector hardware,
            Environment environment,
            AddonHostAuth hostAuth,
            ObjectProvider<AuditLogService> audit) {
        this.catalog = catalog;
        this.store = store;
        this.localModels = localModels;
        this.pulls = pulls;
        this.ollama = ollama;
        this.hardware = hardware;
        this.environment = environment;
        this.hostAuth = hostAuth;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- reading

    public Overview overview(boolean mayChange) {
        Snapshot s = snapshot();
        List<View> views = new ArrayList<>();
        for (AddonDefinition d : catalog.all()) {
            views.add(view(d, s));
        }
        return new Overview(
                AddonCatalog.SCHEMA_VERSION,
                s.hardware,
                new HostBridge(hostAuth.configured()),
                new OllamaInfo(s.tags.reachable()),
                apply(s),
                views,
                mayChange);
    }

    public View get(String id) {
        AddonDefinition d = require(id);
        return view(d, snapshot());
    }

    // --------------------------------------------------------------- changing

    /**
     * Turns {@code id} on, and what it requires. A model starts downloading at once; a compose add-on
     * is recorded as wanted for the host to apply. Asking again for one that failed asks again.
     */
    public ChangeResult enable(String id, String actor) {
        AddonDefinition target = require(id);
        requireSwitchable(target);
        Snapshot before = snapshot();
        List<AddonDefinition> plan = new ArrayList<>(catalog.requirementsOf(id));
        plan.add(target);
        requireOnTier(target, plan);

        boolean needsServer = plan.stream().anyMatch(d -> d.kind() == Kind.MODEL_PULL && !before.installed(d));
        if (needsServer && !before.tags.reachable()) {
            throw new AddonException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ollama_unreachable",
                    "The local model server is not reachable, so the model cannot be downloaded.");
        }
        for (AddonDefinition d : plan) {
            if (d.kind() == Kind.MODEL_PULL && !before.installed(d)) {
                startPull(d);
            }
        }
        List<AddonStateStore.Step> steps = new ArrayList<>();
        for (AddonDefinition d : plan) {
            boolean wanted = before.result(d).desired();
            boolean retry = wanted && d.kind() == Kind.COMPOSE_PROFILE && before.result(d).status() == AddonStatus.FAILED;
            if (!wanted || retry) {
                steps.add(new AddonStateStore.Step(d.id(), true, wanted, retry));
            }
        }
        List<String> changed = ids(store.setDesired(steps, actor));
        if (!changed.isEmpty()) {
            audit(actor, "addon.enable", id, "changed=" + String.join(",", changed));
        }
        return result(target, changed);
    }

    /**
     * Turns {@code id} off. Refused (409 {@code required_by}) while an add-on that requires it is on,
     * unless {@code cascade}, which turns those off first. A downloaded model stays on disk.
     */
    public ChangeResult disable(String id, boolean cascade, String actor) {
        AddonDefinition target = require(id);
        requireSwitchable(target);
        Snapshot before = snapshot();
        List<AddonDefinition> plan = new ArrayList<>();
        for (AddonDefinition dependent : catalog.dependentsOf(id)) {
            if (before.result(dependent).desired()) {
                plan.add(dependent);
            }
        }
        if (!plan.isEmpty() && !cascade) {
            throw new AddonException(
                    HttpStatus.CONFLICT,
                    "required_by",
                    "Other add-ons need this one: " + String.join(", ", plan.stream().map(AddonDefinition::id).toList()) + ".",
                    Map.of("dependents", plan.stream().map(AddonDefinition::id).toList()));
        }
        plan.add(target);
        List<AddonStateStore.Step> steps = new ArrayList<>();
        for (AddonDefinition d : plan) {
            if (before.result(d).desired()) {
                steps.add(new AddonStateStore.Step(d.id(), false, true, false));
            }
        }
        List<String> changed = ids(store.setDesired(steps, actor));
        if (!changed.isEmpty()) {
            audit(actor, "addon.disable", id, "changed=" + String.join(",", changed) + "; cascade=" + cascade);
        }
        return result(target, changed);
    }

    // ------------------------------------------------------------- host bridge

    /** What the installer should make true. Platform-scoped: the installer has no tenant. */
    public HostWanted hostWanted() {
        return PlatformScope.call(() -> {
            Snapshot s = composeSnapshot();
            List<HostItem> items = new ArrayList<>();
            StringBuilder fingerprint = new StringBuilder("schema=").append(AddonCatalog.SCHEMA_VERSION);
            for (AddonDefinition d : hostManaged()) {
                Result r = s.result(d);
                long rev = s.rows.get(d.id()) == null ? 0 : s.rows.get(d.id()).getRev();
                items.add(new HostItem(
                        d.id(),
                        r.desired(),
                        rev,
                        r.status().wire(),
                        new DefinitionCompose(d.compose().profiles(), d.compose().flags(), d.compose().env())));
                fingerprint.append(';').append(d.id()).append(':').append(r.desired()).append(':').append(rev);
            }
            return new HostWanted(
                    Instant.now().toString(), AddonHostAuth.sha256Hex(fingerprint.toString()), apply(s), items);
        });
    }

    /** Stores what the installer did. Unknown, non-host and out-of-date entries are named, not fatal. */
    public ReportResult hostReport(ReportRequest request) {
        if (request == null || request.reports() == null || request.reports().isEmpty()
                || request.reports().size() > MAX_REPORTS) {
            throw new AddonException(
                    HttpStatus.BAD_REQUEST, "invalid_request", "Send between 1 and " + MAX_REPORTS + " reports.");
        }
        List<AddonStateStore.Report> valid = new ArrayList<>();
        List<IgnoredReport> ignored = new ArrayList<>();
        for (ReportItem item : request.reports()) {
            if (item == null || item.id() == null || item.id().isBlank() || item.rev() == null || item.rev() < 0
                    || item.status() == null || !AddonStateStore.REPORTABLE.contains(item.status())) {
                throw new AddonException(
                        HttpStatus.BAD_REQUEST,
                        "invalid_request",
                        "Each report needs id, rev (0 or more) and status (running, stopped, applying or failed).");
            }
            Optional<AddonDefinition> d = catalog.find(item.id());
            if (d.isEmpty()) {
                ignored.add(new IgnoredReport(item.id(), "unknown_addon"));
            } else if (!isHostManaged(d.get())) {
                ignored.add(new IgnoredReport(item.id(), "not_host_managed"));
            } else {
                valid.add(new AddonStateStore.Report(item.id(), item.rev(), item.status(), item.message()));
            }
        }
        return PlatformScope.call(() -> {
            Snapshot s = composeSnapshot();
            Map<String, Boolean> effective = new HashMap<>();
            for (AddonDefinition d : hostManaged()) {
                effective.put(d.id(), s.result(d).desired());
            }
            AddonStateStore.ReportOutcome outcome = store.record(valid, effective);
            List<IgnoredReport> all = new ArrayList<>(ignored);
            outcome.ignored().forEach(i -> all.add(new IgnoredReport(i.id(), i.reason())));
            log.info("Add-on host report: {} accepted, {} ignored.", outcome.accepted(), all.size());
            return new ReportResult(outcome.accepted(), all);
        });
    }

    // ----------------------------------------------------------------- internals

    private AddonDefinition require(String id) {
        return catalog.find(id)
                .orElseThrow(() -> new AddonException(HttpStatus.NOT_FOUND, "unknown_addon", "There is no add-on '" + id + "'."));
    }

    private static void requireSwitchable(AddonDefinition d) {
        if (!d.isAvailable()) {
            throw new AddonException(
                    HttpStatus.CONFLICT, "not_available", "'" + d.name() + "' is planned and cannot be turned on yet.");
        }
    }

    // On the full stack the launcher honours the `tools` profile and `--search`. The catalog's other
    // single-box selectors (`rag`, `workspace`) are ignored there, so an entry needing either could
    // never land; `--browser` is reachable as the `browser` profile and the command is rewritten
    // instead of the card being refused.
    private static final java.util.Set<String> FULL_TIER_PROFILES = java.util.Set.of("tools");
    private static final java.util.Set<String> FULL_TIER_FLAGS = java.util.Set.of("--search", "--browser");

    private boolean isFullTier() {
        return !"local".equalsIgnoreCase(environment.getProperty("app.auth.mode", "keycloak"))
                && !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("lite"));
    }

    private boolean tierInapplicable(AddonDefinition d) {
        if (!isFullTier() || d.kind() != Kind.COMPOSE_PROFILE || d.compose() == null) {
            return false;
        }
        for (String p : d.compose().profiles()) {
            if (!FULL_TIER_PROFILES.contains(p)) {
                return true;
            }
        }
        for (String f : d.compose().flags()) {
            if (!FULL_TIER_FLAGS.contains(f)) {
                return true;
            }
        }
        return false;
    }

    private void requireOnTier(AddonDefinition target, List<AddonDefinition> plan) {
        if (tierInapplicable(target) || plan.stream().anyMatch(this::tierInapplicable)) {
            throw new AddonException(
                    HttpStatus.CONFLICT,
                    "not_available",
                    "'" + target.name() + "' is not available on this installation tier.");
        }
    }

    private boolean isHostManaged(AddonDefinition d) {
        return d.isAvailable() && d.kind() == Kind.COMPOSE_PROFILE && d.compose() != null;
    }

    private List<AddonDefinition> hostManaged() {
        // Entries the tier cannot run (rag/workspace on the full stack) leave the host surface
        // entirely: the installer never sees them as wanted, and a default-on single-box wish
        // does not leak into the apply command.
        return catalog.all().stream().filter(d -> isHostManaged(d) && !tierInapplicable(d)).toList();
    }

    private void startPull(AddonDefinition d) {
        try {
            pullJobs.put(d.id(), localModels.startPull(d.model()));
        } catch (ResponseStatusException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AddonException(status, "too_many_downloads", "Too many model downloads are running. Wait for one to finish.");
            }
            if (status == HttpStatus.SERVICE_UNAVAILABLE) {
                throw new AddonException(status, "ollama_unreachable", "The local model server is not reachable.");
            }
            throw new AddonException(
                    HttpStatus.CONFLICT, "not_available", "This model cannot be installed from here.");
        }
    }

    /**
     * {@code needsHost} is true while any host-managed add-on this call touched (or asked about) still
     * waits for the host, so asking again for something already requested keeps saying so.
     */
    private ChangeResult result(AddonDefinition target, List<String> changed) {
        Snapshot after = snapshot();
        Set<String> touched = new LinkedHashSet<>(changed);
        touched.add(target.id());
        boolean needsHost = touched.stream()
                .map(catalog::find)
                .flatMap(Optional::stream)
                .filter(this::isHostManaged)
                .map(d -> after.result(d).status())
                .anyMatch(st -> st == AddonStatus.ENABLE_REQUESTED || st == AddonStatus.DISABLE_REQUESTED);
        return new ChangeResult(view(target, after), changed, needsHost, apply(after));
    }

    private static List<String> ids(List<AddonStateStore.Applied> applied) {
        return applied.stream().map(AddonStateStore.Applied::id).toList();
    }

    private void audit(String actor, String action, String id, String detail) {
        AuditLogService service = audit.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            service.record(actor, action, "addon", id, AuditOutcome.SUCCESS, detail);
        } catch (RuntimeException e) {
            // The change is made; a failed audit write must not turn it into an error the person retries.
            log.warn("Could not write the audit entry for {} {}: {}", action, id, e.toString());
        }
    }

    private Snapshot snapshot() {
        return new Snapshot(true);
    }

    /** Just the compose add-ons: the installer polls this, and it has no use for Ollama or the hardware. */
    private Snapshot composeSnapshot() {
        return new Snapshot(false);
    }

    /** Everything one request needs, read once. */
    private final class Snapshot {
        final Map<String, AddonState> rows = store.all();
        final LocalModelOllamaGateway.Tags tags;
        final HardwareProfile hardware;
        final Map<String, Result> results = new LinkedHashMap<>();

        Snapshot(boolean full) {
            this.tags = full ? ollama.tags() : new LocalModelOllamaGateway.Tags(false, List.of());
            this.hardware = full ? AddonService.this.hardware.detect() : null;
            for (AddonDefinition d : catalog.all()) {
                if (full || d.kind() != Kind.MODEL_PULL) {
                    results.put(d.id(), AddonStateRules.evaluate(d, facts(d)));
                }
            }
        }

        private Facts facts(AddonDefinition d) {
            Boolean installed = null;
            Pull pull = null;
            if (d.kind() == Kind.MODEL_PULL) {
                installed = tags.reachable() ? tags.has(d.model()) : null;
                pull = pullFacts(d.id());
            }
            return new Facts(rows.get(d.id()), observed(d), installed, pull);
        }

        Result result(AddonDefinition d) {
            return results.get(d.id());
        }

        boolean installed(AddonDefinition d) {
            return tags.reachable() && tags.has(d.model());
        }
    }

    private Pull pullFacts(String addonId) {
        String job = pullJobs.get(addonId);
        if (job == null) {
            return null;
        }
        return pulls.status(job)
                .map(s -> new Pull(s.status(), s.percent(), s.message()))
                .orElse(null);
    }

    private Boolean observed(AddonDefinition d) {
        Observe o = d.observe();
        if (o == null) {
            return null;
        }
        String value = environment.getProperty(o.property());
        if (Observe.NON_BLANK.equals(o.test())) {
            return value != null && !value.isBlank();
        }
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    private View view(AddonDefinition d, Snapshot s) {
        Result r = s.result(d);
        AddonState row = s.rows.get(d.id());
        Boolean installed = d.kind() == Kind.MODEL_PULL && s.tags.reachable() ? s.tags.has(d.model()) : null;
        String blocked = null;
        if (!d.isAvailable()) {
            blocked = "planned";
        } else if (tierInapplicable(d)) {
            blocked = "not_on_tier";
        } else if (d.kind() == Kind.MODEL_PULL && !s.tags.reachable() && !Boolean.TRUE.equals(installed)) {
            blocked = "ollama_unreachable";
        }
        List<String> requiredBy = new ArrayList<>();
        for (AddonDefinition dependent : catalog.dependentsOf(d.id())) {
            if (s.result(dependent).desired()) {
                requiredBy.add(dependent.id());
            }
        }
        return new View(
                d.id(),
                d.name(),
                d.description(),
                wire(d.category()),
                wire(d.kind()),
                wire(d.availability()),
                d.sizeMb(),
                d.minRamMb(),
                d.needsGpu(),
                d.license(),
                d.free(),
                d.requires(),
                d.defaultOn(),
                hostCommand(d),
                d.learnMore(),
                d.unlocks(),
                d.model(),
                r.desired(),
                r.status().wire(),
                r.message(),
                r.progress(),
                installed,
                row == null ? null : row.getDesiredAt().toString(),
                row == null || row.getReportedAt() == null ? null : row.getReportedAt().toString(),
                fit(d, s.hardware),
                blocked == null,
                blocked,
                requiredBy);
    }

    /** ok, low_memory, needs_gpu or unknown: whether this computer suits the add-on. Advice, never a block. */
    static String fit(AddonDefinition d, HardwareProfile hw) {
        if (!d.isAvailable()) {
            return "unknown";
        }
        if (d.needsGpu() && !hw.appleSilicon() && hw.gpu() == null) {
            return "needs_gpu";
        }
        if (hw.ramGb() <= 0) {
            return "unknown";
        }
        double ramMb = hw.ramGb() * 1024;
        double wanted = d.kind() == Kind.MODEL_PULL ? d.minRamMb() : BASE_STACK_MB + d.minRamMb();
        return ramMb >= wanted ? "ok" : "low_memory";
    }

    private static String wire(Enum<?> value) {
        // The JSON names are the kebab-case/lower-case forms the catalog file uses.
        return value.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private Apply apply(Snapshot s) {
        Apply a = computeApply(hostManaged(), s::result, baseCommand());
        if (!isFullTier() || !a.flags().contains("--browser")) {
            return a;
        }
        // `--browser` layers the single-box browser-runner; the full stack reaches the same thing
        // through the `browser` profile (the mcp-stdio-runner-browser swap), so the copy-paste
        // command has to say that.
        List<String> flags = a.flags().stream().filter(f -> !"--browser".equals(f)).toList();
        List<String> profiles = new ArrayList<>(a.profiles());
        profiles.add("browser");
        StringBuilder command = new StringBuilder(baseCommand());
        profiles.forEach(p -> command.append(" --profile ").append(p));
        flags.forEach(f -> command.append(' ').append(f));
        return new Apply(
                command.toString(), profiles, flags, a.envSet(), a.envUnset(), a.pending());
    }

    /**
     * The installer's base command for this install's shape: {@code personal} on local auth,
     * {@code up --lite} on the lite profile, {@code up --full} elsewhere.
     */
    private String baseCommand() {
        if ("local".equalsIgnoreCase(environment.getProperty("app.auth.mode", "keycloak"))) {
            return BASE_COMMAND;
        }
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("lite"))) {
            return BASE_COMMAND_LITE;
        }
        return BASE_COMMAND_FULL;
    }

    /** A catalog {@code hostCommand} re-pointed at this install's shape (the personal prefix is the template). */
    private String hostCommand(AddonDefinition d) {
        String command = d.hostCommand();
        if (command == null || !command.startsWith(BASE_COMMAND)) {
            return command;
        }
        String rewritten = baseCommand() + command.substring(BASE_COMMAND.length());
        if (isFullTier()) {
            rewritten = rewritten.replace(" --browser", " --profile browser");
        }
        return rewritten;
    }

    /** What the host must do so the stack matches the wanted compose add-ons. Pure, so it is unit-tested. */
    static Apply computeApply(List<AddonDefinition> composeAddons, Function<AddonDefinition, Result> state) {
        return computeApply(composeAddons, state, BASE_COMMAND);
    }

    /** Same as {@link #computeApply(List, Function)} with the tier's own base command. */
    static Apply computeApply(List<AddonDefinition> composeAddons, Function<AddonDefinition, Result> state, String baseCommand) {
        Set<String> profiles = new LinkedHashSet<>();
        Set<String> flags = new LinkedHashSet<>();
        Map<String, String> envSet = new LinkedHashMap<>();
        Set<String> envKnown = new LinkedHashSet<>();
        List<String> pending = new ArrayList<>();
        for (AddonDefinition d : composeAddons) {
            Result r = state.apply(d);
            envKnown.addAll(d.compose().env().keySet());
            if (r.desired()) {
                profiles.addAll(d.compose().profiles());
                flags.addAll(d.compose().flags());
                envSet.putAll(d.compose().env());
            }
            if (r.status() == AddonStatus.ENABLE_REQUESTED || r.status() == AddonStatus.DISABLE_REQUESTED) {
                pending.add(d.id());
            }
        }
        List<String> envUnset = envKnown.stream().filter(k -> !envSet.containsKey(k)).toList();
        StringBuilder command = new StringBuilder(baseCommand);
        profiles.forEach(p -> command.append(" --profile ").append(p));
        flags.forEach(f -> command.append(' ').append(f));
        return new Apply(command.toString(), List.copyOf(profiles), List.copyOf(flags),
                Collections.unmodifiableMap(envSet), envUnset, pending);
    }
}
