package com.agenticai.core.common.roi;

import com.agenticai.core.common.roi.RoiDtos.CostReportResponse;
import com.agenticai.core.common.roi.RoiDtos.CostRow;
import com.agenticai.core.common.roi.RoiDtos.UnpricedModel;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cost attribution from {@code llm_call_log}. The database groups token counts by (day, agent,
 * workflow, channel, owner, provider, model); each group is priced here with {@link RoiCostModel}
 * against the tenant's {@code llm_price} versions (pricing is linear in tokens, so group-then-price
 * equals price-then-sum), and the groups are rolled up to the requested dimension.
 *
 * <p>Attribution joins, all on recorded data: workflow = the run whose id is the call's session id
 * (a workflow agent node uses its run id as the chat session); channel = the channel session that
 * owns the session id; owner = {@code agent.created_by}. A call that matches no row for a dimension
 * is reported under the key {@code null} / "unattributed", not guessed. There is no "team" concept
 * in the data model, so the nearest recorded grouping is the agent owner.
 *
 * <p>Transactional on purpose: a raw {@code JdbcTemplate} only runs in the tenant's schema when it
 * joins the JPA-bound connection that {@code SchemaMultiTenantConnectionProvider} scopes, which
 * needs an open transaction; outside one it would read the default schema. {@code RoiApiIT} fails
 * if that is removed.
 */
@Service
@Transactional(readOnly = true)
public class RoiCostService {

    public static final String COST_BASIS =
            "estimated: recorded tokens x the tenant's own llm_price rates; unpriced models are excluded, not guessed";
    static final int MAX_RANGE_DAYS = 366;
    private static final String HOSTED = "PLATFORM_HOSTED";

    /** One database group, priced. */
    record Grain(
            LocalDate day,
            UUID agentId,
            UUID workflowId,
            String channel,
            String owner,
            String provider,
            String model,
            long calls,
            long failed,
            long withoutUsage,
            RoiCostModel.Usage usage,
            RoiCostModel.Cost cost) {
        boolean hosted() {
            return HOSTED.equals(provider);
        }
    }

    private final JdbcTemplate jdbc;
    private final RoiPriceService prices;

    public RoiCostService(JdbcTemplate jdbc, RoiPriceService prices) {
        this.jdbc = jdbc;
        this.prices = prices;
    }

