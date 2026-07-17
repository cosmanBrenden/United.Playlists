package dev.unitedplaylists.provider;

import dev.unitedplaylists.domain.ProviderId;
import java.time.Instant;
import java.util.Objects;

/**
 * A user's live credentials for one service, handed to a provider per call.
 *
 * <p>Providers receive credentials rather than loading them, so they stay free of
 * persistence concerns and are trivially testable. Refresh is handled upstream by
 * {@code ConnectionService}, so a provider can assume the token is valid.
 *
 * @param provider     which service
 * @param accessToken  bearer token, already refreshed if it was near expiry
 * @param expiresAt    when the access token dies, or null if the service does not say
 * @param userMarket   ISO 3166-1 country for catalogue/availability, or null
 */
public record ProviderCredentials(
        ProviderId provider, String accessToken, Instant expiresAt, String userMarket) {

    public ProviderCredentials {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(accessToken, "accessToken");
        if (accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be blank");
        }
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * A placeholder for providers that authenticate nothing.
     *
     * <p>The scraper-backed providers reach their service anonymously, so they have no
     * token — but {@code search} and friends take a {@code ProviderCredentials}, and
     * threading a nullable through every call site would be worse than one obviously
     * inert value. These providers ignore the token entirely.
     */
    public static ProviderCredentials anonymous(ProviderId provider) {
        return new ProviderCredentials(provider, "anonymous", null, null);
    }
}
