package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;

/**
 * Signs the canonical bytes of an {@link ApprovalReceipt}. Implementations are {@link
 * HmacSha256ReceiptSigner} (shared secret) and {@link Ed25519ReceiptSigner} (asymmetric, so the
 * verifier never holds signing material).
 */
@Experimental
public interface ReceiptSigner {

  /** Identifies the key, so a verifier can pick the matching one; stored on the receipt. */
  String keyId();

  /**
   * @param canonical the receipt's canonical UTF-8 bytes
   * @return the signature, base64 encoded
   */
  String sign(byte[] canonical);
}
