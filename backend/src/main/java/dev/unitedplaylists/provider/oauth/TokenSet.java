package dev.unitedplaylists.provider.oauth;

import java.time.Instant;
import java.util.Objects;

/**
 * Tokens as returned by a service's token endpoint.
 *
 * @param accessToken  short-lived bearer token
 * @param refreshToken long-lived token, or null when the service does not issue one
 *                     or (as Spotify does on refresh) declines to rotate it
 * @param expiresAt    absolute expiry, computed from the service's relative
 *                     {@code expires_in} at the moment of the response
 * @param scopes       space-separated granted scopes, or null
 */
public record TokenSet(String accessToken, String refreshToken, Instant expiresAt, String scopes) {

    public TokenSet {
        Objects.requireNonNull(accessToken, "accessToken");
        if (accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be blank");
        }
    }
}
