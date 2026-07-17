package dev.unitedplaylists.provider.spotify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spotify integration settings.
 *
 * <p>Base URLs are configurable so tests can point them at a local WireMock; there
 * is no other reason to change them.
 *
 * @param clientId    Spotify application client id. Public by design: this is a
 *                    PKCE client, so there is no secret to protect.
 * @param apiBaseUrl  Web API root
 * @param authBaseUrl accounts service root, for authorize and token
 * @param enabled     false leaves the provider registered but unavailable, which
 *                    is what happens when the user has not supplied a client id
 */
@ConfigurationProperties(prefix = "unitedplaylists.spotify")
public record SpotifyProperties(
        String clientId, String apiBaseUrl, String authBaseUrl, boolean enabled) {

    public SpotifyProperties {
        apiBaseUrl = apiBaseUrl == null ? "https://api.spotify.com" : stripTrailingSlash(apiBaseUrl);
        authBaseUrl = authBaseUrl == null
                ? "https://accounts.spotify.com"
                : stripTrailingSlash(authBaseUrl);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Scopes needed to read the user's library and to play through the Web Playback
     * SDK. All read-only: the app never writes back.
     *
     * <p>{@code user-read-email} and {@code user-read-private} are here because the
     * Web Playback SDK demands them, alongside {@code streaming}. Without all three it
     * refuses to start with "Invalid token scopes".
     *
     * <p>They were briefly removed on the reasoning that the February 2026 migration
     * deleted {@code email} and {@code country} from the user object, so nothing could
     * read them anyway. That was wrong: the SDK checks the scopes granted on the token,
     * not whether the app ever reads the fields behind them. Removing a scope because
     * its data looks unused is a trap — the scope may be gating something else.
     *
     * <p>Space-delimited, as the OAuth spec requires. Commas produce the same
     * "Invalid token scopes" error, from a different cause.
     */
    public static final String SCOPES =
            "playlist-read-private playlist-read-collaborative "
                    + "streaming user-read-email user-read-private";
}
