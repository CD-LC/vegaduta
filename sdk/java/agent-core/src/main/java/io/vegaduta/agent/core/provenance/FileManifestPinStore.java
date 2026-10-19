package io.vegaduta.agent.core.provenance;

import io.vegaduta.agent.api.Experimental;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ManifestPinStore} that keeps lockfiles on disk, in the text form of {@link
 * ToolManifest#toText()}, so a pin can be committed to the repository and reviewed like any other
 * dependency lockfile.
 *
 * <p>Two layouts:
 *
 * <ul>
 *   <li>{@link #FileManifestPinStore(Path) directory}: one file per key, {@code <key>.lock}, with
 *       characters outside {@code [A-Za-z0-9._-]} in the key replaced by {@code _}
 *   <li>{@link #singleFile(Path)}: every key maps to the one given file, for the common case of one
 *       agent per repository with a {@code vegaduta-tools.lock} at its root
 * </ul>
 *
 * <p>Writes go to a temporary sibling and are moved into place atomically, so a reader never sees a
 * half-written lockfile. Reads of a malformed file fail with {@link IllegalArgumentException}
 * rather than returning empty: a corrupt pin must not silently turn into "pin on first use".
 */
@Experimental
public final class FileManifestPinStore implements ManifestPinStore {

  private final Path directory;
  private final @Nullable Path singleFile;

  /** One lockfile per key under {@code directory}; the directory is created on first save. */
  public FileManifestPinStore(Path directory) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.singleFile = null;
  }

  private FileManifestPinStore(Path directory, Path singleFile) {
    this.directory = directory;
    this.singleFile = singleFile;
  }

  /** A store where every key reads and writes {@code lockFile}. */
  public static FileManifestPinStore singleFile(Path lockFile) {
    Objects.requireNonNull(lockFile, "lockFile");
    Path parent = lockFile.toAbsolutePath().getParent();
    return new FileManifestPinStore(parent == null ? lockFile : parent, lockFile);
  }

  /** The file a key is stored in. */
  public Path pathFor(String key) {
    Objects.requireNonNull(key, "key");
    if (singleFile != null) {
      return singleFile;
    }
    return directory.resolve(sanitize(key) + ".lock");
  }

  @Override
  public Optional<ToolManifest> load(String key) {
    Path file = pathFor(key);
    try {
      return Optional.of(ToolManifest.parse(Files.readString(file, StandardCharsets.UTF_8)));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read pin " + file, e);
    } catch (IllegalArgumentException e) {
      String message = e.getMessage() == null ? "" : e.getMessage();
      if (message.length() > 160) {
        message = message.substring(0, 160) + "...";
      }
      throw new IllegalArgumentException("malformed pin " + file + ": " + message, e);
    }
  }

  @Override
  public Optional<String> version(String key) {
    try {
      Path file = pathFor(key);
      return Optional.of(Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  @Override
  public void save(String key, ToolManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    Path file = pathFor(key);
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path tmp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
      boolean moved = false;
      try {
        Files.writeString(tmp, manifest.toText(), StandardCharsets.UTF_8);
        try {
          Files.move(
              tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        moved = true;
      } finally {
        if (!moved) {
          Files.deleteIfExists(tmp);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write pin " + file, e);
    }
  }

  @Override
  public boolean remove(String key) {
    try {
      return Files.deleteIfExists(pathFor(key));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot remove pin for " + key, e);
    }
  }

  private static String sanitize(String key) {
    StringBuilder sb = new StringBuilder(key.length());
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '_'
              || c == '-';
      sb.append(ok ? c : '_');
    }
    String name = sb.toString();
    if (name.isEmpty() || name.equals(".") || name.equals("..")) {
      return "_" + name;
    }
    return name;
  }
}
