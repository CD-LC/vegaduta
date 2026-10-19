package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;

/**
 * Checks a signature produced by a {@link ReceiptSigner}. {@link HmacSha256ReceiptSigner} is its
 * own verifier; {@link Ed25519ReceiptVerifier} needs only the public key.
 */
@Experimental
public interface ReceiptVerifier {

  /** The key id this verifier checks; must equal the receipt's {@code signerKeyId}. */
  String keyId();

  /**
   * @param canonical the receipt's canonical UTF-8 bytes
   * @param signature the base64 signature from the receipt
   * @return whether the signature is valid for these bytes; never throws on a malformed signature
   */
  boolean verify(byte[] canonical, String signature);
}
