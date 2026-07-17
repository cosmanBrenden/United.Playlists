package dev.unitedplaylists.provider.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** PKCE verifier/challenge generation, per RFC 7636. */
public final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Pkce() {
    }

    /**
     * Generates a code verifier.
     *
     * <p>32 random bytes becomes 43 base64url characters, the minimum RFC 7636
     * allows and comfortably beyond guessing.
     */
    public static String generateVerifier() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return URL_ENCODER.encodeToString(raw);
    }

    /** The S256 challenge for a verifier. */
    public static String challengeFor(String verifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** An opaque random value for the {@code state} parameter. */
    public static String generateState() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        return URL_ENCODER.encodeToString(raw);
    }
}
