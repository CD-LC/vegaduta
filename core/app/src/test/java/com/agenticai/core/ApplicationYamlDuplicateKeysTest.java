package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Spring Boot loads YAML with duplicate keys REJECTED (OriginTrackedYamlLoader), so a key that
 * appears twice under the same parent - typically two features each adding their own
 * {@code app.<name>:} block - stops the application from starting, whatever the flags say.
 * A lenient {@code new Yaml()} parse (see {@link TracingSamplingDefaultTest}) does not notice,
 * and the context test needs Postgres, so this needs its own DB-free check: it happened at
 * integration on 2026-10-01 ({@code app.mcp} declared by both the OAuth and the routing work).
 */
class ApplicationYamlDuplicateKeysTest {

    @Test
    void noApplicationYamlDeclaresAKeyTwice() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:application*.yml");
        assertThat(files).as("application*.yml on the test classpath").isNotEmpty();
        assertThat(files).as("the main application.yml").anyMatch(r -> "application.yml".equals(r.getFilename()));

        LoaderOptions strict = new LoaderOptions();
        strict.setAllowDuplicateKeys(false);
        for (Resource file : files) {
            assertThatCode(() -> {
                try (InputStream in = file.getInputStream()) {
                    // Multi-document files (--- separators) are legal; every document is checked.
                    new Yaml(strict).loadAll(in).forEach(doc -> { });
                }
            }).as("duplicate key in %s", file.getDescription()).doesNotThrowAnyException();
        }
    }
}
