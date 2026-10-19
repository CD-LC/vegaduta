package io.vegaduta.agent.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import io.vegaduta.agent.scanner.fixtures.BillingTools;
import io.vegaduta.agent.scanner.fixtures.LegacyTools;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassFinderTest {

  private static final String FIXTURES = "io.vegaduta.agent.scanner.fixtures";

  @Test
  void enumeratesPackageFromDirectoryOnClasspath() {
    List<Class<?>> classes = new ClassFinder().inPackage(FIXTURES);

    assertThat(classes)
        .extracting(Class::getSimpleName)
        .contains("BillingTools", "LegacyTools", "MailTools", "WebTools", "Tool");
    assertThat(classes).extracting(Class::getName).allMatch(n -> n.startsWith(FIXTURES + "."));
  }

  @Test
  void enumeratesPackageFromJarOnClasspath() {
    // agent-api is a jar on the test classpath.
    List<Class<?>> classes = new ClassFinder().inPackage("io.vegaduta.agent.api.risk");

    assertThat(classes)
        .extracting(Class::getSimpleName)
        .contains("AsiRisk", "RiskLabel", "ToolClassification");
  }

  @Test
  void scansExplicitClassDirectory() throws Exception {
    Path testClasses =
        Path.of(BillingTools.class.getProtectionDomain().getCodeSource().getLocation().toURI());

    List<Class<?>> classes = new ClassFinder().inPath(testClasses);

    assertThat(classes).extracting(Class::getName).contains(BillingTools.class.getName());
  }

  @Test
  void scansExplicitJarThroughChildLoader(@TempDir Path dir) throws Exception {
    Path jar = dir.resolve("tools.jar");
    writeJar(jar, LegacyTools.class, BillingTools.class);
    ClassFinder finder = new ClassFinder(ClassFinder.class.getClassLoader()).withPath(jar);

    List<Class<?>> classes = finder.inPath(jar);

    assertThat(classes)
        .extracting(Class::getName)
        .containsExactlyInAnyOrder(LegacyTools.class.getName(), BillingTools.class.getName());
    // Loaded, not initialised, and reflection still works.
    assertThat(new ToolMethodFinder().find(classes)).hasSize(4);
  }

  @Test
  void byNameSkipsUnknownClasses() {
    List<Class<?>> classes =
        new ClassFinder().byName(List.of(BillingTools.class.getName(), "com.acme.Missing"));

    assertThat(classes).containsExactly(BillingTools.class);
  }

  @Test
  void inPathRejectsMissingFile(@TempDir Path dir) {
    Path missing = dir.resolve("nope.jar");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ClassFinder().withPath(missing))
        .isInstanceOf(IOException.class);
  }

  private static void writeJar(Path jar, Class<?>... classes) throws IOException {
    try (OutputStream file = Files.newOutputStream(jar);
        JarOutputStream out = new JarOutputStream(file)) {
      for (Class<?> type : classes) {
        String entry = type.getName().replace('.', '/') + ".class";
        URL resource = type.getClassLoader().getResource(entry);
        assertThat(resource).isNotNull();
        out.putNextEntry(new JarEntry(entry));
        try (InputStream in = resource.openStream()) {
          in.transferTo(out);
        }
        out.closeEntry();
      }
    }
  }

  @Test
  void springBootRepackagedJarLayoutIsScanned(@TempDir Path dir) throws Exception {
    Path jar = dir.resolve("app.jar");
    try (OutputStream file = Files.newOutputStream(jar);
        JarOutputStream out = new JarOutputStream(file)) {
      for (Class<?> type : new Class<?>[] {LegacyTools.class, BillingTools.class}) {
        String entry = "BOOT-INF/classes/" + type.getName().replace('.', '/') + ".class";
        URL resource =
            type.getClassLoader().getResource(type.getName().replace('.', '/') + ".class");
        assertThat(resource).isNotNull();
        out.putNextEntry(new JarEntry(entry));
        try (InputStream in = resource.openStream()) {
          in.transferTo(out);
        }
        out.closeEntry();
      }
    }
    ClassFinder finder = new ClassFinder(ClassFinder.class.getClassLoader()).withPath(jar);

    List<Class<?>> classes = finder.inPath(jar);

    assertThat(classes)
        .extracting(Class::getName)
        .containsExactlyInAnyOrder(LegacyTools.class.getName(), BillingTools.class.getName());
  }
}
