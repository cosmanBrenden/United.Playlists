package dev.unitedplaylists.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.unitedplaylists.domain.Playlist;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.service.MigrationService.MigrationResult;
import dev.unitedplaylists.service.SearchService.ProviderFailure;
import dev.unitedplaylists.service.SearchService.SearchResults;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The migration job: search the target, auto-apply confident matches, and hand
 * back the rest for the user to resolve.
 *
 * <p>The matcher is real here rather than mocked — the whole point of a migration
 * test is whether a plausible set of search results ends up auto-replaced or
 * surfaced for review, and that is exactly the matcher's judgement.
 */
@ExtendWith(MockitoExtension.class)
class MigrationServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID PLAYLIST_ID = UUID.randomUUID();

    @Mock
    private PlaylistService playlistService;

    @Mock
    private SearchService searchService;

    private MigrationService migrationService;

    @BeforeEach
    void setUp() {
        migrationService = new MigrationService(playlistService, searchService, new TrackMatcher(), 10);
    }

    private static Track track(ProviderId provider, String id, String title, Duration duration) {
        return new Track(
                new TrackRef(provider, id), title, List.of("Adele"), "Album", duration, null, true);
    }

    private Playlist playlistOf(Track... tracks) {
        Playlist playlist = Playlist.createLocal("Mixed", null, T0);
        for (Track track : tracks) {
            playlist.addTrack(track, T0);
        }
        return playlist;
    }

    private SearchResults resultsWith(Track... tracks) {
        return new SearchResults("q", List.of(tracks), List.of());
    }

    @Test
    @DisplayName("an exact match on the target is replaced automatically")
    void exactMatchIsAutoReplaced() {
        Track source = track(ProviderId.SPOTIFY, "sp1", "Hello", Duration.ofSeconds(295));
        Track match = track(ProviderId.YOUTUBE, "yt1", "Hello", Duration.ofSeconds(296));
        Playlist playlist = playlistOf(source);

        when(playlistService.findById(PLAYLIST_ID)).thenReturn(playlist);
        when(searchService.searchAll(anyString(), anyInt())).thenReturn(resultsWith(match));
        when(playlistService.replaceTracks(eq(PLAYLIST_ID), any())).thenReturn(playlist);

        MigrationResult result = migrationService.migrate(
                PLAYLIST_ID, ProviderId.YOUTUBE, List.of());

        assertThat(result.replaced()).singleElement().satisfies(r -> {
            assertThat(r.position()).isEqualTo(0);
            assertThat(r.to()).isEqualTo(match);
        });
        assertThat(result.unresolved()).isEmpty();

        ArgumentCaptor<Map<Integer, Track>> captor = ArgumentCaptor.captor();
        verify(playlistService).replaceTracks(eq(PLAYLIST_ID), captor.capture());
        assertThat(captor.getValue()).containsEntry(0, match);
    }

    @Test
    @DisplayName("no confident match leaves the track unresolved with candidates from every service")
    void noMatchIsUnresolvedWithCrossServiceCandidates() {
        Track source = track(ProviderId.SPOTIFY, "sp1", "Hello", Duration.ofSeconds(295));
        // A live take on the target (too long to auto-accept) plus a copy on a third service.
        Track liveOnTarget = track(ProviderId.YOUTUBE, "yt1", "Hello", Duration.ofSeconds(500));
        Track onSoundcloud = track(ProviderId.SOUNDCLOUD, "sc1", "Hello", Duration.ofSeconds(296));
        Playlist playlist = playlistOf(source);

        when(playlistService.findById(PLAYLIST_ID)).thenReturn(playlist);
        when(searchService.searchAll(anyString(), anyInt()))
                .thenReturn(resultsWith(liveOnTarget, onSoundcloud));

        MigrationResult result = migrationService.migrate(
                PLAYLIST_ID, ProviderId.YOUTUBE, List.of());

        assertThat(result.replaced()).isEmpty();
        assertThat(result.unresolved()).singleElement().satisfies(u -> {
            assertThat(u.position()).isEqualTo(0);
            // Candidates span services, and never include the track being migrated from.
            assertThat(u.candidates()).contains(liveOnTarget, onSoundcloud);
            assertThat(u.candidates()).noneMatch(c -> c.ref().equals(source.ref()));
        });
        verify(playlistService, never()).replaceTracks(any(), any());
    }

    @Test
    @DisplayName("tracks already on the target service are skipped, not searched")
    void alreadyOnTargetIsSkipped() {
        Track alreadyThere = track(ProviderId.YOUTUBE, "yt1", "Hello", Duration.ofSeconds(295));
        Playlist playlist = playlistOf(alreadyThere);

        when(playlistService.findById(PLAYLIST_ID)).thenReturn(playlist);

        MigrationResult result = migrationService.migrate(
                PLAYLIST_ID, ProviderId.YOUTUBE, List.of());

        assertThat(result.alreadyOnTarget()).isEqualTo(1);
        assertThat(result.replaced()).isEmpty();
        assertThat(result.unresolved()).isEmpty();
        verify(searchService, never()).searchAll(anyString(), anyInt());
    }

    @Test
    @DisplayName("only the selected positions are migrated")
    void onlySelectedPositionsAreMigrated() {
        Track first = track(ProviderId.SPOTIFY, "sp1", "Hello", Duration.ofSeconds(295));
        Track second = track(ProviderId.SPOTIFY, "sp2", "Someone Like You", Duration.ofSeconds(285));
        Track matchForFirst = track(ProviderId.YOUTUBE, "yt1", "Hello", Duration.ofSeconds(295));
        Playlist playlist = playlistOf(first, second);

        when(playlistService.findById(PLAYLIST_ID)).thenReturn(playlist);
        when(searchService.searchAll(anyString(), anyInt())).thenReturn(resultsWith(matchForFirst));
        when(playlistService.replaceTracks(eq(PLAYLIST_ID), any())).thenReturn(playlist);

        MigrationResult result = migrationService.migrate(
                PLAYLIST_ID, ProviderId.YOUTUBE, List.of(0));

        // Position 1 was never asked about.
        verify(searchService).searchAll(anyString(), anyInt());
        assertThat(result.replaced()).singleElement()
                .satisfies(r -> assertThat(r.position()).isEqualTo(0));
    }

    @Test
    @DisplayName("a failed service is reported once and surfaces in the result")
    void serviceFailureIsReported() {
        Track source = track(ProviderId.SPOTIFY, "sp1", "Hello", Duration.ofSeconds(295));
        Playlist playlist = playlistOf(source);
        ProviderFailure failure = new ProviderFailure(
                ProviderId.YOUTUBE, ProviderException.Kind.RATE_LIMITED, "slow down", false);

        when(playlistService.findById(PLAYLIST_ID)).thenReturn(playlist);
        when(searchService.searchAll(anyString(), anyInt()))
                .thenReturn(new SearchResults("q", List.of(), List.of(failure)));

        MigrationResult result = migrationService.migrate(
                PLAYLIST_ID, ProviderId.YOUTUBE, List.of());

        assertThat(result.failures()).singleElement()
                .satisfies(f -> assertThat(f.provider()).isEqualTo(ProviderId.YOUTUBE));
        // With the target unreachable, the track cannot be matched — so it is surfaced,
        // not dropped.
        assertThat(result.unresolved()).hasSize(1);
    }
}
