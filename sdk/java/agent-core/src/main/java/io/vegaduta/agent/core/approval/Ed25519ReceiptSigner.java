package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * Signs receipts with Ed25519 (RFC 8032). The matching {@link Ed25519ReceiptVerifier} needs only
 * the public key, so auditors, insurers and regulators can verify receipts without being able to
 * forge them.
 */
@Experimental
public final class Ed25519ReceiptSigner implements ReceiptSigner {

  static final String ALGORITHM = "Ed25519";

  private final String keyId;
  private final PrivateKey privateKey;

  public Ed25519ReceiptSigner(String keyId, PrivateKey privateKey) {
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
  }

  @Override
  public String keyId() {
    return keyId;
  }

  @Override
  public String sign(byte[] canonical) {
    try {
      Signature signature = Signature.getInstance(ALGORITHM);
      signature.initSign(privateKey);
      signature.update(canonical);
      return Base64.getEncoder().encodeToString(signature.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 signing failed", e);
    }
  }
}
