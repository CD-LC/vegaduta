package com.agenticai.core.agents.skills;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The out-of-the-box skills. Each is a real skill folder on core's classpath ({@code
 * skills/<name>/SKILL.md} + bundled files) in exactly the format a tenant would upload - so a
 * built-in is authored, reviewed and diffed as plain markdown, and the same parser and the same
 * runtime path serve both kinds.
 *
 * <p>Loaded once at startup and immutable: no database rows and no per-tenant seeding, which is
 * what makes them genuinely "out of the box" - a tenant provisioned a second ago has them, and
 * shipping a new one is a core deploy with no migration.
 *
 * <p>Fail-soft: a built-in that does not parse is logged and skipped rather than failing boot. A
 * typo in a markdown file must never take the platform down; {@code BuiltInSkillCatalogTest} is
 * what stops one from shipping.
 */
@Component
public class BuiltInSkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(BuiltInSkillCatalog.class);
    private static final String ROOT = "skills/";
    private static final String SKILL_MD = "SKILL.md";

    private final Map<String, SkillDefinition> skills;

    public BuiltInSkillCatalog() {
        this.skills = Collections.unmodifiableMap(load());
    }

    public Collection<SkillDefinition> all() {
        return skills.values();
    }

    public Optional<SkillDefinition> find(String name) {
        return Optional.ofNullable(name == null ? null : skills.get(name));
    }

    public boolean contains(String name) {
        return name != null && skills.containsKey(name);
    }

    private static Map<String, SkillDefinition> load() {
        // skill folder -> (relative path -> text)
        Map<String, Map<String, String>> folders = new TreeMap<>();
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver(
                                    BuiltInSkillCatalog.class.getClassLoader())
                            .getResources("classpath*:" + ROOT + "**/*");
            for (Resource resource : resources) {
                if (!resource.isReadable()) {
                    continue;
                }
                String url = resource.getURL().toString();
                int at = url.lastIndexOf("/" + ROOT);
                if (at < 0) {
                    continue;
                }
                String relative = url.substring(at + 1 + ROOT.length());
                int slash = relative.indexOf('/');
                if (slash <= 0 || relative.endsWith("/")) {
                    continue;
                }
                String folder = relative.substring(0, slash);
                String path = relative.substring(slash + 1);
                try (InputStream in = resource.getInputStream()) {
                    folders.computeIfAbsent(folder, k -> new LinkedHashMap<>())
                            .put(path, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            log.error(
                    "Could not scan the classpath for built-in skills - none will be available", e);
            return Map.of();
        }

        Map<String, SkillDefinition> loaded = new TreeMap<>();
        folders.forEach(
                (folder, files) -> {
                    String skillMd = files.remove(SKILL_MD);
                    if (skillMd == null) {
                        log.error("Built-in skill folder '{}' has no SKILL.md - skipped", folder);
                        return;
                    }
                    try {
                        SkillMarkdownParser.Parsed parsed = SkillMarkdownParser.parse(skillMd);
                        if (!parsed.name().equals(folder)) {
                            log.error(
                                    "Built-in skill folder '{}' declares name '{}' - they must match; skipped",
                                    folder,
                                    parsed.name());
                            return;
                        }
                        loaded.put(
                                parsed.name(),
                                new SkillDefinition(
                                        null,
                                        parsed.name(),
                                        parsed.description(),
                                        parsed.body(),
                                        files,
                                        SkillSource.BUILT_IN));
                    } catch (SkillValidationException e) {
                        log.error(
                                "Built-in skill '{}' is invalid and was skipped: {}",
                                folder,
                                e.getMessage());
                    }
                });
        log.info("Loaded {} built-in skill(s): {}", loaded.size(), loaded.keySet());
        return loaded;
    }
}
