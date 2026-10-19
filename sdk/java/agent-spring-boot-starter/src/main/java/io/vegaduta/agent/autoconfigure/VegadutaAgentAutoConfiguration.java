package io.vegaduta.agent.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.vegaduta.agent.api.approval.ApprovalDecisionAuthorizer;
import io.vegaduta.agent.api.approval.ApprovalGate;
import io.vegaduta.agent.api.approval.ApprovalStore;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.budget.BudgetProvider;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.guardrail.ContentGuardrail;
import io.vegaduta.agent.api.journal.EffectJournal;
import io.vegaduta.agent.api.observe.ToolCallObserver;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.risk.ToolClassificationSource;
import io.vegaduta.agent.core.Governance;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.audit.LoggingAuditSink;
import io.vegaduta.agent.core.classification.StaticClassificationSource;
import io.vegaduta.agent.core.guardrail.GuardrailChain;
import io.vegaduta.agent.core.hook.HookRegistration;
import io.vegaduta.agent.core.hook.WebhookHook;
import io.vegaduta.agent.core.observe.MicrometerToolCallObserver;
import io.vegaduta.agent.core.observe.OpenTelemetryToolCallObserver;
import io.vegaduta.agent.jdbc.JdbcApprovalStore;
import io.vegaduta.agent.jdbc.JdbcAuditSink;
import io.vegaduta.agent.jdbc.JdbcEffectJournal;
import io.vegaduta.agent.jdbc.VegadutaAgentSchema;
import io.vegaduta.agent.springai.GovernanceAdvisor;
import io.vegaduta.agent.springai.RunContextResolver;
import io.vegaduta.agent.springai.ToolGovernor;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wires the SDK into a Spring Boot application. Every collaborator is a
 * {@code @ConditionalOnMissingBean} so a host can replace any one of them (for example an {@link
 * ApprovalDecisionAuthorizer} that checks roles) by declaring its own bean.
 */
