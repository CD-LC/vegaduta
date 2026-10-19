package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Objects;

/** Verifies {@link Ed25519Signer} signatures with the public key only. */
@Experimental
public final class Ed25519Verifier implements Verifier {

  private final PublicKey publicKey;

  public Ed25519Verifier(PublicKey publicKey) {
    this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
  }

  @Override
  public String algorithm() {
    return Ed25519Signer.ALGORITHM;
  }

  @Override
  public boolean verify(byte[] data, byte[] signature) {
    try {
      Signature verifier = Signature.getInstance(Ed25519Signer.ALGORITHM);
      verifier.initVerify(publicKey);
      verifier.update(data);
      return verifier.verify(signature);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }
}
