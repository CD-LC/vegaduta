package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies receipts with HMAC-SHA256 over a shared secret. Simple to operate, but anyone
 * who can verify can also forge; use {@link Ed25519ReceiptSigner} when receipts leave the trust
 * boundary.
 */
@Experimental
public final class HmacSha256ReceiptSigner implements ReceiptSigner, ReceiptVerifier {

  private static final String ALGORITHM = "HmacSHA256";

  private final String keyId;
  private final byte[] key;

  /**
   * @param keyId name stored on every receipt this signer produces
   * @param key the secret; at least 16 bytes
   */
  public HmacSha256ReceiptSigner(String keyId, byte[] key) {
    this.keyId = Objects.requireNonNull(keyId, "keyId");
    Objects.requireNonNull(key, "key");
    if (key.length < 16) {
      throw new IllegalArgumentException("HMAC key must be at least 16 bytes");
    }
    this.key = key.clone();
  }

  @Override
  public String keyId() {
    return keyId;
  }

  @Override
  public String sign(byte[] canonical) {
    return Base64.getEncoder().encodeToString(mac(canonical));
  }

  @Override
  public boolean verify(byte[] canonical, String signature) {
    byte[] given;
    try {
      given = Base64.getDecoder().decode(signature);
    } catch (IllegalArgumentException e) {
      return false;
    }
    return MessageDigest.isEqual(mac(canonical), given);
  }

  private byte[] mac(byte[] data) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(ALGORITHM + " is mandatory in every JDK", e);
    }
  }
}
