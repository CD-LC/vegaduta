package io.vegaduta.agent.api.mandate;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Carries a {@link Mandate} in {@link RunContext#attributes()} and verifies whole chains.
 *
 * <p>Only the leaf mandate travels with the context; an agent that needs to prove where its
 * authority came from keeps the ancestors and hands the full root-to-leaf list to {@link
 * #verifyChain}.
 */
@Experimental
public final class Mandates {

  /** The {@link RunContext} attribute that holds the encoded leaf mandate. */
  public static final String ATTRIBUTE = "vegaduta.mandate";

  private Mandates() {}

  /** A copy of {@code context} carrying {@code mandate} under {@link #ATTRIBUTE}. */
  public static RunContext attach(RunContext context, Mandate mandate) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(mandate, "mandate");
    return context.toBuilder().attribute(ATTRIBUTE, mandate.encode()).build();
  }

  /**
   * The mandate carried by {@code context}, if any.
   *
   * @throws IllegalArgumentException when the attribute is present but not a mandate encoding
   */
  public static Optional<Mandate> from(RunContext context) {
    Objects.requireNonNull(context, "context");
    String encoded = context.attributes().get(ATTRIBUTE);
    return encoded == null ? Optional.empty() : Optional.of(Mandate.decode(encoded));
  }

  /**
   * Re-verifies a chain of mandates ordered root to leaf: digests are recomputed from the canonical
   * fields and each parent's digest, parent links and depths must be contiguous, and every hop must
   * only attenuate the previous one (tools, risk, expiry) while keeping principal and maximum
   * depth.
   *
   * @return the leaf mandate, for convenience
   * @throws IllegalArgumentException naming the first violation found
   */
  public static Mandate verifyChain(List<Mandate> rootToLeaf) {
    Objects.requireNonNull(rootToLeaf, "rootToLeaf");
    if (rootToLeaf.isEmpty()) {
      throw new IllegalArgumentException("mandate chain is empty");
    }
    Mandate root = rootToLeaf.get(0);
    if (root.depth() != 0 || root.parentId() != null) {
      throw new IllegalArgumentException("chain does not start at a root mandate: " + root.id());
    }
    checkDigest(root, null);
    Mandate parent = root;
    for (int i = 1; i < rootToLeaf.size(); i++) {
      Mandate child = rootToLeaf.get(i);
      if (!parent.id().equals(child.parentId())) {
        throw new IllegalArgumentException(
            "mandate "
                + child.id()
                + " names parent "
                + child.parentId()
                + " but follows "
                + parent.id());
      }
      if (child.depth() != parent.depth() + 1) {
        throw new IllegalArgumentException(
            "mandate "
                + child.id()
                + " has depth "
                + child.depth()
                + " after depth "
                + parent.depth());
      }
      if (child.depth() > child.maxDepth()) {
        throw new IllegalArgumentException(
            "mandate " + child.id() + " exceeds maxDepth " + child.maxDepth());
      }
      if (child.maxDepth() != parent.maxDepth()) {
        throw new IllegalArgumentException("mandate " + child.id() + " changes maxDepth");
      }
      if (!Objects.equals(child.principal(), parent.principal())) {
        throw new IllegalArgumentException("mandate " + child.id() + " changes principal");
      }
      if (!child.issuer().equals(parent.subject())) {
        throw new IllegalArgumentException(
            "mandate " + child.id() + " was not issued by its parent's subject");
      }
      Mandate.checkAttenuation(parent, child.allowedTools(), child.maxRisk(), child.expiresAt());
      checkDigest(child, parent.digest());
      parent = child;
    }
    return parent;
  }

  private static void checkDigest(Mandate mandate, @Nullable String parentDigest) {
    String expected = mandate.expectedDigest(parentDigest);
    if (!expected.equals(mandate.digest())) {
      throw new IllegalArgumentException("mandate " + mandate.id() + " digest mismatch");
    }
  }
}
