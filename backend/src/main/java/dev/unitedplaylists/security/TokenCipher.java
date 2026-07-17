package dev.unitedplaylists.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts OAuth tokens at rest with AES-256-GCM.
 *
 * <p>The database is a plain file in the user's profile directory, so a refresh
 * token stored in the clear is a refresh token available to anything that can read
 * that file, and a refresh token is long-lived access to the user's music account.
 *
 * <p>GCM rather than CBC because it authenticates as well as encrypts: tampering
 * with the ciphertext produces an error rather than a silently corrupted token. A
 * fresh random IV is generated per encryption and prefixed to the ciphertext,
 * since IV reuse under GCM is catastrophic rather than merely untidy.
 */
public class TokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param base64Key 32 raw bytes, base64-encoded
     * @throws IllegalArgumentException if the key is missing or the wrong length
     */
    public TokenCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException(
                    "No token encryption key configured; refusing to store tokens in the clear");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Token encryption key is not valid base64", e);
        }
        if (raw.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "Token encryption key must be %d bytes, got %d"
                            .formatted(KEY_LENGTH_BYTES, raw.length));
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Generates a fresh key, base64-encoded. Used on first run. */
    public static String generateKey() {
        byte[] raw = new byte[KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    /** @return base64 of {@code iv || ciphertext || tag} */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt token", e);
        }
    }

    /**
     * @throws TokenDecryptionException if the value was tampered with, or was
     *     written under a different key (e.g. the key file was regenerated)
     */
    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= IV_LENGTH_BYTES) {
                throw new TokenDecryptionException("Stored token is too short to be valid");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new TokenDecryptionException("Stored token is not valid base64", e);
        } catch (GeneralSecurityException e) {
            throw new TokenDecryptionException(
                    "Stored token failed authentication; it may be corrupt or encrypted "
                            + "under a different key", e);
        }
    }

    /** Thrown when a stored token cannot be recovered; the user must reconnect. */
    public static class TokenDecryptionException extends RuntimeException {
        public TokenDecryptionException(String message) {
            super(message);
        }

        public TokenDecryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
