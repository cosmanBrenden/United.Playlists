package dev.unitedplaylists.provider.newpipe;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.provider.ImportedPlaylist;
import dev.unitedplaylists.provider.ProviderException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.springframework.stereotype.Service;

/**
 * Turns NewPipeExtractor's scraping into this app's domain types.
 *
 * <p>Both scraper-backed providers (YouTube, SoundCloud) share this; they differ only
 * in which {@link StreamingService} they hand it. That is the whole appeal of NewPipe
 * here — one extraction path, several sites.
 *
 * <p>Every entry point is best-effort per item: a single unparseable search result or
 * a single unavailable playlist entry is skipped, never allowed to sink the batch.
 * Scraping is inherently brittle — the markup shifts under it — so tolerating the odd
 * broken item is not politeness, it is the difference between "search returned 9 of 10"
 * and "search failed".
 */
@Service
public class NewPipeExtractionService {

    /**
     * Guards against a playlist that pages forever. Real playlists end; a parser
     * confused by new markup might not.
     */
    private static final int MAX_PLAYLIST_ITEMS = 5000;

    /**
     * Searches a service and maps the track results.
     *
     * @param providerId the id to stamp on each result, so merged search can say where
     *                   it came from
     */
    public List<Track> search(
            StreamingService service, ProviderId providerId, String query, int limit) {
        try {
            SearchInfo info = SearchInfo.getInfo(
                    service, service.getSearchQHFactory().fromQuery(query));

            List<Track> tracks = new ArrayList<>();
            for (InfoItem item : info.getRelatedItems()) {
                if (tracks.size() >= limit) {
                    break;
                }
                if (item instanceof StreamInfoItem stream) {
                    toTrack(service, providerId, stream).ifPresent(tracks::add);
                }
            }
            return List.copyOf(tracks);
        } catch (ExtractionException | java.io.IOException e) {
            throw wrap(providerId, "search", e);
        }
    }

    /**
     * Imports a playlist from its public URL.
     *
     * <p>Anonymous scraping cannot read a user's account, so this is the import path:
     * paste a link. Works for public and unlisted playlists; a private one the scraper
     * cannot see is reported as unreadable rather than throwing.
     */
    public ImportedPlaylist fetchPlaylistByUrl(
            StreamingService service, ProviderId providerId, String url) {
        try {
            PlaylistInfo info = PlaylistInfo.getInfo(service, url);

            List<Track> tracks = new ArrayList<>();
            for (InfoItem item : info.getRelatedItems()) {
                if (tracks.size() >= MAX_PLAYLIST_ITEMS) {
                    break;
                }
                if (item instanceof StreamInfoItem stream) {
                    toTrack(service, providerId, stream).ifPresent(tracks::add);
                }
            }
            return ImportedPlaylist.readable(
                    playlistId(service, url, info),
                    info.getName(),
                    null,
                    bestImage(info.getThumbnails()),
                    tracks);
        } catch (ExtractionException | java.io.IOException e) {
            throw wrap(providerId, "playlist import", e);
        }
    }

    /**
     * A resolved, playable audio stream: its URL and how it is delivered.
     *
     * <p>{@code protocol} tells the client which player path to take. A plain HTML5
     * {@code <audio>} element handles {@link #PROGRESSIVE}; {@link #HLS} needs
     * Media Source Extensions (hls.js in Chromium/Electron, native on Safari/iOS),
     * because Chromium ships no native HLS. SoundCloud increasingly serves only HLS,
     * so this distinction is what makes it play at all — see the delivery-method
     * preference in {@link #resolveAudioStream}.
     */
    public record ResolvedStream(String url, String protocol) {
        public static final String PROGRESSIVE = "progressive";
        public static final String HLS = "hls";
    }

