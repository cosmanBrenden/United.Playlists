package dev.unitedplaylists.provider.newpipe;

import static org.assertj.core.api.Assertions.assertThat;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;

/**
 * Hits real YouTube and SoundCloud through NewPipe.
 *
 * <p>Disabled by default: it depends on the network and on YouTube not having changed
 * its markup this morning, so it must never gate CI. It exists to answer the one
 * question unit tests cannot — does the scraper actually work against the live sites
 * right now — and is run by hand with {@code -Dnewpipe.live=true}.
 */
@DisplayName("NewPipe live extraction (manual)")
class NewPipeLiveProbeTest {

    private static final NewPipeExtractionService extraction = new NewPipeExtractionService();

    @BeforeAll
    static void initNewPipe() {
        NewPipe.init(new NewPipeDownloader());
    }

    private boolean live() {
        return Boolean.parseBoolean(System.getProperty("newpipe.live", "false"));
    }

    @Test
    @DisplayName("YouTube search returns real tracks")
    void youtubeSearch() {
        if (!live()) {
            return;
        }
        List<Track> tracks = extraction.search(
                ServiceList.YouTube, ProviderId.YOUTUBE, "rick astley never gonna give you up", 5);

        assertThat(tracks).isNotEmpty();
        assertThat(tracks).allSatisfy(track -> {
            assertThat(track.provider()).isEqualTo(ProviderId.YOUTUBE);
            assertThat(track.title()).isNotBlank();
            assertThat(track.ref().providerTrackId()).isNotBlank();
        });
        System.out.println("YouTube search[0]: " + tracks.get(0).title()
                + " / id=" + tracks.get(0).ref().providerTrackId());
    }

    @Test
    @DisplayName("YouTube resolves a playable audio stream URL")
    void youtubePlayback() {
        if (!live()) {
            return;
        }
        // Rick Astley — Never Gonna Give You Up. A stable, always-available video.
        NewPipeExtractionService.ResolvedStream stream = extraction.resolveAudioStream(
                ServiceList.YouTube, ProviderId.YOUTUBE,
                new TrackRef(ProviderId.YOUTUBE, "dQw4w9WgXcQ"));

        assertThat(stream.url()).startsWith("https://");
        // YouTube serves progressive audio, so the client never needs the HLS path.
        assertThat(stream.protocol())
                .isEqualTo(NewPipeExtractionService.ResolvedStream.PROGRESSIVE);
        System.out.println("YouTube audio (" + stream.protocol() + "): "
                + stream.url().substring(0, Math.min(80, stream.url().length())) + "…");
    }

    @Test
    @DisplayName("SoundCloud search returns real tracks")
    void soundcloudSearch() {
        if (!live()) {
            return;
        }
        List<Track> tracks = extraction.search(
                ServiceList.SoundCloud, ProviderId.SOUNDCLOUD, "flume", 5);

        assertThat(tracks).isNotEmpty();
        assertThat(tracks).allSatisfy(track ->
                assertThat(track.provider()).isEqualTo(ProviderId.SOUNDCLOUD));
        System.out.println("SoundCloud search[0]: " + tracks.get(0).title());
    }

    @Test
    @DisplayName("SoundCloud resolves a playable audio stream (HLS)")
    void soundcloudPlayback() {
        if (!live()) {
            return;
        }
        // First search result becomes the track we try to resolve, since SoundCloud ids
        // are not stable strings we can hard-code the way a YouTube video id is.
        List<Track> tracks = extraction.search(
                ServiceList.SoundCloud, ProviderId.SOUNDCLOUD, "flume never be like you", 5);
        assertThat(tracks).isNotEmpty();

        NewPipeExtractionService.ResolvedStream stream = extraction.resolveAudioStream(
                ServiceList.SoundCloud, ProviderId.SOUNDCLOUD, tracks.get(0).ref());

        // The regression this guards: SoundCloud serves only HLS, and the old code picked
        // the highest-bitrate stream regardless of protocol and handed the HLS URL to a
        // plain <audio> element, which cannot play it. It must resolve, and be flagged so
        // the client takes the HLS path.
        assertThat(stream.url()).startsWith("https://");
        assertThat(stream.protocol()).isEqualTo(NewPipeExtractionService.ResolvedStream.HLS);
        System.out.println("SoundCloud audio (" + stream.protocol() + "): "
                + stream.url().substring(0, Math.min(80, stream.url().length())) + "…");
    }
}
