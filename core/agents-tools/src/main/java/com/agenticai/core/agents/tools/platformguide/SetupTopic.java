package com.agenticai.core.agents.tools.platformguide;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One thing a person can set up on this platform and where: the console page that does it, the steps,
 * the docs that explain it. Loaded from {@code platform-guide/topics.json}; the page paths are checked
 * against the web app's routes by {@code PlatformGuideCatalogTest}.
 *
 * @param keywords words and phrases a request is matched against (lower case)
 * @param path the console route that sets it up (relative; the app resolves it)
 * @param docs paths under the bundled docs (for example {@code console/configuration.md})
 * @param also ids of topics that usually have to be done as well
 * @param apiHints queries to pass to {@code vegaduta_api_guide} for the REST side
 * @param needs a permission or prerequisite the person should know about, or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SetupTopic(
        String id,
        String title,
        List<String> keywords,
        String summary,
        String path,
        List<String> steps,
        List<String> docs,
        List<String> also,
        List<String> apiHints,
        String needs) {

    public SetupTopic {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        steps = steps == null ? List.of() : List.copyOf(steps);
        docs = docs == null ? List.of() : List.copyOf(docs);
        also = also == null ? List.of() : List.copyOf(also);
        apiHints = apiHints == null ? List.of() : List.copyOf(apiHints);
    }
}