    /**
     * Resolves a playable audio stream for a track.
     *
     * <p>This is what direct playback needs. The URL is time-limited and tied to the
     * requesting IP, so it is resolved on demand rather than stored — a cached one goes
     * stale within hours.
     *
     * <p>Progressive delivery is preferred over HLS: a progressive URL plays straight
     * through a plain {@code <audio>} element on every platform, while HLS needs Media
     * Source Extensions. When a service offers both (YouTube), we take progressive and
     * the client never touches hls.js. When a service offers only HLS (SoundCloud, for
     * most tracks now), we return it flagged so the client uses the HLS path rather than
     * silently failing with "audio format could not be played".
     *
     * @return the chosen audio-only stream's URL and its delivery protocol
     * @throws ProviderException if the track has no playable audio (deleted, private,
     *     region-locked, or a live stream)
     */
    public ResolvedStream resolveAudioStream(
            StreamingService service, ProviderId providerId, TrackRef ref) {
        String url = urlForId(service, providerId, ref.providerTrackId());
        try {
            StreamInfo info = StreamInfo.getInfo(service, url);

            if (info.getStreamType() == StreamType.LIVE_STREAM
                    || info.getStreamType() == StreamType.AUDIO_LIVE_STREAM) {
                throw new ProviderException(
                        providerId, ProviderException.Kind.UNSUPPORTED,
                        "Live streams cannot be played as tracks");
            }

            List<AudioStream> playable = info.getAudioStreams().stream()
                    .filter(stream -> stream.getContent() != null && stream.isUrl())
                    .filter(NewPipeExtractionService::isDirectlyPlayable)
                    .toList();

            // Highest bitrate wins within a protocol; this is a music player and the
            // audio-only streams are small enough that quality is the only axis that
            // matters. But a progressive stream is preferred over a higher-bitrate HLS
            // one, because it plays on every platform with no extra machinery.
            return playable.stream()
                    .filter(stream -> !isHls(stream))
                    .max(Comparator.comparingInt(AudioStream::getAverageBitrate))
                    .map(stream -> new ResolvedStream(stream.getContent(), ResolvedStream.PROGRESSIVE))
                    .or(() -> playable.stream()
                            .filter(NewPipeExtractionService::isHls)
                            .max(Comparator.comparingInt(AudioStream::getAverageBitrate))
                            .map(stream -> new ResolvedStream(stream.getContent(), ResolvedStream.HLS)))
                    .orElseThrow(() -> new ProviderException(
                            providerId, ProviderException.Kind.NOT_FOUND,
                            "No playable audio stream for this track"));
        } catch (ExtractionException | java.io.IOException e) {
            throw wrap(providerId, "playback", e);
        }
    }

    /**
     * Whether a stream is one the client can actually play. Progressive HTTP plays in a
     * plain {@code <audio>} element; HLS plays via MSE (hls.js/native). DASH, Smooth
     * Streaming and torrents have no player here, so they are excluded rather than handed
     * over to fail opaquely.
     */
    private static boolean isDirectlyPlayable(AudioStream stream) {
        return stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP || isHls(stream);
    }

    private static boolean isHls(AudioStream stream) {
        return stream.getDeliveryMethod() == DeliveryMethod.HLS;
    }

    /** Reconstructs the watch/track URL from a stored provider id. */
    private String urlForId(StreamingService service, ProviderId providerId, String id) {
        try {
            return service.getStreamLHFactory().getUrl(id);
        } catch (ExtractionException e) {
            throw new ProviderException(
                    providerId, ProviderException.Kind.NOT_FOUND,
                    "Not a valid " + providerId + " track id: " + id, e);
        }
    }

    private java.util.Optional<Track> toTrack(
            StreamingService service, ProviderId providerId, StreamInfoItem item) {
        // A live stream in results is not a track. Skip rather than surface something
        // that cannot be added to a playlist meaningfully.
        if (item.getStreamType() == StreamType.LIVE_STREAM
                || item.getStreamType() == StreamType.AUDIO_LIVE_STREAM) {
            return java.util.Optional.empty();
        }
        LinkHandlerFactory factory = service.getStreamLHFactory();
        String id;
        try {
            id = factory.getId(item.getUrl());
        } catch (ExtractionException e) {
            // No stable id means nothing to store or replay later.
            return java.util.Optional.empty();
        }
        if (id == null || id.isBlank()) {
            return java.util.Optional.empty();
        }

        long durationSeconds = item.getDuration();
        String uploader = item.getUploaderName();

        return java.util.Optional.of(new Track(
                new TrackRef(providerId, id),
                item.getName() == null ? "Unknown" : item.getName(),
                uploader == null || uploader.isBlank() ? List.of() : List.of(uploader),
                null, // Neither service exposes an album via this path.
                durationSeconds > 0 ? Duration.ofSeconds(durationSeconds) : null,
                bestImage(item.getThumbnails()),
                true));
    }

    private String playlistId(StreamingService service, String url, PlaylistInfo info) {
        try {
            return service.getPlaylistLHFactory().getId(url);
        } catch (ExtractionException e) {
            // Fall back to the id NewPipe already resolved on the info object.
            return info.getId();
        }
    }

    /** Largest thumbnail NewPipe offers, or null. */
    private String bestImage(List<Image> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        return images.stream()
                .max(Comparator.comparingInt(Image::getHeight))
                .map(Image::getUrl)
                .orElse(null);
    }

    /**
     * Maps a NewPipe failure to a {@link ProviderException}.
     *
     * <p>Extraction failing usually means YouTube changed something and this version of
     * the extractor has not caught up — a maintenance signal, not a user error. It is
     * reported as {@code UNAVAILABLE} so the UI treats it as "the service is having
     * trouble" rather than telling the user to reconnect, which would do nothing.
     */
    private ProviderException wrap(ProviderId providerId, String operation, Exception cause) {
        return new ProviderException(
                providerId,
                ProviderException.Kind.UNAVAILABLE,
                providerId + " " + operation + " failed (the extractor may need updating): "
                        + cause.getMessage(),
                cause);
    }
}
