package com.agenticai.core.crypto.kms;

import com.agenticai.core.crypto.KeyManagementProvider;
import com.agenticai.core.crypto.WrappedDataKey;
import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.AzureException;
import com.azure.core.http.HttpClient;
import com.azure.core.http.jdk.httpclient.JdkHttpClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.UnwrapResult;
import com.azure.security.keyvault.keys.cryptography.models.WrapResult;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Azure Key Vault {@link KeyManagementProvider} ({@code app.crypto.kms.provider=azure}). A fresh
 * AES-256 data key is generated locally and wrapped with {@code wrapKey} (default {@code
 * RSA-OAEP-256}, an RSA key in Key Vault or Managed HSM); {@code unwrapKey} reverses it.
 * Credentials come from {@code DefaultAzureCredential} (AKS workload identity, managed identity,
 * {@code az login}): no secret is stored. The HTTP stack is the JDK client (no Netty).
 *
 * <p>The provider id is {@code azure:<key URL without a version>}, so pinning a version in the
 * configured URL does not change it. A key URL without a version always wraps with the newest
 * version; a pinned one wraps with that version. Each wrapped key records the versioned key id
 * Azure reported and the wrap algorithm used; {@code unwrapKey} uses exactly those, so neither a
 * rotation of the vault key nor a changed {@code app.crypto.kms.azure.algorithm} strands old data.
 * A version of a different key is refused. The identity needs the {@code wrapKey} and {@code
 * unwrapKey} key permissions (RBAC role "Key Vault Crypto User" covers both).
 *
 * <p>One {@link CryptographyClient} per key identifier is built on first use and kept, sharing one
 * credential (so its token cache survives) and one HTTP client.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.crypto.kms.provider", havingValue = "azure")
public class AzureKeyVaultKeyManagementProvider implements KeyManagementProvider {

    static final String ID_PREFIX = "azure:";
    private static final int DATA_KEY_LENGTH_BYTES = 32;

    private final String keyUrl;
    private final KeyWrapAlgorithm algorithm;
    private final Function<String, CryptographyClient> clients;
    private final ConcurrentHashMap<String, CryptographyClient> clientCache =
            new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public AzureKeyVaultKeyManagementProvider(
            @Value("${app.crypto.kms.azure.key-id:}") String keyUrl,
            @Value("${app.crypto.kms.azure.algorithm:RSA-OAEP-256}") String algorithm) {
        this(keyUrl, algorithm, new SharedClientFactory());
    }

    AzureKeyVaultKeyManagementProvider(
            String keyUrl, String algorithm, Function<String, CryptographyClient> clients) {
        String url = KmsIds.requireKey("app.crypto.kms.azure.key-id", keyUrl);
        this.keyUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        KmsIds.checkProviderIdLength(ID_PREFIX + keyBase());
        this.algorithm = parseAlgorithm("app.crypto.kms.azure.algorithm", algorithm);
        this.clients = clients;
    }

    /** Builds clients that share one credential chain (and token cache) and one HTTP client. */
    private static final class SharedClientFactory implements Function<String, CryptographyClient> {
        private final TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        private final HttpClient httpClient = new JdkHttpClientBuilder().build();

        @Override
        public CryptographyClient apply(String keyIdentifier) {
            return new CryptographyClientBuilder()
                    .keyIdentifier(keyIdentifier)
                    .credential(credential)
                    .httpClient(httpClient)
                    .buildClient();
        }
    }

    private CryptographyClient client(String keyIdentifier) {
        return clientCache.computeIfAbsent(keyIdentifier, clients);
    }

    private static KeyWrapAlgorithm parseAlgorithm(String what, String value) {
        String wanted = value == null ? "" : value.trim();
        for (KeyWrapAlgorithm known : KeyWrapAlgorithm.values()) {
            if (known.toString().equalsIgnoreCase(wanted)) {
                return known;
            }
        }
        throw new IllegalStateException(
                what + "='" + value + "' is not a known Key Vault wrap algorithm");
    }

    @Override
    public WrappedDataKey generateDataKey() {
        byte[] plaintext = new byte[DATA_KEY_LENGTH_BYTES];
        secureRandom.nextBytes(plaintext);
        try {
            WrapResult r = client(keyUrl).wrapKey(algorithm, plaintext);
            String used = r.getKeyId() == null ? keyUrl : r.getKeyId();
            return new WrappedDataKey(
                    plaintext, pack(used, algorithm, r.getEncryptedKey()), providerId());
        } catch (AzureException e) {
            Arrays.fill(plaintext, (byte) 0);
            throw new IllegalStateException(
                    "Azure Key Vault wrapKey failed for '" + keyUrl + "': " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] unwrapDataKey(byte[] wrappedKey) {
        ByteBuffer in = ByteBuffer.wrap(wrappedKey);
        String used = readString(in);
        KeyWrapAlgorithm recorded = parseRecordedAlgorithm(readString(in));
        byte[] wrapped = new byte[in.remaining()];
        in.get(wrapped);
        if (!used.equals(keyUrl) && !used.startsWith(keyBase() + "/")) {
            throw new IllegalStateException(
                    "Wrapped key was produced by '"
                            + used
                            + "', outside the configured key '"
                            + keyBase()
                            + "'");
        }
        try {
            UnwrapResult r = client(used).unwrapKey(recorded, wrapped);
            return r.getKey();
        } catch (AzureException e) {
            throw new IllegalStateException(
                    "Azure Key Vault unwrapKey failed for '" + used + "': " + e.getMessage(), e);
        }
    }

    private static KeyWrapAlgorithm parseRecordedAlgorithm(String recorded) {
        try {
            return parseAlgorithm("recorded wrap algorithm", recorded);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "Malformed Azure Key Vault wrapped key: " + e.getMessage());
        }
    }

    private static String readString(ByteBuffer in) {
        if (in.remaining() < 2) {
            throw new IllegalStateException("Malformed Azure Key Vault wrapped key");
        }
        int len = in.getShort() & 0xFFFF;
        if (in.remaining() < len) {
            throw new IllegalStateException("Malformed Azure Key Vault wrapped key");
        }
        byte[] bytes = new byte[len];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The configured key URL without a trailing version segment: {@code .../keys/<name>}. */
    private String keyBase() {
        int keys = keyUrl.indexOf("/keys/");
        if (keys < 0) {
            return keyUrl;
        }
        int nameEnd = keyUrl.indexOf('/', keys + "/keys/".length());
        return nameEnd < 0 ? keyUrl : keyUrl.substring(0, nameEnd);
    }

    /** Layout: [u16 key id length][key id][u16 algorithm length][algorithm][wrapped key]. */
    private static byte[] pack(String keyId, KeyWrapAlgorithm algorithm, byte[] wrapped) {
        byte[] id = keyId.getBytes(StandardCharsets.UTF_8);
        byte[] alg = algorithm.toString().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + id.length + alg.length + wrapped.length)
                .putShort((short) id.length)
                .put(id)
                .putShort((short) alg.length)
                .put(alg)
                .put(wrapped)
                .array();
    }

    @Override
    public String providerId() {
        return ID_PREFIX + keyBase();
    }
}
