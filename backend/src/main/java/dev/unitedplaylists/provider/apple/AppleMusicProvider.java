package dev.unitedplaylists.provider.apple;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.provider.ImportedPlaylist;
import dev.unitedplaylists.provider.MusicProvider;
import dev.unitedplaylists.provider.PlaybackTicket;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Apple Music: registered, not yet implemented.
 *
 * <p>Present so the UI can list Apple Music as a known-but-unavailable service
 * rather than pretending it does not exist, and so the shape of a third provider
 * is pinned down while the abstraction is still cheap to change.
 *
 * <p>Implementing it needs things the other two do not:
 *
 * <ul>
 *   <li>A paid Apple Developer Program membership, to create a MusicKit private
 *       key. There is no free tier, so this cannot be built or tested without one.
 *   <li>A developer token: an ES256 JWT signed with that key, minted server-side
 *       and refreshed at most every six months.
 *   <li>A Music User Token obtained through MusicKit on the client, which is a
 *       different flow to OAuth — hence {@code AppleMusicProvider} having no
 *       {@link dev.unitedplaylists.provider.oauth.OAuthClient} counterpart.
 * </ul>
 *
 * <p>Everything else — {@code /v1/me/library/playlists}, {@code /v1/catalog/{sf}/search},
 * playback via MusicKit JS — maps onto the existing interface without changes,
 * which is the point of the stub.
 */
@Component
public class AppleMusicProvider implements MusicProvider {

    @Override
    public ProviderId id() {
        return ProviderId.APPLE_MUSIC;
    }

    @Override
    public String displayName() {
        return "Apple Music";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String unavailableReason() {
        // Unlike the other two, this is not a configuration gap the user can close:
        // the integration does not exist yet, and building it needs a paid membership.
        return "Not supported yet. Apple Music needs a paid Apple Developer membership "
                + "to issue a MusicKit key, so it is not built into this release.";
    }

    @Override
    public boolean isSetupSupported() {
        // No credentials would help: there is no integration behind them yet. Offering
        // the form would waste the user's time and an Apple Developer subscription.
        return false;
    }

    @Override
    public List<ImportedPlaylist> fetchPlaylists(ProviderCredentials credentials) {
        throw notImplemented();
    }

    @Override
    public List<Track> search(ProviderCredentials credentials, String query, int limit) {
        throw notImplemented();
    }

    @Override
    public PlaybackTicket resolvePlayback(ProviderCredentials credentials, TrackRef ref) {
        throw notImplemented();
    }

    private ProviderException notImplemented() {
        return new ProviderException(
                ProviderId.APPLE_MUSIC,
                ProviderException.Kind.UNSUPPORTED,
                "Apple Music support is not implemented yet");
    }
}
