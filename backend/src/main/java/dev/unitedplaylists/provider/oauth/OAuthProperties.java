package dev.unitedplaylists.provider.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The OAuth redirect URI, defined once.
 *
 * <p>This exists because it was previously defaulted inline in two separate
 * {@code @Value} strings, and they drifted: one said port 842, the other 8420. That
 * class of bug is unusually nasty here, because the two readers do different jobs —
 * one tells the user which URI to register with the service, the other puts it in
 * the authorize request. A mismatch means the app instructs the user to register a
 * URI it will never send, and the service rejects the sign-in with an error that
 * names neither value.
 *
 * @param redirectUri must match what is registered with every service, exactly.
 *     Loopback with the literal IP rather than "localhost": the OAuth-for-native-apps
 *     spec (RFC 8252) calls for the IP literal, and Spotify rejects "localhost"
 *     outright.
 */
@ConfigurationProperties(prefix = "unitedplaylists.oauth")
public record OAuthProperties(String redirectUri) {

    /** The port the Electron shell listens on for the callback. */
    public static final String DEFAULT_REDIRECT_URI = "http://127.0.0.1:8420/callback";

    public OAuthProperties {
        redirectUri = redirectUri == null || redirectUri.isBlank()
                ? DEFAULT_REDIRECT_URI
                : redirectUri.trim();
    }
}
