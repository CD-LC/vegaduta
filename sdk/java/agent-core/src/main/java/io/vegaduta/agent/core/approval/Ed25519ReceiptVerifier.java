package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/** Verifies receipts signed by an {@link Ed25519ReceiptSigner}, using only the public key. */
@Experimental
public final class Ed25519ReceiptVerifier implements ReceiptVerifier {

  private final String keyId;
  private final PublicKey publicKey;

  public Ed25519ReceiptVerifier(String keyId, PublicKey publicKey) {
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
  }

  @Override
  public String keyId() {
    return keyId;
  }

  @Override
  public boolean verify(byte[] canonical, String signature) {
    try {
      Signature verifier = Signature.getInstance(Ed25519ReceiptSigner.ALGORITHM);
      verifier.initVerify(publicKey);
      verifier.update(canonical);
      return verifier.verify(Base64.getDecoder().decode(signature));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }
}
