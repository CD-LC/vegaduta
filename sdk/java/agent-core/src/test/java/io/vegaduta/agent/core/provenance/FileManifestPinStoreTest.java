package io.vegaduta.agent.core.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vegaduta.agent.api.tool.ToolDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileManifestPinStoreTest {

  private static final ToolManifest MANIFEST =
      ToolManifest.of(
          List.of(
              ToolDescriptor.of("search", "Search the web", "{\"type\":\"object\"}"),
              ToolDescriptor.of("read_file", "Read a file", "{\"type\":\"object\"}")));

  @TempDir Path dir;

  @Test
  void directoryStoreWritesOneLockfilePerKeyAndRoundTrips() {
    FileManifestPinStore store = new FileManifestPinStore(dir.resolve("pins"));

    assertThat(store.load("agent/one")).isEmpty();
    store.save("agent/one", MANIFEST);

    Path file = store.pathFor("agent/one");
    assertThat(file).isEqualTo(dir.resolve("pins").resolve("agent_one.lock"));
    assertThat(file).exists();
    assertThat(store.load("agent/one")).contains(MANIFEST);
    assertThat(store.load("other")).isEmpty();
    assertThat(store.remove("agent/one")).isTrue();
    assertThat(store.remove("agent/one")).isFalse();
    assertThat(store.load("agent/one")).isEmpty();
  }

  @Test
  void singleFileStoreIgnoresTheKey() throws IOException {
    Path lock = dir.resolve("vegaduta-tools.lock");
    FileManifestPinStore store = FileManifestPinStore.singleFile(lock);

    store.save("anything", MANIFEST);
    assertThat(Files.readString(lock, StandardCharsets.UTF_8)).isEqualTo(MANIFEST.toText());
    assertThat(store.load("something-else")).contains(MANIFEST);
    try (java.util.stream.Stream<Path> files = Files.list(dir)) {
      assertThat(files).as("no temp file left behind").hasSize(1);
    }
  }

  @Test
  void malformedLockfileFailsLoudlyInsteadOfLookingUnpinned() throws IOException {
    Path lock = dir.resolve("vegaduta-tools.lock");
    Files.writeString(lock, "garbage", StandardCharsets.UTF_8);
    FileManifestPinStore store = FileManifestPinStore.singleFile(lock);

    assertThatThrownBy(() -> store.load("k"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed pin");
  }
}
