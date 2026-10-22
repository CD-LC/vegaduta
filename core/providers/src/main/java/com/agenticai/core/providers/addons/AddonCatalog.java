package com.agenticai.core.providers.addons;

import com.agenticai.core.providers.addons.AddonDefinition.Availability;
import com.agenticai.core.providers.addons.AddonDefinition.Kind;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The add-on catalog: {@code addons/catalog.json} on the classpath, the single source of truth for
 * which optional capabilities exist. VegaDuta Setup (the Go installer) ships a copy that a CI test
 * keeps byte-identical, so the console and the installer can never disagree about what is on offer.
 *
 * <p>Loading is strict and fails fast: unknown JSON properties, duplicate keys, duplicate ids, an
 * unknown {@code requires}, a dependency cycle, a compose entry the launcher cannot honour, or an
 * available entry that depends on a planned one all stop core from starting with every problem
 * listed. The rules are in {@link #validate}; they exist so that nothing the catalog lists as
 * switchable can turn out not to be.
 */
@Component
public class AddonCatalog {

    static final String RESOURCE = "/addons/catalog.json";
    static final int SCHEMA_VERSION = 1;

    /** At most this many entries may be planned: a roadmap hint, not a feature list. */
    static final int MAX_PLANNED = 3;

    /** Reserved: {@code /api/addons/host/...} is the installer's surface. */
    static final Set<String> RESERVED_IDS = Set.of("host");

    /** {@code --profile} names {@code scripts/vegaduta personal} accepts (resolve_profiles). */
    static final Set<String> PERSONAL_PROFILES = Set.of("rag", "workspace", "tools");

    /** Bare flags {@code scripts/vegaduta personal} remembers (parse_tier_flags). */
    static final Set<String> PERSONAL_FLAGS = Set.of("--search", "--browser");

    static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{1,62}$");
    static final Pattern ENV_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /** Values are written into {@code .env} unquoted: no spaces, quotes, dollars or newlines. */
    static final Pattern ENV_VALUE = Pattern.compile("^[A-Za-z0-9._:/+-]{1,128}$");

    static final Pattern LEARN_MORE = Pattern.compile("^docs/[A-Za-z0-9._/-]+(#[A-Za-z0-9._-]+)?$");

    /** Same shape as {@code LocalModelsService.TAG}: an Ollama model name. */
    static final Pattern MODEL_TAG =
            Pattern.compile(
                    "^[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*){0,2}(:[A-Za-z0-9._-]+)?$");

    private static final int MAX_NAME = 80;
    private static final int MAX_DESCRIPTION = 600;

    private final List<AddonDefinition> addons;
    private final Map<String, AddonDefinition> byId;

    /** Ids with every requirement before the add-on that needs it. */
    private final List<String> order;

    @Autowired
    public AddonCatalog() {
        this(read(AddonCatalog.class.getResourceAsStream(RESOURCE), RESOURCE));
    }

    private AddonCatalog(List<AddonDefinition> parsed) {
        List<String> problems = validate(parsed);
        if (!problems.isEmpty()) {
            throw new AddonCatalogException(
                    "addons/catalog.json is invalid:\n  - " + String.join("\n  - ", problems));
        }
        this.addons = List.copyOf(parsed);
        Map<String, AddonDefinition> index = new LinkedHashMap<>();
        for (AddonDefinition d : parsed) {
            index.put(d.id(), d);
        }
        this.byId = Map.copyOf(index);
        this.order = topologicalOrder(parsed);
    }

    /** Parses and validates catalog text; used by tests with small synthetic catalogs. */
    static AddonCatalog parse(String json) {
        return new AddonCatalog(readText(json));
    }

    /** Every entry, in the file's order (which is the order the console shows them in). */
    public List<AddonDefinition> all() {
        return addons;
    }

    public Optional<AddonDefinition> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /**
     * Everything {@code id} needs, directly or not, requirements first; {@code id} itself is not in
     * the list.
     */
    public List<AddonDefinition> requirementsOf(String id) {
        Set<String> needed = new LinkedHashSet<>();
        collectRequirements(id, needed);
        needed.remove(id);
        List<AddonDefinition> out = new ArrayList<>();
        for (String candidate : order) {
            if (needed.contains(candidate)) {
                out.add(byId.get(candidate));
            }
        }
        return out;
    }

    /** Everything that needs {@code id}, directly or not, the add-ons furthest from it first. */
    public List<AddonDefinition> dependentsOf(String id) {
        List<AddonDefinition> out = new ArrayList<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            String candidate = order.get(i);
            if (!candidate.equals(id)
                    && requirementsOf(candidate).stream().anyMatch(d -> d.id().equals(id))) {
                out.add(byId.get(candidate));
            }
        }
        return out;
    }

    private void collectRequirements(String id, Set<String> into) {
        AddonDefinition d = byId.get(id);
        if (d == null || !into.add(id)) {
            return;
        }
        for (String r : d.requires()) {
            collectRequirements(r, into);
        }
    }

    // ------------------------------------------------------------------ reading

    private static List<AddonDefinition> read(InputStream in, String name) {
        if (in == null) {
            throw new AddonCatalogException(name + " is missing from the classpath.");
        }
        try (in) {
            return readText(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AddonCatalogException("Could not read " + name + ".", e);
        }
    }

    private record CatalogFile(Integer schemaVersion, List<AddonDefinition> addons) {}

    private static List<AddonDefinition> readText(String json) {
        JsonMapper mapper =
                JsonMapper.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                        .build();
        CatalogFile file;
        try {
            file = mapper.readValue(json, CatalogFile.class);
        } catch (JacksonException e) {
            throw new AddonCatalogException(
                    "addons/catalog.json cannot be parsed: " + e.getOriginalMessage(), e);
        }
        if (file == null
                || file.schemaVersion() == null
                || file.schemaVersion() != SCHEMA_VERSION) {
            throw new AddonCatalogException(
                    "addons/catalog.json: schemaVersion must be " + SCHEMA_VERSION + ".");
        }
        if (file.addons() == null || file.addons().isEmpty()) {
            throw new AddonCatalogException(
                    "addons/catalog.json: addons must be a non-empty list.");
        }
        return file.addons();
    }

    // --------------------------------------------------------------- validation

    /** Every rule the catalog must satisfy, as human-readable problems; empty when it is sound. */
    static List<String> validate(List<AddonDefinition> list) {
        List<String> problems = new ArrayList<>();
        Map<String, AddonDefinition> ids = new HashMap<>();
        for (AddonDefinition d : list) {
            if (d == null) {
                problems.add("an entry is null");
                continue;
            }
            String where = "'" + d.id() + "'";
            if (d.id() == null || !ID.matcher(d.id()).matches()) {
                problems.add("id " + where + " must match " + ID.pattern());
                continue;
            }
            if (RESERVED_IDS.contains(d.id())) {
                problems.add("id " + where + " is reserved");
            }
            if (ids.put(d.id(), d) != null) {
                problems.add("duplicate id " + where);
            }
        }
        for (AddonDefinition d : list) {
            if (d != null && d.id() != null && ID.matcher(d.id()).matches()) {
                validateEntry(d, ids, problems);
            }
        }
        validateComposeEnvConflicts(list, problems);
        long planned =
                list.stream()
                        .filter(d -> d != null && d.availability() == Availability.PLANNED)
                        .count();
        if (planned > MAX_PLANNED) {
            problems.add("at most " + MAX_PLANNED + " add-ons may be planned, found " + planned);
        }
        if (problems.isEmpty()) {
            problems.addAll(cycles(list));
        }
        return problems;
    }

    private static void validateEntry(
            AddonDefinition d, Map<String, AddonDefinition> ids, List<String> problems) {
        String where = "'" + d.id() + "'";
        boolean planned = d.availability() == Availability.PLANNED;
        text(problems, where, "name", d.name(), MAX_NAME);
        text(problems, where, "description", d.description(), MAX_DESCRIPTION);
        if (d.category() == null) {
            problems.add(where + ": category is missing or unknown");
        }
        if (d.kind() == null) {
            problems.add(where + ": kind is missing or unknown");
        }
        if (d.availability() == null) {
            problems.add(where + ": availability is missing or unknown");
        }
        if (d.sizeMb() < 0 || d.minRamMb() < 0) {
            problems.add(where + ": sizeMb and minRamMb cannot be negative");
        }
        if (d.kind() == null || d.availability() == null) {
            return;
        }
        if ((d.kind() == Kind.PLANNED) != planned) {
            problems.add(where + ": kind 'planned' and availability 'planned' go together");
        }
        if (d.kind() == Kind.MCP_SERVER && !planned) {
            problems.add(
                    where
                            + ": kind 'mcp-server' has no executor yet, so it can only be listed as"
                            + " planned");
        }
        if (!planned && (d.license() == null || d.license().isBlank())) {
            problems.add(where + ": license is required for an available add-on");
        }
        if (d.learnMore() != null
                && !d.learnMore().isEmpty()
                && (d.learnMore().contains("..") || !LEARN_MORE.matcher(d.learnMore()).matches())) {
            problems.add(
                    where + ": learnMore must be a repository docs path like docs/x.md#anchor");
        }
        if (d.learnMore() == null || d.learnMore().isEmpty()) {
            problems.add(where + ": learnMore is required");
        }
        for (String key : d.unlocks()) {
            if (!AddonDefinition.Unlock.known(key)) {
                problems.add(where + ": unknown unlocks key '" + key + "'");
            }
        }
        for (String required : d.requires()) {
            AddonDefinition target = ids.get(required);
            if (target == null) {
                problems.add(where + ": requires unknown add-on '" + required + "'");
            } else if (required.equals(d.id())) {
                problems.add(where + ": requires itself");
            } else if (!planned && target.availability() == Availability.PLANNED) {
                problems.add(
                        where + ": is available but requires planned add-on '" + required + "'");
            }
        }
        if (new HashSet<>(d.requires()).size() != d.requires().size()) {
            problems.add(where + ": requires lists an id twice");
        }
        if (planned) {
            validatePlanned(d, where, problems);
        } else if (d.kind() == Kind.COMPOSE_PROFILE) {
            validateCompose(d, where, problems);
        } else if (d.kind() == Kind.MODEL_PULL) {
            validateModelPull(d, where, problems);
        }
    }

    private static void validatePlanned(AddonDefinition d, String where, List<String> problems) {
        if (d.defaultOn()) {
            problems.add(where + ": a planned add-on cannot be on by default");
        }
        if (d.compose() != null || d.model() != null || d.observe() != null) {
            problems.add(
                    where
                            + ": a planned add-on cannot carry compose, model or observe (nothing"
                            + " can be switched on)");
        }
        if (d.hostCommand() != null && !d.hostCommand().isEmpty()) {
            problems.add(where + ": a planned add-on has no hostCommand");
        }
    }

    private static void validateCompose(AddonDefinition d, String where, List<String> problems) {
        AddonDefinition.Compose c = d.compose();
        if (c == null || c.isEmpty()) {
            problems.add(where + ": a compose-profile add-on needs compose.profiles, flags or env");
            return;
        }
        if (d.model() != null) {
            problems.add(where + ": model belongs to kind 'model-pull'");
        }
        for (String p : c.profiles()) {
            if (!PERSONAL_PROFILES.contains(p)) {
                problems.add(
                        where
                                + ": compose profile '"
                                + p
                                + "' is not one scripts/vegaduta personal accepts "
                                + PERSONAL_PROFILES);
            }
        }
        for (String f : c.flags()) {
            if (!PERSONAL_FLAGS.contains(f)) {
                problems.add(
                        where
                                + ": compose flag '"
                                + f
                                + "' is not one scripts/vegaduta personal remembers "
                                + PERSONAL_FLAGS);
            }
        }
        for (Map.Entry<String, String> e : c.env().entrySet()) {
            if (!ENV_KEY.matcher(e.getKey()).matches()) {
                problems.add(where + ": env key '" + e.getKey() + "' must be UPPER_SNAKE_CASE");
            }
            if (e.getValue() == null || !ENV_VALUE.matcher(e.getValue()).matches()) {
                problems.add(
                        where
                                + ": env value of "
                                + e.getKey()
                                + " must match "
                                + ENV_VALUE.pattern());
            }
        }
        String command = d.hostCommand() == null ? "" : d.hostCommand();
        if (command.isBlank()) {
            problems.add(where + ": hostCommand is required");
        }
        for (String p : c.profiles()) {
            if (!command.contains("--profile " + p)) {
                problems.add(where + ": hostCommand must contain '--profile " + p + "'");
            }
        }
        for (String f : c.flags()) {
            if (!command.contains(f)) {
                problems.add(where + ": hostCommand must contain '" + f + "'");
            }
        }
        for (Map.Entry<String, String> e : c.env().entrySet()) {
            if (!command.contains(e.getKey() + "=" + e.getValue())) {
                problems.add(
                        where + ": hostCommand must mention " + e.getKey() + "=" + e.getValue());
            }
        }
        AddonDefinition.Observe o = d.observe();
        if (o != null) {
            boolean knownTest =
                    AddonDefinition.Observe.NON_BLANK.equals(o.test())
                            || AddonDefinition.Observe.TRUE.equals(o.test());
            if (o.property() == null || o.property().isBlank() || !knownTest) {
                problems.add(where + ": observe needs a property and test 'non-blank' or 'true'");
            }
        }
    }

    private static void validateModelPull(AddonDefinition d, String where, List<String> problems) {
        if (d.model() == null || !MODEL_TAG.matcher(d.model()).matches()) {
            problems.add(where + ": a model-pull add-on needs a valid Ollama tag in model");
        }
        if (d.compose() != null || d.observe() != null) {
            problems.add(where + ": compose and observe belong to kind 'compose-profile'");
        }
        if (d.hostCommand() == null
                || d.hostCommand().isBlank()
                || (d.model() != null && !d.hostCommand().contains(d.model()))) {
            problems.add(where + ": hostCommand must be the manual pull of the model");
        }
    }

    private static void text(
            List<String> problems, String where, String field, String value, int max) {
        if (value == null || value.isBlank()) {
            problems.add(where + ": " + field + " is required");
        } else if (value.length() > max) {
            problems.add(where + ": " + field + " is longer than " + max + " characters");
        }
    }

    /** Two add-ons that set the same {@code .env} key to different values cannot both be on. */
    private static void validateComposeEnvConflicts(
            List<AddonDefinition> list, List<String> problems) {
        Map<String, String> seen = new HashMap<>();
        Map<String, String> owner = new HashMap<>();
        for (AddonDefinition d : list) {
            if (d == null || d.compose() == null) {
                continue;
            }
            for (Map.Entry<String, String> e : d.compose().env().entrySet()) {
                String previous = seen.putIfAbsent(e.getKey(), e.getValue());
                if (previous != null && !previous.equals(e.getValue())) {
                    problems.add(
                            "env key "
                                    + e.getKey()
                                    + " is set to different values by '"
                                    + owner.get(e.getKey())
                                    + "' and '"
                                    + d.id()
                                    + "'");
                }
                owner.putIfAbsent(e.getKey(), d.id());
            }
        }
    }

    private static List<String> cycles(List<AddonDefinition> list) {
        Map<String, AddonDefinition> byId = new HashMap<>();
        list.forEach(d -> byId.put(d.id(), d));
        List<String> problems = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (AddonDefinition d : list) {
            findCycle(d.id(), byId, done, new ArrayList<>(), problems);
        }
        return problems;
    }

    private static void findCycle(
            String id,
            Map<String, AddonDefinition> byId,
            Set<String> done,
            List<String> path,
            List<String> problems) {
        if (done.contains(id)) {
            return;
        }
        int at = path.indexOf(id);
        if (at >= 0) {
            List<String> loop = new ArrayList<>(path.subList(at, path.size()));
            loop.add(id);
            problems.add("dependency cycle: " + String.join(" -> ", loop));
            return;
        }
        path.add(id);
        for (String r : byId.get(id).requires()) {
            if (byId.containsKey(r)) {
                findCycle(r, byId, done, path, problems);
            }
        }
        path.remove(path.size() - 1);
        done.add(id);
    }

    /**
     * Requirements before dependents; stable for the file's order. Only called on a cycle-free
     * catalog.
     */
    private static List<String> topologicalOrder(List<AddonDefinition> list) {
        Map<String, AddonDefinition> byId = new HashMap<>();
        list.forEach(d -> byId.put(d.id(), d));
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AddonDefinition d : list) {
            visit(d.id(), byId, seen, out);
        }
        return List.copyOf(out);
    }

    private static void visit(
            String id, Map<String, AddonDefinition> byId, Set<String> seen, List<String> out) {
        if (!seen.add(id)) {
            return;
        }
        for (String r : byId.get(id).requires()) {
            visit(r, byId, seen, out);
        }
        out.add(id);
    }
}
