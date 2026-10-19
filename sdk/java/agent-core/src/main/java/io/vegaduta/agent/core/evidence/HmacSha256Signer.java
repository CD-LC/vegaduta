package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 over the chain hash with a shared secret. Simple and fast; anyone holding the key can
 * both sign and verify, so it proves integrity against outsiders, not against the key holder. Use
 * {@link Ed25519Signer} when the verifier must not be able to forge entries.
 */
@Experimental
public final class HmacSha256Signer implements Signer, Verifier {

  public static final String ALGORITHM = "HmacSHA256";

  private final byte[] key;

  public HmacSha256Signer(byte[] key) {
    Objects.requireNonNull(key, "key");
    if (key.length < 16) {
      throw new IllegalArgumentException("HMAC key must be at least 16 bytes");
    }
    this.key = key.clone();
  }

  @Override
  public String algorithm() {
    return ALGORITHM;
  }

  @Override
  public byte[] sign(byte[] data) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      return mac.doFinal(data);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  @Override
  public boolean verify(byte[] data, byte[] signature) {
    return MessageDigest.isEqual(sign(data), signature);
  }

  @Override
  public Verifier verifier() {
    return this;
  }
}
