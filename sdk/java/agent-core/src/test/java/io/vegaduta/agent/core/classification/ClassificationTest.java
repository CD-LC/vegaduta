package io.vegaduta.agent.core.classification;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.api.annotation.Governed;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.risk.ToolClassificationDecision;
import io.vegaduta.agent.core.GovernancePolicy;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClassificationTest {

  private static ToolClassification tool(String name) {
    return ToolClassification.builder(name).build();
  }

  @Test
  void trifectaHoldsOnlyWhenAllThreePresent() {
    LethalTrifectaDetector detector = new LethalTrifectaDetector();
    ToolClassification reader =
        ToolClassification.builder("read_db").readsPrivateData(true).build();
    ToolClassification web =
        ToolClassification.builder("fetch_page").consumesUntrustedInput(true).build();
    ToolClassification mailer =
        ToolClassification.builder("send_mail").exfiltrationSink(true).build();

    assertThat(detector.analyse(List.of(reader, web)).holds()).isFalse();
    assertThat(detector.analyse(List.of(reader, mailer)).holds()).isFalse();
    assertThat(detector.analyse(List.of(web, mailer)).holds()).isFalse();
    assertThat(detector.analyse(List.of(reader, web, mailer)).holds()).isTrue();
  }

  @Test
  void trifectaReportGroupsToolsByCapability() {
    ToolClassification reader =
        ToolClassification.builder("read_db").readsPrivateData(true).build();
    ToolClassification web =
        ToolClassification.builder("fetch_page").consumesUntrustedInput(true).build();
    ToolClassification mailer =
        ToolClassification.builder("send_mail").exfiltrationSink(true).build();
    ToolClassification harmless = tool("add_numbers");

    LethalTrifectaDetector.Report report =
        new LethalTrifectaDetector().analyse(List.of(reader, web, mailer, harmless));

    assertThat(report.privateReaders()).containsExactly("read_db");
    assertThat(report.untrustedConsumers()).containsExactly("fetch_page");
    assertThat(report.sinks()).containsExactly("send_mail");
  }

  @Test
  void trifectaIgnoresUntaggedToolsAndEmptyInput() {
    LethalTrifectaDetector detector = new LethalTrifectaDetector();
    assertThat(detector.analyse(List.of(tool("add_numbers"))).holds()).isFalse();
    assertThat(detector.analyse(List.of()).holds()).isFalse();
  }

  @Test
  void resolverReturnsSourceClassification() {
    StaticClassificationSource source = new StaticClassificationSource();
    source.register(tool("known"));
    ClassificationResolver resolver =
        new ClassificationResolver(source, GovernancePolicy.defaults());

    ToolClassificationDecision decision = resolver.resolve("known");
    assertThat(decision).isInstanceOf(ToolClassificationDecision.Classified.class);
    assertThat(resolver.isClassified("known")).isTrue();
  }

  @Test
  void resolverBlocksUnclassifiedAndAppliesSafeDefault() {
    ClassificationResolver resolver =
        new ClassificationResolver(new StaticClassificationSource(), GovernancePolicy.defaults());

    ToolClassificationDecision decision = resolver.resolve("mystery");
    assertThat(decision).isInstanceOf(ToolClassificationDecision.Blocked.class);
    assertThat(resolver.isClassified("mystery")).isFalse();

    // WARN-mode default errs on the safe side: side-effecting, not replay-safe.
    ToolClassification effective = resolver.effective("mystery");
    assertThat(effective.risk()).isEqualTo(RiskLabel.MEDIUM);
    assertThat(effective.sideEffecting()).isTrue();
    assertThat(effective.idempotent()).isFalse();
  }

  @Test
  void overrideBeatsSourceAndSharedResolversSeeIt() {
    StaticClassificationSource source = new StaticClassificationSource();
    source.register(tool("t"));
    GovernancePolicy live = GovernancePolicy.defaults();
    GovernancePolicy shadow = GovernancePolicy.builder().defaultRisk(RiskLabel.HIGH).build();
    ClassificationResolver resolver = new ClassificationResolver(source, live);
    ClassificationResolver sharing = resolver.sharing(shadow);

    ToolClassification forced = ToolClassification.builder("t").risk(RiskLabel.CRITICAL).build();
    resolver.override(forced);

    // A lethal-trifecta override applied on the live policy is visible to the shadow one.
    assertThat(((ToolClassificationDecision.Classified) sharing.resolve("t")).classification())
        .isEqualTo(forced);
    assertThat(sharing.policy()).isEqualTo(shadow);

    resolver.clearOverride("t");
    assertThat(((ToolClassificationDecision.Classified) sharing.resolve("t")).classification())
        .isEqualTo(tool("t"));
  }

  @Test
  void compositeSourceFirstMatchWins() {
    StaticClassificationSource empty = new StaticClassificationSource();
    StaticClassificationSource first = new StaticClassificationSource();
    first.register(tool("a"));
    StaticClassificationSource second = new StaticClassificationSource();
    ToolClassification different = ToolClassification.builder("a").risk(RiskLabel.LOW).build();
    second.register(different);

    CompositeClassificationSource composite =
        CompositeClassificationSource.of(empty, first, second);

    assertThat(composite.classify("a"))
        .hasValueSatisfying(c -> assertThat(c.risk()).isEqualTo(RiskLabel.MEDIUM));
    assertThat(composite.classify("absent")).isEmpty();
  }

  @Test
  void staticSourceRegistersRemovesAndCounts() {
    StaticClassificationSource source = new StaticClassificationSource(List.of(tool("x")));
    assertThat(source.size()).isEqualTo(1);
    assertThat(source.classify("x")).isPresent();

    source.register(tool("y")).remove("x");
    assertThat(source.classify("x")).isEmpty();
    assertThat(source.classify("y")).isPresent();
    assertThat(source.size()).isEqualTo(1);
  }

  @Governed(risk = RiskLabel.HIGH, readsPrivateData = true, requiresApproval = true)
  static class AnnotatedTools {
    @Governed(
        risk = RiskLabel.LOW,
        idempotent = true,
        sideEffecting = false,
        untrustedInput = true,
        exfiltrationSink = true,
        risks = {AsiRisk.ASI02})
    void methodLevel() {}

    void classLevel() {}
  }

  static class PlainTools {
    void nothing() {}
  }

  @Test
  void governedAnnotationOnMethodWinsOverClass() throws Exception {
    Method m = AnnotatedTools.class.getDeclaredMethod("methodLevel");
    Optional<ToolClassification> classified = GovernedAnnotations.classify(m, "tool");

    assertThat(classified).isPresent();
    ToolClassification c = classified.get();
    assertThat(c.toolName()).isEqualTo("tool");
    assertThat(c.risk()).isEqualTo(RiskLabel.LOW);
    assertThat(c.sideEffecting()).isFalse();
    assertThat(c.idempotent()).isTrue();
    assertThat(c.readsPrivateData()).isFalse();
    assertThat(c.consumesUntrustedInput()).isTrue();
    assertThat(c.exfiltrationSink()).isTrue();
    assertThat(c.requiresApproval()).isFalse();
    assertThat(c.risks()).containsExactly(AsiRisk.ASI02);
  }

  @Test
  void governedAnnotationFallsBackToClass() throws Exception {
    Method m = AnnotatedTools.class.getDeclaredMethod("classLevel");
    Optional<ToolClassification> classified = GovernedAnnotations.classify(m, "tool");

    assertThat(classified).isPresent();
    assertThat(classified.get().risk()).isEqualTo(RiskLabel.HIGH);
    assertThat(classified.get().readsPrivateData()).isTrue();
    assertThat(classified.get().requiresApproval()).isTrue();
  }

  @Test
  void governedAnnotationAbsentReturnsEmptyAndNull() throws Exception {
    Method m = PlainTools.class.getDeclaredMethod("nothing");
    assertThat(GovernedAnnotations.classify(m, "tool")).isEmpty();
    assertThat(GovernedAnnotations.find(m)).isNull();
  }
}
