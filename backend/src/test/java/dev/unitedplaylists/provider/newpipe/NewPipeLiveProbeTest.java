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
        String url = extraction.resolveAudioStreamUrl(
                ServiceList.YouTube, ProviderId.YOUTUBE,
                new TrackRef(ProviderId.YOUTUBE, "dQw4w9WgXcQ"));

        assertThat(url).startsWith("https://");
        System.out.println("YouTube audio URL: " + url.substring(0, Math.min(80, url.length())) + "…");
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
}