@AutoConfiguration
@ConditionalOnProperty(
    prefix = "vegaduta.agent",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(VegadutaAgentProperties.class)
public class VegadutaAgentAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public GovernancePolicy vegadutaGovernancePolicy(VegadutaAgentProperties properties) {
    return properties.toPolicy();
  }

  /** Classifications discovered from {@code @Governed} annotations are registered here. */
  @Bean(name = "vegadutaClassifications")
  @ConditionalOnMissingBean(name = "vegadutaClassifications")
  public StaticClassificationSource vegadutaClassifications(
      ObjectProvider<ToolClassification> declared) {
    StaticClassificationSource source = new StaticClassificationSource();
    declared.orderedStream().forEach(source::register);
    return source;
  }

  @Bean
  @ConditionalOnMissingBean
  public Governance vegadutaGovernance(
      GovernancePolicy policy,
      StaticClassificationSource vegadutaClassifications,
      ObjectProvider<ToolClassificationSource> sources,
      ObjectProvider<ToolDispatchGate> gate,
      ObjectProvider<ApprovalStore> approvalStore,
      ObjectProvider<ApprovalDecisionAuthorizer> authorizer,
      ObjectProvider<BudgetProvider> budgets,
      ObjectProvider<EffectJournal> journal,
      ObjectProvider<GuardrailChain> chainOverride,
      ObjectProvider<ContentGuardrail> customGuardrails,
      ObjectProvider<HookRegistration> hooks,
      ObjectProvider<AuditSink> auditSinks,
      ObjectProvider<ToolCallObserver> observers,
      VegadutaAgentProperties properties) {
    Governance.Builder builder =
        Governance.builder()
            .policy(policy)
            .guardrails(guardrailChain(properties, chainOverride, customGuardrails));
    builder.classificationSource(vegadutaClassifications);
    sources
        .orderedStream()
        .filter(s -> s != vegadutaClassifications)
        .forEach(builder::classificationSource);
    gate.ifAvailable(builder::gate);
    approvalStore.ifAvailable(builder::approvalStore);
    authorizer.ifAvailable(builder::approvalAuthorizer);
    budgets.ifAvailable(builder::budgets);
    journal.ifAvailable(builder::effectJournal);
    hooks.orderedStream().forEach(builder::hook);
    for (VegadutaAgentProperties.Webhook webhook : properties.getWebhooks()) {
      if (webhook.getUrl() == null) {
        continue;
      }
      builder.hook(
          new HookRegistration(
              webhook.getName(),
              webhook.getPoints(),
              webhook.getToolPattern(),
              new WebhookHook(
                  URI.create(webhook.getUrl()), webhook.getSecret(), webhook.getTimeout()),
              webhook.getFailMode()));
    }
    if (properties.getAudit().isLog()) {
      builder.auditSink(new LoggingAuditSink());
    }
    auditSinks.orderedStream().forEach(builder::auditSink);
    observers.orderedStream().forEach(builder::observer);
    return builder.build();
  }

  /**
   * A {@link GuardrailChain} bean replaces the whole chain; otherwise the built-in rules (when
   * enabled) are followed by every {@link ContentGuardrail} bean the host declares.
   */
  static GuardrailChain guardrailChain(
      VegadutaAgentProperties properties,
      ObjectProvider<GuardrailChain> override,
      ObjectProvider<ContentGuardrail> custom) {
    GuardrailChain replacement = override.getIfUnique();
    if (replacement != null) {
      return replacement;
    }
    List<ContentGuardrail> chain = new ArrayList<>();
    if (properties.getGuardrails().isBuiltIn()) {
      chain.addAll(GuardrailChain.builtIn().guardrails());
    }
    custom.orderedStream().filter(g -> !(g instanceof GuardrailChain)).forEach(chain::add);
    return new GuardrailChain(chain);
  }

  @Bean
  @ConditionalOnMissingBean
  public ApprovalGate vegadutaApprovalGate(Governance governance) {
    return governance.approvals();
  }

  @Bean
  @ConditionalOnMissingBean
  public RunContextResolver vegadutaRunContextResolver() {
    return RunContextResolver.defaults();
  }

  @Bean
  @ConditionalOnMissingBean
  public ToolGovernor vegadutaToolGovernor(Governance governance, RunContextResolver resolver) {
    return new ToolGovernor(governance, resolver);
  }

  @Bean
  @ConditionalOnMissingBean
  public GovernanceAdvisor vegadutaGovernanceAdvisor(ToolGovernor governor) {
    return new GovernanceAdvisor(governor);
  }

  /** Adds the advisor to every auto-configured {@code ChatClient.Builder}. */
  @Bean
  @ConditionalOnClass(ChatClientBuilderCustomizer.class)
  public ChatClientBuilderCustomizer vegadutaChatClientBuilderCustomizer(
      GovernanceAdvisor advisor) {
    return builder -> builder.defaultAdvisors(advisor);
  }

  @Bean
  public static GovernedToolBeanPostProcessor vegadutaGovernedToolBeanPostProcessor(
      ObjectProvider<StaticClassificationSource> classifications,
      ObjectProvider<ToolGovernor> governor) {
    return new GovernedToolBeanPostProcessor(classifications, governor);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(MeterRegistry.class)
  @ConditionalOnBean(MeterRegistry.class)
  @ConditionalOnProperty(
      prefix = "vegaduta.agent.telemetry",
      name = "micrometer",
      havingValue = "true",
      matchIfMissing = true)
  static class MicrometerConfiguration {
    @Bean
    public ToolCallObserver vegadutaMicrometerObserver(MeterRegistry registry) {
      return new MicrometerToolCallObserver(registry);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(OpenTelemetry.class)
  @ConditionalOnBean(OpenTelemetry.class)
  @ConditionalOnProperty(
      prefix = "vegaduta.agent.telemetry",
      name = "opentelemetry",
      havingValue = "true",
      matchIfMissing = true)
  static class OpenTelemetryConfiguration {
    @Bean
    public ToolCallObserver vegadutaOpenTelemetryObserver(OpenTelemetry openTelemetry) {
      return OpenTelemetryToolCallObserver.of(openTelemetry);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass({JdbcApprovalStore.class, DataSource.class})
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnProperty(prefix = "vegaduta.agent.store", name = "type", havingValue = "jdbc")
  static class JdbcStoreConfiguration {

    JdbcStoreConfiguration(DataSource dataSource, VegadutaAgentProperties properties) {
      if (properties.getStore().isInitializeSchema()) {
        VegadutaAgentSchema.initialize(dataSource);
      }
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalStore vegadutaJdbcApprovalStore(DataSource dataSource) {
      return new JdbcApprovalStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public EffectJournal vegadutaJdbcEffectJournal(
        DataSource dataSource, VegadutaAgentProperties properties) {
      return new JdbcEffectJournal(dataSource, properties.getJournal().getTtl());
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "vegaduta.agent.store",
        name = "jdbc-audit",
        havingValue = "true",
        matchIfMissing = true)
    public AuditSink vegadutaJdbcAuditSink(DataSource dataSource) {
      return new JdbcAuditSink(dataSource);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(RestController.class)
  @ConditionalOnProperty(
      prefix = "vegaduta.agent.approval.endpoint",
      name = "enabled",
      havingValue = "true")
  static class ApprovalEndpointConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ApprovalEndpoint vegadutaApprovalEndpoint(ApprovalGate approvals) {
      return new ApprovalEndpoint(approvals);
    }
  }
}
