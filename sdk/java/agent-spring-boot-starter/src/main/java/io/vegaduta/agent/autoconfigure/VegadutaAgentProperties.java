package io.vegaduta.agent.autoconfigure;

import io.vegaduta.agent.api.hook.FailMode;
import io.vegaduta.agent.api.hook.HookPoint;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vegaduta.agent.*} configuration. Every default matches {@link
 * GovernancePolicy#defaults()}.
 */
@ConfigurationProperties(prefix = "vegaduta.agent")
public class VegadutaAgentProperties {

  /** Master switch. {@code false} removes every bean; tools run ungoverned. */
  private boolean enabled = true;

  /** How unclassified tools are treated: STRICT denies, WARN runs with a warning, OFF bypasses. */
  private GovernanceMode mode = GovernanceMode.WARN;

  /** Classification applied to unclassified tools in WARN mode. */
  private RiskLabel defaultRisk = RiskLabel.MEDIUM;

  /** Characters of tool arguments included in approval summaries and audit detail. */
  private int argumentPreviewChars = 500;

  private final Approval approval = new Approval();
  private final Budget budget = new Budget();
  private final Journal journal = new Journal();
  private final Guardrails guardrails = new Guardrails();
  private final Audit audit = new Audit();
  private final Telemetry telemetry = new Telemetry();
  private final Store store = new Store();
  private final List<Webhook> webhooks = new ArrayList<>();

  public GovernancePolicy toPolicy() {
    return GovernancePolicy.builder()
        .mode(mode)
        .defaultRisk(defaultRisk)
        .approvalRequiredFrom(approval.requiredFrom)
        .approvalTtl(approval.ttl)
        .fourEyes(approval.fourEyes)
        .maxToolCallsPerRun(budget.maxToolCallsPerRun)
        .maxTokensPerRun(budget.maxTokensPerRun)
        .maxUsdPerRun(budget.maxUsdPerRun)
        .estimatedUsdPerToolCall(budget.estimatedUsdPerToolCall)
        .effectJournalTtl(journal.ttl)
        .argumentPreviewChars(argumentPreviewChars)
        .build();
  }

  public static class Approval {
    /** Side-effecting tools at or above this risk need a human. */
    private RiskLabel requiredFrom = RiskLabel.HIGH;

    /** How long a request stays decidable. */
    private Duration ttl = Duration.ofHours(24);

    /** The requester may not approve their own request. */
    private boolean fourEyes = true;

    private final Endpoint endpoint = new Endpoint();

    public static class Endpoint {
      /** Expose REST endpoints to list and decide approvals. Secure the path yourself. */
      private boolean enabled = false;

      /** Base path of the endpoints. */
      private String path = "/vegaduta/approvals";

      public boolean isEnabled() {
        return enabled;
      }

      public void setEnabled(boolean enabled) {
        this.enabled = enabled;
      }

      public String getPath() {
        return path;
      }

      public void setPath(String path) {
        this.path = path;
      }
    }

    public RiskLabel getRequiredFrom() {
      return requiredFrom;
    }

    public void setRequiredFrom(RiskLabel requiredFrom) {
      this.requiredFrom = requiredFrom;
    }

    public Duration getTtl() {
      return ttl;
    }

    public void setTtl(Duration ttl) {
      this.ttl = ttl;
    }

    public boolean isFourEyes() {
      return fourEyes;
    }

    public void setFourEyes(boolean fourEyes) {
      this.fourEyes = fourEyes;
    }

    public Endpoint getEndpoint() {
      return endpoint;
    }
  }

  public static class Budget {
    /** Tool calls per run; 0 is unlimited. */
    private long maxToolCallsPerRun = 50;

    /** Model tokens per run; 0 is unlimited. */
    private long maxTokensPerRun = 0;

    /** US dollars per run; 0 is unlimited. */
    private BigDecimal maxUsdPerRun = BigDecimal.ZERO;

    /** Reserved per tool call when the real cost is unknown. */
    private BigDecimal estimatedUsdPerToolCall = BigDecimal.ZERO;

    public long getMaxToolCallsPerRun() {
      return maxToolCallsPerRun;
    }

    public void setMaxToolCallsPerRun(long maxToolCallsPerRun) {
      this.maxToolCallsPerRun = maxToolCallsPerRun;
    }

    public long getMaxTokensPerRun() {
      return maxTokensPerRun;
    }

    public void setMaxTokensPerRun(long maxTokensPerRun) {
      this.maxTokensPerRun = maxTokensPerRun;
    }

    public BigDecimal getMaxUsdPerRun() {
      return maxUsdPerRun;
    }

    public void setMaxUsdPerRun(BigDecimal maxUsdPerRun) {
      this.maxUsdPerRun = maxUsdPerRun;
    }

    public BigDecimal getEstimatedUsdPerToolCall() {
      return estimatedUsdPerToolCall;
    }

    public void setEstimatedUsdPerToolCall(BigDecimal estimatedUsdPerToolCall) {
      this.estimatedUsdPerToolCall = estimatedUsdPerToolCall;
    }
  }

  public static class Journal {
    /** How long replayable results are kept. */
    private Duration ttl = Duration.ofHours(1);

    public Duration getTtl() {
      return ttl;
    }

    public void setTtl(Duration ttl) {
      this.ttl = ttl;
    }
  }

  public static class Guardrails {
    /** Register the built-in secret and prompt-injection rules. */
    private boolean builtIn = true;

    public boolean isBuiltIn() {
      return builtIn;
    }

    public void setBuiltIn(boolean builtIn) {
      this.builtIn = builtIn;
    }
  }

  public static class Audit {
    /** Write audit events to the {@code io.vegaduta.agent.audit} logger. */
    private boolean log = true;

    public boolean isLog() {
      return log;
    }

    public void setLog(boolean log) {
      this.log = log;
    }
  }

  public static class Telemetry {
    /** Record Micrometer metrics when a MeterRegistry is present. */
    private boolean micrometer = true;

    /** Emit OpenTelemetry spans when an OpenTelemetry bean is present. */
    private boolean opentelemetry = true;

    public boolean isMicrometer() {
      return micrometer;
    }

    public void setMicrometer(boolean micrometer) {
      this.micrometer = micrometer;
    }

    public boolean isOpentelemetry() {
      return opentelemetry;
    }

    public void setOpentelemetry(boolean opentelemetry) {
      this.opentelemetry = opentelemetry;
    }
  }

  public enum StoreType {
    MEMORY,
    JDBC
  }

  public static class Store {
    /**
     * Where approvals, effects and audit events live. JDBC needs vegaduta-agent-jdbc and a
     * DataSource.
     */
    private StoreType type = StoreType.MEMORY;

    /** Create the SDK tables at startup (development only; use migrations in production). */
    private boolean initializeSchema = false;

    /** Also write audit events to the JDBC table (in addition to the log). */
    private boolean jdbcAudit = true;

    public StoreType getType() {
      return type;
    }

    public void setType(StoreType type) {
      this.type = type;
    }

    public boolean isInitializeSchema() {
      return initializeSchema;
    }

    public void setInitializeSchema(boolean initializeSchema) {
      this.initializeSchema = initializeSchema;
    }

    public boolean isJdbcAudit() {
      return jdbcAudit;
    }

    public void setJdbcAudit(boolean jdbcAudit) {
      this.jdbcAudit = jdbcAudit;
    }
  }

  /** An HTTP policy hook. */
  public static class Webhook {
    private String name = "webhook";
    private @Nullable String url;
    private @Nullable String secret;
    private Set<HookPoint> points = Set.of(HookPoint.BEFORE_TOOL_CALL);
    private String toolPattern = "*";
    private FailMode failMode = FailMode.OPEN;
    private Duration timeout = Duration.ofSeconds(5);

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public @Nullable String getUrl() {
      return url;
    }

    public void setUrl(@Nullable String url) {
      this.url = url;
    }

    public @Nullable String getSecret() {
      return secret;
    }

    public void setSecret(@Nullable String secret) {
      this.secret = secret;
    }

    public Set<HookPoint> getPoints() {
      return points;
    }

    public void setPoints(Set<HookPoint> points) {
      this.points = points;
    }

    public String getToolPattern() {
      return toolPattern;
    }

    public void setToolPattern(String toolPattern) {
      this.toolPattern = toolPattern;
    }

    public FailMode getFailMode() {
      return failMode;
    }

    public void setFailMode(FailMode failMode) {
      this.failMode = failMode;
    }

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public GovernanceMode getMode() {
    return mode;
  }

  public void setMode(GovernanceMode mode) {
    this.mode = mode;
  }

  public RiskLabel getDefaultRisk() {
    return defaultRisk;
  }

  public void setDefaultRisk(RiskLabel defaultRisk) {
    this.defaultRisk = defaultRisk;
  }

  public int getArgumentPreviewChars() {
    return argumentPreviewChars;
  }

  public void setArgumentPreviewChars(int argumentPreviewChars) {
    this.argumentPreviewChars = argumentPreviewChars;
  }

  public Approval getApproval() {
    return approval;
  }

  public Budget getBudget() {
    return budget;
  }

  public Journal getJournal() {
    return journal;
  }

  public Guardrails getGuardrails() {
    return guardrails;
  }

  public Audit getAudit() {
    return audit;
  }

  public Telemetry getTelemetry() {
    return telemetry;
  }

  public Store getStore() {
    return store;
  }

  public List<Webhook> getWebhooks() {
    return webhooks;
  }
}
