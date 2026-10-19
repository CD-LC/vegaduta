package io.vegaduta.agent.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.annotation.Governed;
import io.vegaduta.agent.api.approval.ApprovalGate;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import io.vegaduta.agent.api.approval.ApprovalStore;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.classification.StaticClassificationSource;
import io.vegaduta.agent.jdbc.JdbcApprovalStore;
import io.vegaduta.agent.springai.GovernanceAdvisor;
import io.vegaduta.agent.springai.GovernedToolCallback;
import io.vegaduta.agent.springai.ToolGovernor;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class VegadutaAgentAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(VegadutaAgentAutoConfiguration.class));

  @Configuration(proxyBeanMethods = false)
  static class ConsumerApp {
    static class Payments {
      @Tool(description = "Refund an order")
      @Governed(risk = RiskLabel.HIGH, requiresApproval = true)
      public String refund(String orderId) {
        return "refunded " + orderId;
      }
    }

    @Bean
    Payments payments() {
      return new Payments();
    }

    @Bean
    ToolCallback weather() {
      return new ToolCallback() {
        @Override
        public ToolDefinition getToolDefinition() {
          return ToolDefinition.builder()
              .name("weather")
              .description("weather")
              .inputSchema("{}")
              .build();
        }

        @Override
        public String call(String toolInput) {
          return "sunny";
        }
      };
    }
  }

  @Test
  void wiresGovernanceWithDefaultsAndGovernsToolBeans() {
    runner
        .withUserConfiguration(ConsumerApp.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Governance.class);
              assertThat(context).hasSingleBean(ToolGovernor.class);
              assertThat(context).hasSingleBean(GovernanceAdvisor.class);
              assertThat(context).hasSingleBean(ApprovalGate.class);
              assertThat(context.getBean(Governance.class).policy().mode())
                  .isEqualTo(GovernanceMode.WARN);

              ToolCallback weather = context.getBean("weather", ToolCallback.class);
              assertThat(weather).isInstanceOf(GovernedToolCallback.class);
              assertThat(weather.call("{}")).isEqualTo("sunny");

              StaticClassificationSource classifications =
                  context.getBean("vegadutaClassifications", StaticClassificationSource.class);
              assertThat(classifications.classify("refund")).isPresent();
              assertThat(classifications.classify("refund").get().requiresApproval()).isTrue();

              ToolCallback refund =
                  context
                      .getBean(ToolGovernor.class)
                      .govern(
                          MethodToolCallbackProvider.builder()
                              .toolObjects(context.getBean(ConsumerApp.Payments.class))
                              .build()
                              .getToolCallbacks()[0]);
              assertThat(refund.call("{\"orderId\":\"1\"}")).contains("requires human approval");
              assertThat(context.getBean(ApprovalGate.class).pending(null, 10)).hasSize(1);
            });
  }

  @Test
  void propertiesDriveThePolicy() {
    runner
        .withPropertyValues(
            "vegaduta.agent.mode=strict",
            "vegaduta.agent.approval.required-from=medium",
            "vegaduta.agent.approval.four-eyes=false",
            "vegaduta.agent.budget.max-tool-calls-per-run=3",
            "vegaduta.agent.budget.max-usd-per-run=1.50")
        .run(
            context -> {
              Governance governance = context.getBean(Governance.class);
              assertThat(governance.policy().mode()).isEqualTo(GovernanceMode.STRICT);
              assertThat(governance.policy().approvalRequiredFrom()).isEqualTo(RiskLabel.MEDIUM);
              assertThat(governance.policy().fourEyes()).isFalse();
              assertThat(governance.policy().maxToolCallsPerRun()).isEqualTo(3);
              assertThat(governance.policy().maxUsdPerRun()).isEqualByComparingTo("1.50");
            });
  }

  @Test
  void disabledRemovesEverything() {
    runner
        .withPropertyValues("vegaduta.agent.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(Governance.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class WithDataSource {
    @Bean
    DataSource dataSource() {
      JdbcDataSource ds = new JdbcDataSource();
      ds.setURL("jdbc:h2:mem:starter;DB_CLOSE_DELAY=-1");
      return ds;
    }
  }

  @Test
  void jdbcStoreIsUsedWhenConfigured() {
    runner
        .withUserConfiguration(WithDataSource.class, ConsumerApp.class)
        .withPropertyValues(
            "vegaduta.agent.store.type=jdbc",
            "vegaduta.agent.store.initialize-schema=true",
            "vegaduta.agent.mode=strict")
        .run(
            context -> {
              assertThat(context).hasSingleBean(ApprovalStore.class);
              assertThat(context.getBean(ApprovalStore.class))
                  .isInstanceOf(JdbcApprovalStore.class);
              ToolCallback refund =
                  context
                      .getBean(ToolGovernor.class)
                      .govern(
                          MethodToolCallbackProvider.builder()
                              .toolObjects(context.getBean(ConsumerApp.Payments.class))
                              .build()
                              .getToolCallbacks()[0]);
              refund.call("{\"orderId\":\"7\"}");
              ApprovalRequest pending =
                  context.getBean(ApprovalStore.class).findPending(null, 10).get(0);
              assertThat(pending.status()).isEqualTo(ApprovalStatus.PENDING);
              ApprovalRequest executed =
                  context.getBean(ApprovalGate.class).approve(pending.id(), "reviewer");
              assertThat(executed.status()).isEqualTo(ApprovalStatus.EXECUTED);
              assertThat(executed.resultJson()).contains("refunded 7");
            });
  }
}
