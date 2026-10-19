package com.agenticai.core.graphmemory;

import com.agenticai.core.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Command-line runner for {@link MemoryEval} ("LongMemEval-lite (own fixtures)"). With no
 * arguments it evaluates the in-memory graph; with {@code --jdbc-url} it evaluates a real Apache
 * AGE Postgres through the real {@link GraphMemoryService}, in the given tenant's graph:
 *
 * <pre>
 * java -cp &lt;test-classes:classes:deps&gt; com.agenticai.core.graphmemory.MemoryEvalRunner \
 *     --jdbc-url=jdbc:postgresql://host:5432/db --user=postgres --password=... --tenant=memeval_a [--out=report.txt]
 * </pre>
 *
 * The tenant's graph ({@code <tenant>_kg}) must already exist. Every case writes under its own
 * throw-away {@code subject_key} ({@code <run id>:<mode>:<case>}) and erases it afterwards, but it
 * DOES write (and DETACH DELETE) in that graph: point it at a staging tenant, never at one whose
 * data matters. Valid-from stamps are synthetic session dates, never wall-clock time.
 */
public final class MemoryEvalRunner {

    private MemoryEvalRunner() {
    }

    public static void main(String[] args) throws Exception {
        String url = arg(args, "jdbc-url");
        String user = arg(args, "user");
        String password = arg(args, "password");
        String tenant = arg(args, "tenant");
        String out = arg(args, "out");
        String runId = "memeval-" + UUID.randomUUID().toString().substring(0, 8);

        GraphMemoryService store;
        String backend;
        SingleConnectionDataSource ds = null;
        if (url == null) {
            store = new InMemoryTemporalGraphMemory();
            backend = "in-memory temporal graph";
        } else {
            if (tenant == null) {
                throw new IllegalArgumentException("--tenant is required with --jdbc-url");
            }
            ds = new SingleConnectionDataSource(url, user == null ? "postgres" : user, password == null ? "" : password, true);
            store = new GraphMemoryService(new JdbcTemplate(ds));
            TenantContext.set(TenantContext.of(tenant, "memeval-runner", Set.of()));
            backend = "real Apache AGE via " + url + " (tenant " + tenant + ")";
        }
        try {
            Map<MemoryEval.Mode, List<MemoryEval.CaseResult>> all =
                MemoryEval.runAll(MemoryEval.loadFixture(), store, new MemoryEval.ExtractiveAnswerer(), runId);
            String report = MemoryEval.report(backend, all);
            System.out.println(report);
            if (out != null) {
                Files.writeString(Path.of(out), report, StandardCharsets.UTF_8);
            }
        } finally {
            TenantContext.clear();
            if (ds != null) {
                ds.destroy();
            }
        }
    }

    private static String arg(String[] args, String name) {
        String prefix = "--" + name + "=";
        for (String a : args) {
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return null;
    }
}
