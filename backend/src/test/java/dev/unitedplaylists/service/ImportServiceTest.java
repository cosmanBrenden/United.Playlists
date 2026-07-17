package dev.unitedplaylists.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.unitedplaylists.domain.Playlist;
import dev.unitedplaylists.domain.PlaylistOrigin;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.ImportedPlaylist;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.ProviderRegistry;
import dev.unitedplaylists.repository.PlaylistRepository;
import dev.unitedplaylists.support.FakeProvider;
import dev.unitedplaylists.support.Fixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImportServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private ConnectionService connectionService;

    @Mock
    private PlaylistRepository repository;

    private ImportService importService;
    private FakeProvider spotify;

    @BeforeEach
    void setUp() {
        spotify = FakeProvider.of(ProviderId.SPOTIFY);
        importService = new ImportService(
                new ProviderRegistry(List.of(spotify)), connectionService, repository, clock);
        when(connectionService.credentialsFor(ProviderId.SPOTIFY))
                .thenReturn(Fixtures.credentials(ProviderId.SPOTIFY));
        when(repository.save(any(Playlist.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByOrigin(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void importsPlaylistsAndRecordsTheirOrigin() {
        spotify.withPlaylists(List.of(ImportedPlaylist.readable(
                "sp_pl_1", "Road Trip", "for the car", null,
                List.of(Fixtures.spotifyTrack("t1", "One"), Fixtures.spotifyTrack("t2", "Two")))));

        ImportService.ImportSummary summary = importService.importFrom(ProviderId.SPOTIFY);

        assertThat(summary.importedCount()).isEqualTo(1);
        assertThat(summary.trackCount()).isEqualTo(2);
        assertThat(summary.imported()).singleElement().satisfies(playlist -> {
            assertThat(playlist.getName()).isEqualTo("Road Trip");
            assertThat(playlist.isImported()).isTrue();
            assertThat(playlist.getOrigin()).get().satisfies(origin -> {
                assertThat(origin.getProvider()).isEqualTo(ProviderId.SPOTIFY);
                assertThat(origin.getOriginPlaylistId()).isEqualTo("sp_pl_1");
                assertThat(origin.getImportedAt()).isEqualTo(NOW);
            });
        });
    }

    @Test
    @DisplayName("importing preserves the service's track order")
    void preservesTrackOrder() {
        spotify.withPlaylists(List.of(ImportedPlaylist.readable(
                "sp_pl_1", "Ordered", null, null,
                List.of(
                        Fixtures.spotifyTrack("t1", "First"),
                        Fixtures.spotifyTrack("t2", "Second"),
                        Fixtures.spotifyTrack("t3", "Third")))));

        ImportService.ImportSummary summary = importService.importFrom(ProviderId.SPOTIFY);

        assertThat(summary.imported().get(0).getEntries())
                .extracting(e -> e.toTrack().title())
                .containsExactly("First", "Second", "Third");
    }

    @Test
    @DisplayName("a playlist already imported is skipped, not overwritten")
    void skipsAlreadyImportedPlaylists() {
        Playlist existing = Playlist.createImported(
                "Road Trip", null,
                new PlaylistOrigin(ProviderId.SPOTIFY, "sp_pl_1", NOW.minusSeconds(86400)),
                NOW.minusSeconds(86400));
        when(repository.findByOrigin(ProviderId.SPOTIFY, "sp_pl_1")).thenReturn(Optional.of(existing));

        spotify.withPlaylists(List.of(ImportedPlaylist.readable(
                "sp_pl_1", "Road Trip", null, null, List.of(Fixtures.spotifyTrack("t1", "One")))));

        ImportService.ImportSummary summary = importService.importFrom(ProviderId.SPOTIFY);

        assertThat(summary.importedCount()).isZero();
        assertThat(summary.alreadyPresent()).isEqualTo(1);
        // The critical part: the local copy may hold the user's own edits, so nothing
        // is written over it.
        verify(repository, never()).save(any(Playlist.class));
    }

    @Test
    void importsOnlyTheNewPlaylistsOnASecondRun() {
        when(repository.findByOrigin(ProviderId.SPOTIFY, "old")).thenReturn(
                Optional.of(Playlist.createImported(
                        "Old", null, new PlaylistOrigin(ProviderId.SPOTIFY, "old", NOW), NOW)));
        when(repository.findByOrigin(ProviderId.SPOTIFY, "new")).thenReturn(Optional.empty());

        spotify.withPlaylists(List.of(
                ImportedPlaylist.readable("old", "Old", null, null, List.of()),
                ImportedPlaylist.readable("new", "New", null, null,
                        List.of(Fixtures.spotifyTrack("t1", "One")))));

        ImportService.ImportSummary summary = importService.importFrom(ProviderId.SPOTIFY);

        assertThat(summary.importedCount()).isEqualTo(1);
        assertThat(summary.alreadyPresent()).isEqualTo(1);
        assertThat(summary.imported().get(0).getName()).isEqualTo("New");
    }

    @Test
    void importingNothingIsNotAnError() {
        spotify.withPlaylists(List.of());

        ImportService.ImportSummary summary = importService.importFrom(ProviderId.SPOTIFY);

        assertThat(summary.importedCount()).isZero();
        assertThat(summary.imported()).isEmpty();
    }

    @Test
    void anEmptyPlaylistStillImports() {
        spotify.withPlaylists(List.of(ImportedPlaylist.readable("sp_pl_1", "Empty", null, null, List.of())));

        ImportService.ImportSummary summary = importService.importFrom(ProviderId.SPOTIFY);

        assertThat(summary.importedCount()).isEqualTo(1);
        assertThat(summary.trackCount()).isZero();
    }

    @Test
    void failsWhenTheServiceIsNotConnected() {
        when(connectionService.credentialsFor(ProviderId.SPOTIFY))
                .thenThrow(new ProviderException(
                        ProviderId.SPOTIFY, ProviderException.Kind.UNAUTHORIZED, "not connected"));

        assertThatThrownBy(() -> importService.importFrom(ProviderId.SPOTIFY))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).requiresReconnect()).isTrue());
    }

    @Test
    void failsForAServiceWithNoProvider() {
        ImportService bare = new ImportService(
                new ProviderRegistry(List.of()), connectionService, repository, clock);

        assertThatThrownBy(() -> bare.importFrom(ProviderId.APPLE_MUSIC))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).getKind())
                        .isEqualTo(ProviderException.Kind.UNSUPPORTED));
    }
}
