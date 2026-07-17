package dev.unitedplaylists.provider.newpipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.provider.ImportedPlaylist;
import dev.unitedplaylists.provider.PlaybackMethod;
import dev.unitedplaylists.provider.PlaybackTicket;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.soundcloud.SoundCloudProvider;
import dev.unitedplaylists.provider.youtube.YoutubeScraperProvider;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shared behaviour of the scraper-backed providers.
 *
 * <p>Uses a mocked {@link NewPipeExtractionService} so nothing touches the network:
 * these assert how a provider treats the extractor, not that the extractor works.
 * Whether extraction actually works against live sites is {@code NewPipeLiveProbeTest},
 * run by hand.
 */
class NewPipeProviderTest {

    private final NewPipeExtractionService extraction = mock(NewPipeExtractionService.class);
    private final ProviderCredentials anon = ProviderCredentials.anonymous(ProviderId.YOUTUBE);

    private YoutubeScraperProvider youtube() {
        return new YoutubeScraperProvider(extraction, true);
    }

    @Test
    @DisplayName("a scraper provider needs no auth and no setup")
    void isAnonymousAndReady() {
        YoutubeScraperProvider provider = youtube();

        assertThat(provider.requiresAuthentication()).isFalse();
        assertThat(provider.isAvailable()).isTrue();
        assertThat(provider.isSetupSupported()).isFalse();
        assertThat(provider.unavailableReason()).isNull();
    }

    @Test
    @DisplayName("when the scraper is switched off it is simply unavailable")
    void respectsTheEnabledFlag() {
        assertThat(new YoutubeScraperProvider(extraction, false).isAvailable()).isFalse();
    }

    @Test
    @DisplayName("search delegates to the extractor, tagged with this provider")
    void searchDelegates() {
        Track track = new Track(
                new TrackRef(ProviderId.YOUTUBE, "vid1"), "A Song", List.of("A Channel"),
                null, Duration.ofMinutes(3), null, true);
        when(extraction.search(any(), eq(ProviderId.YOUTUBE), eq("query"), anyInt()))
                .thenReturn(List.of(track));

        List<Track> results = youtube().search(anon, "query", 10);

        assertThat(results).containsExactly(track);
    }

    @Test
    @DisplayName("playback resolves a fresh stream URL as a DIRECT_AUDIO ticket")
    void playbackReturnsADirectAudioTicket() {
        TrackRef ref = new TrackRef(ProviderId.YOUTUBE, "dQw4w9WgXcQ");
        when(extraction.resolveAudioStreamUrl(any(), eq(ProviderId.YOUTUBE), eq(ref)))
                .thenReturn("https://stream.example/audio.webm");

        PlaybackTicket ticket = youtube().resolvePlayback(anon, ref);

        assertThat(ticket.method()).isEqualTo(PlaybackMethod.DIRECT_AUDIO);
        assertThat(ticket.params()).containsEntry("streamUrl", "https://stream.example/audio.webm");
        assertThat(ticket.ref()).isEqualTo(ref);
    }

    @Test
    @DisplayName("playback refuses a track from another service")
    void playbackRejectsForeignTrack() {
        TrackRef spotifyRef = new TrackRef(ProviderId.SPOTIFY, "abc");

        assertThatThrownBy(() -> youtube().resolvePlayback(anon, spotifyRef))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).getKind())
                        .isEqualTo(ProviderException.Kind.UNSUPPORTED));
    }

    @Test
    @DisplayName("fetchPlaylists is unsupported: there is no account to enumerate")
    void fetchPlaylistsIsUnsupported() {
        assertThatThrownBy(() -> youtube().fetchPlaylists(anon))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.getKind()).isEqualTo(ProviderException.Kind.UNSUPPORTED);
                    assertThat(pe.getMessage()).containsIgnoringCase("paste a playlist link");
                });
    }

    @Test
    @DisplayName("import by URL delegates to the extractor")
    void importByUrlDelegates() {
        ImportedPlaylist playlist = ImportedPlaylist.readable(
                "PL1", "My Mix", null, null, List.of());
        when(extraction.fetchPlaylistByUrl(any(), eq(ProviderId.YOUTUBE), eq("https://yt/playlist")))
                .thenReturn(playlist);

        assertThat(youtube().importByUrl("https://yt/playlist")).isEqualTo(playlist);
    }

    @Test
    @DisplayName("SoundCloud is the same provider pointed at a different service")
    void soundcloudMirrorsYoutube() {
        SoundCloudProvider soundcloud = new SoundCloudProvider(extraction, true);

        assertThat(soundcloud.id()).isEqualTo(ProviderId.SOUNDCLOUD);
        assertThat(soundcloud.displayName()).isEqualTo("SoundCloud");
        assertThat(soundcloud.requiresAuthentication()).isFalse();
        assertThat(soundcloud.isAvailable()).isTrue();
    }
}
