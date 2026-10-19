package io.vegaduta.agent.core.evidence;

import io.vegaduta.agent.api.Experimental;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Objects;

/**
 * Ed25519 signatures (JDK 15+, no extra dependency). The private key stays with the writer; a
 * regulator or auditor verifies with the public key alone, so they cannot forge entries.
 */
@Experimental
public final class Ed25519Signer implements Signer {

  public static final String ALGORITHM = "Ed25519";

  private final PrivateKey privateKey;
  private final PublicKey publicKey;

  public Ed25519Signer(KeyPair keyPair) {
    this(keyPair.getPrivate(), keyPair.getPublic());
  }

  public Ed25519Signer(PrivateKey privateKey, PublicKey publicKey) {
    this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
    this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
  }

  /** A signer over a fresh random key pair; keep {@link #publicKey()} for verification. */
  public static Ed25519Signer generate() {
    try {
      return new Ed25519Signer(KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 unavailable", e);
    }
  }

  public PublicKey publicKey() {
    return publicKey;
  }

  @Override
  public String algorithm() {
    return ALGORITHM;
  }

  @Override
  public byte[] sign(byte[] data) {
    try {
      Signature signature = Signature.getInstance(ALGORITHM);
      signature.initSign(privateKey);
      signature.update(data);
      return signature.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 signing failed", e);
    }
  }

  @Override
  public Verifier verifier() {
    return new Ed25519Verifier(publicKey);
  }
}
