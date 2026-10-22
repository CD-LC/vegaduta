package com.agenticai.core.agents.skills;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The public Agent Skills registry, the skills counterpart of the MCP registry: search a public
 * index, then install a skill in one click.
 *
 * <p>Search is proxied to SkillsMP (an index of public SKILL.md folders on GitHub). Install never
 * trusts the index for content: it takes the GitHub folder URL, fetches that folder from GitHub
 * itself, zips it in memory and hands it to the SAME {@link SkillService#importArchive} path an
 * uploaded .zip takes - so every size, path and format limit applies unchanged.
 *
 * <p>Outbound hosts are fixed (skillsmp.com, api.github.com, raw.githubusercontent.com); the only
 * caller-supplied part is a GitHub owner/repo/ref/path matched by {@link #GITHUB_TREE}, so this is
 * not an open fetch. A skill here is instructions and text files only; nothing in it is executed.
 */
@Component
public class SkillRegistryClient {

    private static final String SEARCH_URL = "https://skillsmp.com/api/v1/skills/search";
    private static final Pattern GITHUB_TREE =
            Pattern.compile(
                    "^https://github\\.com/([A-Za-z0-9_.-]{1,100})/([A-Za-z0-9_.-]{1,100})/tree/([A-Za-z0-9_./-]{1,100}?)/((?:[A-Za-z0-9_. -]+/?)+)$");
    private static final int MAX_DEPTH = 4;

    // Guarded transport; defaults() resolves the process-wide SsrfGuard lazily at
    // lookup time, so the operator's allow-list is always in effect.
    private final HttpClient http =
            GuardedJdkHttpClient.defaults(Duration.ofSeconds(8), Duration.ofSeconds(30), true);
    private final ObjectMapper mapper;
    private final String registryApiKey;
    private final String githubToken;

    public SkillRegistryClient(
            ObjectMapper mapper,
            @Value("${app.skills.registry.api-key:}") String registryApiKey,
            @Value("${app.skills.registry.github-token:}") String githubToken) {
        this.mapper = mapper;
        this.registryApiKey = registryApiKey;
        this.githubToken = githubToken;
    }

    public record RegistrySkill(
            String id,
            String name,
            String author,
            String description,
            String githubUrl,
            String registryUrl,
            long stars) {}

    public record SearchResult(List<RegistrySkill> skills, int page, int totalPages, long total) {}

    /** SE-74: how long a search answer is reused; the upstream took up to 2.9 s per call. */
    static final long SEARCH_CACHE_TTL_NANOS = Duration.ofSeconds(60).toNanos();

    private static final int SEARCH_CACHE_MAX = 200;

    private record CachedSearch(SearchResult result, long storedAtNanos) {}

    private final java.util.concurrent.ConcurrentHashMap<String, CachedSearch> searchCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.function.LongSupplier nanoClock = System::nanoTime;

    /** Test seam for the cache TTL. */
    void setNanoClockForTest(java.util.function.LongSupplier clock) {
        this.nanoClock = clock;
    }

    /**
     * Search with a short server-side cache (the registry is public, the answer is the same for
     * every tenant, and only successful answers are cached so a registry outage is never pinned).
     */
    public SearchResult search(String query, int page) {
        String q = query == null || query.isBlank() ? "skill" : query.trim();
        if (q.length() > 100) {
            q = q.substring(0, 100);
        }
        return cachedSearch(q, Math.max(1, Math.min(page, 50)), () -> fetchSearch(query, page));
    }

    SearchResult cachedSearch(
            String q, int page, java.util.function.Supplier<SearchResult> loader) {
        String key = page + "|" + q.toLowerCase(java.util.Locale.ROOT);
        long now = nanoClock.getAsLong();
        CachedSearch hit = searchCache.get(key);
        if (hit != null && now - hit.storedAtNanos() < SEARCH_CACHE_TTL_NANOS) {
            return hit.result();
        }
        SearchResult fresh = loader.get();
        if (searchCache.size() >= SEARCH_CACHE_MAX) {
            searchCache.values().removeIf(c -> now - c.storedAtNanos() >= SEARCH_CACHE_TTL_NANOS);
            if (searchCache.size() >= SEARCH_CACHE_MAX) {
                searchCache.clear();
            }
        }
        searchCache.put(key, new CachedSearch(fresh, now));
        return fresh;
    }

    private SearchResult fetchSearch(String query, int page) {
        String q = query == null || query.isBlank() ? "skill" : query.trim();
        if (q.length() > 100) {
            q = q.substring(0, 100);
        }
        String url =
                SEARCH_URL
                        + "?q="
                        + URLEncoder.encode(q, StandardCharsets.UTF_8)
                        + "&page="
                        + Math.max(1, Math.min(page, 50))
                        + "&limit=24&sortBy=stars";
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12));
        if (!registryApiKey.isBlank()) {
            request.header("Authorization", "Bearer " + registryApiKey);
        }
        JsonNode root =
                getJson(
                        request.build(),
                        "The skills registry is not answering right now. Try again in a minute.");
        JsonNode data = root.path("data");
        List<RegistrySkill> out = new ArrayList<>();
        for (JsonNode s : data.path("skills")) {
            String github = s.path("githubUrl").asText("");
            if (!GITHUB_TREE.matcher(github).matches()) {
                continue;
            }
            out.add(
                    new RegistrySkill(
                            s.path("id").asText(),
                            s.path("name").asText(),
                            s.path("author").asText(),
                            s.path("description").asText(),
                            github,
                            s.path("skillUrl").asText(""),
                            s.path("stars").asLong(0)));
        }
        JsonNode p = data.path("pagination");
        return new SearchResult(
                out,
                p.path("page").asInt(1),
                p.path("totalPages").asInt(1),
                p.path("total").asLong(out.size()));
    }

    /**
     * Fetch a GitHub skill folder and return it as the folder-in-a-zip {@link SkillArchiveReader}
     * reads.
     */
    public byte[] fetchAsArchive(String githubUrl) {
        Matcher m = GITHUB_TREE.matcher(githubUrl == null ? "" : githubUrl.trim());
        if (!m.matches() || githubUrl.contains("..")) {
            throw new SkillValidationException("That is not a GitHub skill folder link.");
        }
        String owner = m.group(1);
        String repo = m.group(2);
        String ref = m.group(3);
        String path = m.group(4).replaceAll("/+$", "");
        String folder = path.substring(path.lastIndexOf('/') + 1);

        Map<String, byte[]> files = new LinkedHashMap<>();
        try {
            collect(owner, repo, ref, path, path, files, 0);
        } catch (SkillValidationException rateLimited) {
            // GitHub's unauthenticated API is tightly rate limited; SKILL.md alone is still a whole
            // skill.
            files.clear();
            files.put(
                    "SKILL.md",
                    getBytes(
                            raw(owner, repo, ref, path + "/SKILL.md"),
                            "Could not download that skill from GitHub. Try again in a few minutes."));
        }
        if (!files.containsKey("SKILL.md")) {
            throw new SkillValidationException(
                    "That GitHub folder has no SKILL.md, so it is not an Agent Skill.");
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(folder + "/" + file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not package the downloaded skill", e);
        }
    }

    private void collect(
            String owner,
            String repo,
            String ref,
            String root,
            String dir,
            Map<String, byte[]> files,
            int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        String url =
                "https://api.github.com/repos/"
                        + owner
                        + "/"
                        + repo
                        + "/contents/"
                        + encodePath(dir)
                        + "?ref="
                        + URLEncoder.encode(ref, StandardCharsets.UTF_8);
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(12))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "agent-skills-registry");
        if (!githubToken.isBlank()) {
            request.header("Authorization", "Bearer " + githubToken);
        }
        JsonNode listing = getJson(request.build(), "GitHub is not answering right now.");
        if (!listing.isArray()) {
            throw new SkillValidationException("That GitHub link is not a folder.");
        }
        long total = files.values().stream().mapToLong(b -> b.length).sum();
        for (JsonNode entry : listing) {
            String type = entry.path("type").asText();
            String entryPath = entry.path("path").asText();
            if ("dir".equals(type)) {
                collect(owner, repo, ref, root, entryPath, files, depth + 1);
            } else if ("file".equals(type)) {
                long size = entry.path("size").asLong(0);
                // Oversized and surplus files are skipped here rather than failing the install;
                // importArchive enforces the hard limits on what is left.
                if (size > SkillArchiveReader.MAX_FILE_BYTES
                        || files.size() >= SkillArchiveReader.MAX_FILES
                        || total + size > SkillArchiveReader.MAX_TOTAL_BYTES) {
                    continue;
                }
                total += size;
                files.put(
                        entryPath.substring(root.length() + 1),
                        getBytes(
                                raw(owner, repo, ref, entryPath),
                                "Could not download a file of that skill from GitHub."));
            }
        }
    }

    private static String raw(String owner, String repo, String ref, String path) {
        return "https://raw.githubusercontent.com/"
                + owner
                + "/"
                + repo
                + "/"
                + ref
                + "/"
                + encodePath(path);
    }

    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/")) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    private JsonNode getJson(HttpRequest request, String failureMessage) {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new SkillValidationException(failureMessage);
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new SkillValidationException(failureMessage);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkillValidationException(failureMessage);
        }
    }

    private byte[] getBytes(String url, String failureMessage) {
        try {
            HttpResponse<byte[]> response =
                    http.send(
                            HttpRequest.newBuilder(URI.create(url))
                                    .timeout(Duration.ofSeconds(12))
                                    .header("User-Agent", "agent-skills-registry")
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2
                    || response.body().length > SkillArchiveReader.MAX_FILE_BYTES) {
                throw new SkillValidationException(failureMessage);
            }
            return response.body();
        } catch (IOException e) {
            throw new SkillValidationException(failureMessage);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkillValidationException(failureMessage);
        }
    }
}
