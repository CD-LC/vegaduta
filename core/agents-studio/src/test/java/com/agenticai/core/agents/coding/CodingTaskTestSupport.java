package com.agenticai.core.agents.coding;

import com.agenticai.core.agents.coding.CodingTaskDtos.TaskDocument;
import com.agenticai.core.sdlc.harness.CodingCommandPolicy;
import com.agenticai.core.sdlc.harness.CodingSandbox;
import com.agenticai.core.sdlc.harness.ShellRunner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Fakes shared by the coding-task tests: an in-memory store, a scripted sandbox and a scripted model. */
final class CodingTaskTestSupport {

    private CodingTaskTestSupport() {
    }

    static final String DIFF = "diff --git a/src/Foo.java b/src/Foo.java\n--- a/src/Foo.java\n+++ b/src/Foo.java\n@@ -1 +1 @@\n-a\n+b\n";

    /** Waits (up to ~10s) for a condition the worker thread makes true. */
    static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for the condition");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    // ---------------------------------------------------------------------------------------------------

    static final class InMemoryStore implements CodingTaskStore {
        final Map<String, TaskDocument> docs = new ConcurrentHashMap<>();
        final Map<String, String> diffs = new ConcurrentHashMap<>();
        final Map<String, String> locks = new ConcurrentHashMap<>();
        final Set<String> cancels = ConcurrentHashMap.newKeySet();
        final Map<String, String> decisions = new ConcurrentHashMap<>();
        final Set<String> alive = ConcurrentHashMap.newKeySet();
        final Map<String, List<String>> index = new ConcurrentHashMap<>();

        private static String k(String tenant, String id) {
            return tenant + "|" + id;
        }

        @Override
        public void save(String tenantId, TaskDocument doc) {
            docs.put(k(tenantId, doc.task().taskId()), doc);
        }

        @Override
        public Optional<TaskDocument> find(String tenantId, String taskId) {
            return Optional.ofNullable(docs.get(k(tenantId, taskId)));
        }

        @Override
        public void delete(String tenantId, String taskId) {
            docs.remove(k(tenantId, taskId));
        }

        @Override
        public void saveDiff(String tenantId, String taskId, String diff) {
            diffs.put(k(tenantId, taskId), diff);
        }

        @Override
        public Optional<String> findDiff(String tenantId, String taskId) {
            return Optional.ofNullable(diffs.get(k(tenantId, taskId)));
        }

        @Override
        public void deleteDiff(String tenantId, String taskId) {
            diffs.remove(k(tenantId, taskId));
        }

        @Override
        public boolean acquireLock(String tenantId, String subject, String taskId) {
            return locks.putIfAbsent(k(tenantId, subject), taskId) == null;
        }

        @Override
        public void releaseLock(String tenantId, String subject, String taskId) {
            locks.remove(k(tenantId, subject), taskId);
        }

        @Override
        public Optional<String> lockHolder(String tenantId, String subject) {
            return Optional.ofNullable(locks.get(k(tenantId, subject)));
        }

        @Override
        public void requestCancel(String tenantId, String taskId) {
            cancels.add(k(tenantId, taskId));
        }

        @Override
        public boolean isCancelRequested(String tenantId, String taskId) {
            return cancels.contains(k(tenantId, taskId));
        }

        @Override
        public void setDecision(String tenantId, String taskId, String decision) {
            decisions.put(k(tenantId, taskId), decision);
        }

        @Override
        public Optional<String> decision(String tenantId, String taskId) {
            return Optional.ofNullable(decisions.get(k(tenantId, taskId)));
        }

        @Override
        public void clearDecision(String tenantId, String taskId) {
            decisions.remove(k(tenantId, taskId));
        }

        @Override
        public void markAlive(String tenantId, String taskId) {
            alive.add(k(tenantId, taskId));
        }

        @Override
        public boolean isAlive(String tenantId, String taskId) {
            return alive.contains(k(tenantId, taskId));
        }