    public static LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "range is limited to " + MAX_RANGE_DAYS + " days");
        }
        return new LocalDate[] {start, end};
    }

    List<Grain> grains(LocalDate from, LocalDate to) {
        List<RoiCostModel.Rate> rates = prices.rates();
        List<Grain> out = new ArrayList<>();
        jdbc.query(
                "SELECT (l.started_at AT TIME ZONE 'UTC')::date AS day, l.agent_id, wr.workflow_id, cs.channel,"
                        + " a.created_by AS owner, l.provider_name, l.model, count(*) AS calls,"
                        + " count(*) FILTER (WHERE NOT l.success) AS failed,"
                        + " count(*) FILTER (WHERE l.success AND (l.prompt_tokens IS NULL OR l.completion_tokens IS NULL)) AS no_usage,"
                        + " COALESCE(sum(l.prompt_tokens),0) AS p, COALESCE(sum(l.completion_tokens),0) AS c,"
                        + " COALESCE(sum(l.cache_creation_tokens),0) AS cc, COALESCE(sum(l.cache_read_tokens),0) AS cr,"
                        + " COALESCE(sum(l.reasoning_tokens),0) AS r"
                        + " FROM llm_call_log l"
                        + " LEFT JOIN agent a ON a.id = l.agent_id"
                        + " LEFT JOIN workflow_run wr ON wr.id = CASE WHEN l.session_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'"
                        + "   THEN l.session_id::uuid END"
                        + " LEFT JOIN LATERAL (SELECT channel FROM channel_session WHERE session_id = l.session_id LIMIT 1) cs ON true"
                        + " WHERE l.started_at >= ? AND l.started_at < ?"
                        + " GROUP BY 1,2,3,4,5,6,7",
                rs -> {
                    LocalDate day = rs.getObject("day", LocalDate.class);
                    String provider = rs.getString("provider_name");
                    String model = rs.getString("model");
                    var usage =
                            new RoiCostModel.Usage(
                                    rs.getLong("p"),
                                    rs.getLong("c"),
                                    rs.getLong("cc"),
                                    rs.getLong("cr"),
                                    rs.getLong("r"));
                    var rate = RoiCostModel.select(rates, provider, model, day).orElse(null);
                    out.add(
                            new Grain(
                                    day,
                                    rs.getObject("agent_id", UUID.class),
                                    rs.getObject("workflow_id", UUID.class),
                                    rs.getString("channel"),
                                    rs.getString("owner"),
                                    provider,
                                    model,
                                    rs.getLong("calls"),
                                    rs.getLong("failed"),
                                    rs.getLong("no_usage"),
                                    usage,
                                    RoiCostModel.price(rate, usage)));
                },
                Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        return out;
    }

    public CostReportResponse report(
            LocalDate from, LocalDate to, String groupBy, Charges charges) {
        String dim = groupBy == null ? "agent" : groupBy.toLowerCase(Locale.ROOT);
        if (!List.of("agent", "workflow", "channel", "owner", "day").contains(dim)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "groupBy must be one of agent, workflow, channel, owner, day");
        }
        List<Grain> grains = grains(from, to);
        Map<String, String> labels = labels(dim);
        Map<String, List<Grain>> byKey = new LinkedHashMap<>();
        for (Grain g : grains) {
            byKey.computeIfAbsent(keyOf(dim, g), k -> new ArrayList<>()).add(g);
        }
        List<CostRow> rows = new ArrayList<>();
        byKey.forEach((k, gs) -> rows.add(row(k, labels.getOrDefault(k, k), gs)));
        rows.sort(
                dim.equals("day")
                        ? java.util.Comparator.comparing(CostRow::key)
                        : java.util.Comparator.comparing((CostRow r) -> r.estimatedCostUsd())
                                .reversed());
        return new CostReportResponse(
                from,
                to,
                dim,
                COST_BASIS,
                rows,
                row("total", "Total", grains),
                charges.minor(),
                charges.currency());
    }

    /** Recorded platform charges for the window: billable, undisputed resolution events. */
    public record Charges(long minor, String currency) {}

    public Charges platformCharges(LocalDate from, LocalDate to) {
        return jdbc.query(
                "SELECT COALESCE(SUM(unit_price_minor),0) AS minor, MAX(currency) AS currency, count(DISTINCT currency) AS n"
                        + " FROM resolution_event WHERE billable AND disputed_at IS NULL AND resolved_at >= ? AND resolved_at < ?",
                rs -> {
                    rs.next();
                    // More than one currency in a window cannot be summed honestly: report none.
                    return rs.getInt("n") > 1
                            ? new Charges(0, null)
                            : new Charges(rs.getLong("minor"), rs.getString("currency"));
                },
                Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    /** Models with calls in the window that have no applicable price. */
    public List<UnpricedModel> unpriced(LocalDate from, LocalDate to) {
        Map<String, long[]> acc = new HashMap<>();
        Map<String, String> reason = new HashMap<>();
        for (Grain g : grains(from, to)) {
            if (g.cost().priced()) {
                continue;
            }
            String k = g.provider() + "\u0000" + g.model();
            long[] a = acc.computeIfAbsent(k, x -> new long[2]);
            a[0] += g.calls();
            a[1] += g.usage().prompt() + g.usage().completion() + g.usage().reasoning();
            reason.put(k, g.cost().unpricedReason());
        }
        List<UnpricedModel> out = new ArrayList<>();
        acc.forEach(
                (k, a) -> {
                    String[] pm = k.split("\u0000", 2);
                    out.add(new UnpricedModel(pm[0], pm[1], a[0], a[1], reason.get(k)));
                });
        out.sort(java.util.Comparator.comparingLong(UnpricedModel::calls).reversed());
        return out;
    }

    static CostRow row(String key, String label, List<Grain> gs) {
        long calls = 0, failed = 0, priced = 0, unpriced = 0, noUsage = 0, p = 0, c = 0;
        BigDecimal byok = BigDecimal.ZERO, hosted = BigDecimal.ZERO;
        for (Grain g : gs) {
            calls += g.calls();
            failed += g.failed();
            noUsage += g.withoutUsage();
            p += g.usage().prompt();
            c += g.usage().completion();
            if (g.cost().priced()) {
                priced += g.calls();
                if (g.hosted()) {
                    hosted = hosted.add(g.cost().usd());
                } else {
                    byok = byok.add(g.cost().usd());
                }
            } else {
                unpriced += g.calls();
            }
        }
        return new CostRow(
                key,
                label,
                calls,
                failed,
                priced,
                unpriced,
                noUsage,
                p,
                c,
                byok,
                hosted,
                byok.add(hosted),
                unpriced == 0 && noUsage == 0);
    }

    private static String keyOf(String dim, Grain g) {
        return switch (dim) {
            case "agent" -> String.valueOf(g.agentId());
            case "workflow" -> String.valueOf(g.workflowId());
            case "channel" -> String.valueOf(g.channel());
            case "owner" -> String.valueOf(g.owner());
            default -> g.day().toString();
        };
    }

    private Map<String, String> labels(String dim) {
        Map<String, String> m = new HashMap<>();
        m.put("null", "unattributed");
        String sql =
                switch (dim) {
                    case "agent" -> "SELECT id::text, name FROM agent";
                    case "workflow" -> "SELECT id::text, name FROM workflow_definition";
                    default -> null;
                };
        if (sql != null) {
            jdbc.query(
                    sql,
                    rs -> {
                        m.put(rs.getString(1), rs.getString(2));
                    });
        }
        return m;
    }
}
