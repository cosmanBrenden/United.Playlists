package dev.unitedplaylists.provider;

import dev.unitedplaylists.domain.ProviderId;

/**
 * A service call failed.
 *
 * <p>Carries the {@link ProviderId} because most failures here are partial: with
 * three services connected, one being rate-limited must degrade that service's
 * results rather than fail the user's search. Callers need to know who failed in
 * order to say so in the response.
 */
public class ProviderException extends RuntimeException {

    private final ProviderId provider;
    private final Kind kind;

    public enum Kind {
        /** Token invalid or revoked; the user must reconnect. */
        UNAUTHORIZED,
        /** Service asked us to slow down. */
        RATE_LIMITED,
        /** Account lacks the entitlement, e.g. Spotify playback without Premium. */
        FORBIDDEN,
        /** Track/playlist gone or not in this market. */
        NOT_FOUND,
        /** Service returned 5xx, timed out, or was unreachable. */
        UNAVAILABLE,
        /** The provider cannot do this at all, e.g. an unimplemented service. */
        UNSUPPORTED
    }

    public ProviderException(ProviderId provider, Kind kind, String message) {
        this(provider, kind, message, null);
    }

    public ProviderException(ProviderId provider, Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.kind = kind;
    }

    public ProviderId getProvider() {
        return provider;
    }

    public Kind getKind() {
        return kind;
    }

    /** True when reconnecting the service is what would fix this. */
    public boolean requiresReconnect() {
        return kind == Kind.UNAUTHORIZED;
    }
}
