package io.vegaduta.agent.scanner;

import io.vegaduta.agent.api.Experimental;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enumerates classes to scan: under a package prefix on a class loader (exploded directories and
 * plain jars; nested jars such as Spring Boot's {@code BOOT-INF/lib} are skipped), from an explicit
 * jar or class directory, or by fully qualified name.
 *
 * <p>Classes are loaded with {@code Class.forName(name, false, loader)}, so no static initialiser
 * runs. Classes that fail to load or link (missing optional dependencies, newer bytecode) are
 * skipped and logged at DEBUG.
 */
@Experimental
public final class ClassFinder {

  private static final Logger log = LoggerFactory.getLogger(ClassFinder.class);
  private static final String CLASS_SUFFIX = ".class";

  private final ClassLoader loader;

  /** Uses the context class loader, falling back to this class's loader. */
  public ClassFinder() {
    this(defaultLoader());
  }

  public ClassFinder(ClassLoader loader) {
    this.loader = loader;
  }

  /**
   * A finder whose loader sees {@code path} (a jar or a directory of {@code .class} files) on top
   * of this finder's loader. The returned loader is left open because reflection on the classes it
   * loaded may still need to resolve types; a scan is a short-lived process.
   */
  public ClassFinder withPath(Path path) throws IOException {
    if (!Files.exists(path)) {
      throw new IOException("Not found: " + path);
    }
    URL url = path.toUri().toURL();
    @SuppressWarnings("resource")
    URLClassLoader child = new URLClassLoader(new URL[] {url}, loader);
    return new ClassFinder(child);
  }

  /** Every loadable class whose name starts with {@code packagePrefix}. */
  public List<Class<?>> inPackage(String packagePrefix) {
    String prefix = packagePrefix.endsWith(".") ? packagePrefix : packagePrefix + ".";
    String resourcePath = prefix.substring(0, prefix.length() - 1).replace('.', '/');
    Set<String> names = new LinkedHashSet<>();
    try {
      Enumeration<URL> roots = loader.getResources(resourcePath);
      while (roots.hasMoreElements()) {
        URL root = roots.nextElement();
        collectNames(root, resourcePath, names);
      }
    } catch (IOException e) {
      log.debug("Cannot list resources for {}: {}", resourcePath, e.toString());
    }
    return load(names);
  }

  /** Every loadable class in {@code path}, a jar or a directory of {@code .class} files. */
  public List<Class<?>> inPath(Path path) throws IOException {
    Set<String> names = new LinkedHashSet<>();
    if (Files.isDirectory(path)) {
      collectFromDirectory(path, "", names);
    } else if (Files.isRegularFile(path)) {
      try (JarFile jar = new JarFile(path.toFile())) {
        collectFromJar(jar, "", names);
      }
    } else {
      throw new IOException("Not a jar or directory: " + path);
    }
    return load(names);
  }

  /** The named classes, skipping those that cannot be loaded. */
  public List<Class<?>> byName(Collection<String> names) {
    return load(new LinkedHashSet<>(names));
  }

  private List<Class<?>> load(Set<String> names) {
    List<Class<?>> classes = new ArrayList<>(names.size());
    for (String name : names) {
      Class<?> type = tryLoad(name);
      if (type != null) {
        classes.add(type);
      }
    }
    return classes;
  }

  private @Nullable Class<?> tryLoad(String name) {
    try {
      return Class.forName(name, false, loader);
    } catch (Throwable t) {
      log.debug("Skipping {}: {}", name, t.toString());
      return null;
    }
  }

  private void collectNames(URL root, String resourcePath, Set<String> names) {
    String protocol = root.getProtocol();
    try {
      if ("file".equals(protocol)) {
        Path dir = Path.of(root.toURI());
        collectFromDirectory(dir, resourcePath.replace('/', '.') + ".", names);
      } else if ("jar".equals(protocol)) {
        String spec = root.toString();
        if (spec.indexOf("!/") != spec.lastIndexOf("!/") || spec.startsWith("jar:nested:")) {
          log.debug("Skipping nested jar {}", spec);
          return;
        }
        JarURLConnection connection = (JarURLConnection) root.openConnection();
        connection.setUseCaches(false);
        try (JarFile jar = connection.getJarFile()) {
          collectFromJar(jar, resourcePath + "/", names);
        }
      } else {
        log.debug("Skipping unsupported resource {}", root);
      }
    } catch (Exception e) {
      log.debug("Skipping {}: {}", root, e.toString());
    }
  }

  private static void collectFromDirectory(Path dir, String packagePrefix, Set<String> names)
      throws IOException {
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.filter(p -> p.getFileName().toString().endsWith(CLASS_SUFFIX))
          .forEach(
              p -> {
                String relative =
                    dir.relativize(p).toString().replace(java.io.File.separatorChar, '.');
                addName(packagePrefix + relative, names);
              });
    }
  }

  private static void collectFromJar(JarFile jar, String entryPrefix, Set<String> names) {
    Enumeration<JarEntry> entries = jar.entries();
    while (entries.hasMoreElements()) {
      JarEntry entry = entries.nextElement();
      String name = entry.getName();
      if (entry.isDirectory() || !name.startsWith(entryPrefix) || !name.endsWith(CLASS_SUFFIX)) {
        continue;
      }
      if (name.startsWith("META-INF/")) {
        continue;
      }
      // Spring Boot and WAR layouts nest the application classes under a prefix.
      for (String nested : NESTED_CLASS_ROOTS) {
        if (name.startsWith(nested)) {
          name = name.substring(nested.length());
          break;
        }
      }
      addName(name.replace('/', '.'), names);
    }
  }

  private static final String[] NESTED_CLASS_ROOTS = {"BOOT-INF/classes/", "WEB-INF/classes/"};

  private static void addName(String fileName, Set<String> names) {
    String name = fileName.substring(0, fileName.length() - CLASS_SUFFIX.length());
    if (name.endsWith("package-info") || name.endsWith("module-info")) {
      return;
    }
    names.add(name);
  }

  private static ClassLoader defaultLoader() {
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    return context != null ? context : ClassFinder.class.getClassLoader();
  }
}
