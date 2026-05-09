package com.parkride.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Utility for loading RSA keys from PEM-formatted content.
 *
 * <p>This is a plain Java utility with no Spring dependency — intentional,
 * since {@code common-security} is a zero-framework library.
 * Callers are responsible for reading the {@code InputStream} from whichever
 * source they prefer (classpath, filesystem, vault, etc.).
 *
 * <p>Expected PEM formats:
 * <ul>
 *   <li>Private key: {@code -----BEGIN PRIVATE KEY-----} (PKCS#8 unencrypted)</li>
 *   <li>Public key:  {@code -----BEGIN PUBLIC KEY-----}  (X.509 SubjectPublicKeyInfo)</li>
 * </ul>
 *
 * <p>Usage in Spring {@code @Configuration}:
 * <pre>{@code
 * @Bean
 * public JwtUtil jwtUtil(
 *         @Value("classpath:keys/private.pem") Resource privateKeyRes,
 *         @Value("classpath:keys/public.pem")  Resource publicKeyRes) throws IOException {
 *     return new JwtUtil(
 *         RsaKeyUtil.loadPrivateKey(privateKeyRes.getInputStream()),
 *         RsaKeyUtil.loadPublicKey(publicKeyRes.getInputStream()));
 * }
 * }</pre>
 */
public final class RsaKeyUtil {

    private RsaKeyUtil() {}

    /**
     * Loads an RSA private key from a PKCS#8 PEM {@link InputStream}.
     *
     * @param stream the input stream containing the PEM-encoded private key.
     *               The stream is fully read and closed by this method.
     * @return parsed {@link RSAPrivateKey}
     * @throws IllegalStateException if the key cannot be parsed
     */
    public static RSAPrivateKey loadPrivateKey(InputStream stream) {
        try {
            String pem = readStream(stream);
            byte[] der = decodePem(pem, "PRIVATE KEY");
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to load RSA private key", e);
        }
    }

    /**
     * Loads an RSA public key from an X.509 SubjectPublicKeyInfo PEM {@link InputStream}.
     *
     * @param stream the input stream containing the PEM-encoded public key.
     *               The stream is fully read and closed by this method.
     * @return parsed {@link RSAPublicKey}
     * @throws IllegalStateException if the key cannot be parsed
     */
    public static RSAPublicKey loadPublicKey(InputStream stream) {
        try {
            String pem = readStream(stream);
            byte[] der = decodePem(pem, "PUBLIC KEY");
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to load RSA public key", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private static String readStream(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] decodePem(String pem, String label) {
        String stripped = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END "   + label + "-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(stripped);
    }
}
