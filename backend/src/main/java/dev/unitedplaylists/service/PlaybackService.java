package dev.unitedplaylists.service;

import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.provider.MusicProvider;
import dev.unitedplaylists.provider.PlaybackTicket;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderRegistry;
import org.springframework.stereotype.Service;

/**
 * Turns a track reference into client-side playback instructions (spec 2/3).
 *
 * <p>Each track plays through the service it came from, so a playlist mixing
 * Spotify and YouTube hands the client a different ticket per track and the client
 * switches SDKs between them. That is the whole mechanism: audio never passes
 * through this process, which is what keeps playback within each service's terms.
 */
@Service
public class PlaybackService {

    private final ProviderRegistry registry;
    private final ConnectionService connectionService;

    public PlaybackService(ProviderRegistry registry, ConnectionService connectionService) {
        this.registry = registry;
        this.connectionService = connectionService;
    }

    /**
     * @throws dev.unitedplaylists.provider.ProviderException if the service is
     *     unknown, not connected, or the track is unplayable for this user
     */
    public PlaybackTicket ticketFor(TrackRef ref) {
        MusicProvider provider = registry.require(ref.provider());
        // A scraper provider has no account to consult; asking ConnectionService for a
        // token it never stored would fail with a spurious "not connected".
        ProviderCredentials credentials = provider.requiresAuthentication()
                ? connectionService.credentialsFor(ref.provider())
                : ProviderCredentials.anonymous(ref.provider());
        return provider.resolvePlayback(credentials, ref);
    }
}
