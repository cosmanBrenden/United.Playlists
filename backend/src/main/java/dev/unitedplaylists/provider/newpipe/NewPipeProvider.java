package dev.unitedplaylists.provider.newpipe;

import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.provider.ImportedPlaylist;
import dev.unitedplaylists.provider.MusicProvider;
import dev.unitedplaylists.provider.PlaybackMethod;
import dev.unitedplaylists.provider.PlaybackTicket;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderException;
import java.util.List;
import java.util.Map;
import org.schabi.newpipe.extractor.StreamingService;

/**
 * Shared behaviour for the scraper-backed providers.
 *
 * <p>YouTube and SoundCloud are the same integration pointed at different sites, so
 * everything lives here and the subclasses supply only their {@link StreamingService},
 * name, and console link. Adding another NewPipe-supported site later — Bandcamp, say —
 * is a subclass and a {@code ProviderId}, nothing more.
 *
 * <p>These providers are anonymous: no OAuth, no tokens, no per-user account. That has
 * consequences the interface makes explicit. {@link #requiresAuthentication()} is false,
 * so search reaches them without a connection. {@link #fetchPlaylists} cannot work —
 * there is no account to enumerate — so it throws, and import happens by pasting a
 * public URL instead.
 */
public abstract class NewPipeProvider implements MusicProvider {

    private final NewPipeExtractionService extraction;
    private final boolean enabled;

    protected NewPipeProvider(NewPipeExtractionService extraction, boolean enabled) {
        this.extraction = extraction;
        this.enabled = enabled;
    }

    /** The NewPipe service this provider scrapes. */
    protected abstract StreamingService service();

    @Override
    public boolean requiresAuthentication() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        // Nothing to configure and nobody to sign in, so available whenever the scraper
        // is switched on. The switch exists mainly so tests can keep the providers from
        // making live network calls; in production it defaults on.
        return enabled;
    }

    @Override
    public boolean isSetupSupported() {
        // No credentials to enter. The UI shows these as ready, with no setup form.
        return false;
    }

    @Override
    public List<Track> search(ProviderCredentials credentials, String query, int limit) {
        return extraction.search(service(), id(), query, limit);
    }

    @Override
    public List<ImportedPlaylist> fetchPlaylists(ProviderCredentials credentials) {
        // Anonymous scraping has no account to read. Import is by URL instead; the
        // controller routes to importByUrl rather than here.
        throw new ProviderException(
                id(),
                ProviderException.Kind.UNSUPPORTED,
                displayName() + " has no account to import from. Paste a playlist link instead.");
    }

    /** Imports a playlist from its public URL. The scraper equivalent of "import my playlists". */
    public ImportedPlaylist importByUrl(String url) {
        return extraction.fetchPlaylistByUrl(service(), id(), url);
    }

    @Override
    public PlaybackTicket resolvePlayback(ProviderCredentials credentials, TrackRef ref) {
        if (ref.provider() != id()) {
            throw new ProviderException(
                    id(),
                    ProviderException.Kind.UNSUPPORTED,
                    "Not a " + displayName() + " track: " + ref.toKey());
        }
        // Resolved fresh every time: stream URLs are short-lived and IP-bound, so a
        // cached one is worse than useless. This is the one place a scraper provider
        // does real work at play time rather than at import.
        NewPipeExtractionService.ResolvedStream stream =
                extraction.resolveAudioStream(service(), id(), ref);
        // "protocol" tells the client whether the URL is a progressive file (plain
        // <audio>) or an HLS playlist (needs hls.js/native MSE). SoundCloud is HLS-only
        // for most tracks, so without this flag it would fail to play at all.
        return new PlaybackTicket(
                ref,
                PlaybackMethod.DIRECT_AUDIO,
                Map.of(
                        "streamUrl", stream.url(),
                        "protocol", stream.protocol(),
                        "trackId", ref.providerTrackId()));
    }
}
