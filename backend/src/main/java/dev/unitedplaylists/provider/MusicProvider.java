package dev.unitedplaylists.provider;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import java.util.List;

/**
 * Everything the app needs from a streaming service.
 *
 * <p>This interface is the extension point: supporting a new service means
 * writing one implementation and registering it as a Spring bean.
 * {@link ProviderRegistry} discovers it automatically, and aggregated search,
 * import, and playback pick it up with no changes to their own code.
 *
 * <p>Note what is <em>not</em> here. There is no method to create, update, or
 * delete a playlist on the service, and none will be added. Local playlists are
 * the app's own; the services are read-only sources. A provider therefore cannot
 * write back even by mistake.
 *
 * <p>Implementations must be stateless and thread-safe: aggregated search calls
 * every provider concurrently on a shared pool.
 */
public interface MusicProvider {

    /** Which service this speaks for. Must be unique across all beans. */
    ProviderId id();

    /** Human-readable name for the UI, e.g. "Spotify". */
    String displayName();

    /**
     * Whether this provider needs the user to sign in before it can be used.
     *
     * <p>The two kinds of provider differ fundamentally. An authenticated provider
     * (Spotify) reaches a per-user account over OAuth and does nothing until connected.
     * An anonymous provider (the scraper-backed YouTube and SoundCloud) reaches a public
     * catalogue with no account at all — it is usable the moment the app starts, and has
     * no connect, disconnect, or token to expire.
     *
     * <p>This is what lets aggregated search include a scraper provider without a
     * connection, and what keeps the UI from offering a pointless "Connect" button for
     * one.
     */
    default boolean requiresAuthentication() {
        return true;
    }

    /**
     * Whether this provider can be connected right now. False covers both "not
     * implemented" and "not configured", so the UI can list the service without
     * offering a connect button that would fail.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Why {@link #isAvailable()} is false, phrased for the user, or null when the
     * provider is available.
     *
     * <p>The reason belongs to the provider because only the provider knows it: a
     * missing client id and an unimplemented service are entirely different problems
     * with entirely different fixes. Leaving the UI to guess produces exactly one
     * wrong answer shown for every case.
     */
    default String unavailableReason() {
        return null;
    }

    /**
     * Whether this service issues a client secret alongside the client id.
     *
     * <p>Drives the setup form: Spotify's PKCE flow has no secret, and showing the
     * field anyway sends people looking for something that does not exist, while
     * hiding it from Google breaks the token exchange.
     */
    default boolean requiresClientSecret() {
        return false;
    }

    /**
     * Whether the user can set this service up by entering credentials.
     *
     * <p>False for services that are not implemented: no client id will make Apple
     * Music work, so offering the form would be a dead end.
     */
    default boolean isSetupSupported() {
        return true;
    }

    /** Where the user creates credentials for this service, or null if not applicable. */
    default String consoleUrl() {
        return null;
    }

    /**
     * The OAuth scopes this provider cannot work without.
     *
     * <p>Declared so a connection can be checked against what the service actually
     * granted. A token missing a scope fails with a bare 403 at the moment the user
     * tries to import — indistinguishable from a dashboard misconfiguration, and
     * fixable only by reconnecting, which nothing would otherwise suggest.
     *
     * <p>Only the scopes needed for reading the library belong here. Playback scopes
     * do not: they depend on the user's subscription, and lacking them should not
     * make the whole service look broken.
     */
    default List<String> requiredScopes() {
        return List.of();
    }

    /**
     * Steps to get credentials, in order, phrased for someone who has never seen the
     * service's developer console.
     *
     * <p>Lives with the provider because each console is different, and because the
     * alternative is the UI carrying a growing switch over service names.
     *
     * @param redirectUri the exact value the user must register, passed in because
     *     only the app knows which port it listens on
     */
    default List<String> setupInstructions(String redirectUri) {
        return List.of();
    }

    /**
     * Fetches every playlist the user owns, tracks included.
     *
     * @throws ProviderException on any service-side failure
     */
    List<ImportedPlaylist> fetchPlaylists(ProviderCredentials credentials);

    /**
     * Searches this service's catalogue.
     *
     * <p>Returned tracks carry this provider's id in their {@code ref}, which is
     * what lets merged results state their origin.
     *
     * @param limit maximum results; providers may return fewer, never more
     * @throws ProviderException on any service-side failure
     */
    List<Track> search(ProviderCredentials credentials, String query, int limit);

    /**
     * Produces client-side playback instructions for a track.
     *
     * <p>Must not return audio or any URL that yields audio. See
     * {@link PlaybackTicket}.
     *
     * @throws ProviderException if the track is unplayable for this user
     */
    PlaybackTicket resolvePlayback(ProviderCredentials credentials, TrackRef ref);
}