        @Override
        public void index(String tenantId, String subject, String taskId) {
            index.computeIfAbsent(k(tenantId, subject), x -> Collections.synchronizedList(new ArrayList<>())).add(0, taskId);
        }

        @Override
        public List<String> recent(String tenantId, String subject, int limit) {
            List<String> ids = index.getOrDefault(k(tenantId, subject), List.of());
            synchronized (ids) {
                return new ArrayList<>(ids.subList(0, Math.min(limit, ids.size())));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------

    /** Emulates just enough of git and the harness's file templates to run the real loop. */
    static final class FakeSandbox implements CodingSandbox {
        final List<String> internalCommands = Collections.synchronizedList(new ArrayList<>());
        final List<String> testCommands = Collections.synchronizedList(new ArrayList<>());
        final Deque<Integer> testExits = new ArrayDeque<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final CodingCommandPolicy policy = CodingCommandPolicy.defaults();
        volatile String diff = DIFF;
        volatile int commits;
        volatile String seededGit;
        volatile byte[] seededArchive;

        @Override
        public ShellRunner internal() {
            return (command, timeout) -> {
                internalCommands.add(command);
                if (command.equals("git rev-parse HEAD")) {
                    return new ShellRunner.ShellResult(0, String.format("%07x\n", 0xabc000 + commits));
                }
                if (command.contains(" commit ")) {
                    commits++;
                }
                if (command.startsWith("git diff")) {
                    return new ShellRunner.ShellResult(0, diff);
                }
                if (command.startsWith("git ls-files")) {
                    return new ShellRunner.ShellResult(0, "src/Foo.java\n");
                }
                return new ShellRunner.ShellResult(0, "");
            };
        }

        @Override
        public ShellRunner restricted() {
            return (command, timeout) -> {
                String validated = policy.validate(command);
                testCommands.add(validated);
                Integer exit;
                synchronized (testExits) {
                    exit = testExits.isEmpty() ? Integer.valueOf(1) : testExits.poll();
                }
                return new ShellRunner.ShellResult(exit, exit == 0 ? "BUILD SUCCESS" : "FAILED: expected 1 but was 2");
            };
        }

        @Override
        public void seedFromArchive(byte[] sanitizedZip) {
            seededArchive = sanitizedZip;
        }

        @Override
        public void seedFromGit(String httpsUrl) {
            seededGit = httpsUrl;
        }

        @Override
        public void prepareRepository() {
        }

        @Override
        public String describe() {
            return "fake sandbox";
        }

        @Override
        public void close() {
            closed.set(true);
        }

        boolean rolledBack() {
            synchronized (internalCommands) {
                return internalCommands.stream().anyMatch(c -> c.startsWith("git reset -q --hard "));
            }
        }

        boolean wroteAnything() {
            synchronized (internalCommands) {
                return internalCommands.stream().anyMatch(c -> c.contains("base64 -d"));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------

    /** Answers the fixer with queued replies (a default edit when empty) and the reviewer with a fixed verdict. */
    static final class ScriptedModel implements CodingModel {
        final Deque<String> fixerReplies = new ArrayDeque<>();
        final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        volatile String reviewReply = "{\"approved\": true, \"notes\": \"matches the task\"}";
        volatile String defaultFixerReply = "{\"edits\":[{\"path\":\"src/Foo.java\",\"content\":\"class Foo { }\"}],\"note\":\"changed Foo\"}";

        @Override
        public String complete(String systemPrompt, String userMessage) {
            prompts.add(userMessage);
            if (systemPrompt.contains("You review a code change")) {
                return reviewReply;
            }
            synchronized (fixerReplies) {
                return fixerReplies.isEmpty() ? defaultFixerReply : fixerReplies.poll();
            }
        }
    }

    static Map<String, String> sampleRepo() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pom.xml", "<project/>");
        files.put("src/Foo.java", "class Foo { }");
        return files;
    }
}
