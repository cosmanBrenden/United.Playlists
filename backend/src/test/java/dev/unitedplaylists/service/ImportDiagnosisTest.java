package dev.unitedplaylists.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.ImportedPlaylist;
import dev.unitedplaylists.provider.MusicProvider;
import dev.unitedplaylists.provider.PlaybackTicket;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.ProviderRegistry;
import dev.unitedplaylists.repository.PlaylistRepository;
import dev.unitedplaylists.support.Fixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * A bare "403 Forbidden" must not be passed on to the user as-is.
 *
 * <p>Spotify uses that one status, with the single word "Forbidden" and nothing
 * else, for problems whose fixes have nothing in common: a token that was granted
 * fewer permissions than asked for, and an app whose dashboard settings are wrong.
 * Repeating "Forbidden" back to the user leaves them with no way to tell which they
 * have — which is precisely where this app left one.
 *
 * <p>The granted scopes are recorded at sign-in, so the app can tell the difference
 * and should say which it is.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImportDiagnosisTest {

    private static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");

    @Mock
    private ConnectionService connectionService;

    @Mock
    private PlaylistRepository repository;

    private ImportService importService;

    /** A provider that always answers with Spotify's unhelpful 403. */
    private static final class ForbiddenProvider implements MusicProvider {
        @Override
        public ProviderId id() {
            return ProviderId.SPOTIFY;
        }

        @Override
        public String displayName() {
            return "Spotify";
        }

        @Override
        public List<String> requiredScopes() {
            return List.of("playlist-read-private", "playlist-read-collaborative");
        }

        @Override
        public List<ImportedPlaylist> fetchPlaylists(ProviderCredentials credentials) {
            throw new ProviderException(
                    ProviderId.SPOTIFY, ProviderException.Kind.FORBIDDEN,
                    "SPOTIFY: refused — Forbidden");
        }

        @Override
        public List<dev.unitedplaylists.domain.Track> search(
                ProviderCredentials credentials, String query, int limit) {
            return List.of();
        }

        @Override
        public PlaybackTicket resolvePlayback(
                ProviderCredentials credentials, dev.unitedplaylists.domain.TrackRef ref) {
            throw new UnsupportedOperationException();
        }
    }

    @BeforeEach
    void setUp() {
        importService = new ImportService(
                new ProviderRegistry(List.of(new ForbiddenProvider())),
                connectionService,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(connectionService.credentialsFor(ProviderId.SPOTIFY))
                .thenReturn(Fixtures.credentials(ProviderId.SPOTIFY));
        when(repository.findByOrigin(any(), any())).thenReturn(java.util.Optional.empty());
    }

    @Test
    @DisplayName("a missing permission is named, along with the fix")
    void namesTheMissingScope() {
        when(connectionService.grantedScopesFor(ProviderId.SPOTIFY))
                .thenReturn(List.of("streaming"));

        assertThatThrownBy(() -> importService.importFrom(ProviderId.SPOTIFY))
                .isInstanceOf(ProviderException.class)
                .satisfies(thrown -> {
                    String message = thrown.getMessage();
                    assertThat(message).contains("playlist-read-private");
                    assertThat(message).contains("Disconnect Spotify and connect again");
                    // Spotify's own word is kept: it is the ground truth, however useless.
                    assertThat(message).contains("Forbidden");
                });
    }

    @Test
    @DisplayName("when nothing is missing, the token is ruled out and the dashboard implicated")
    void rulesOutTheTokenWhenScopesAreComplete() {
        when(connectionService.grantedScopesFor(ProviderId.SPOTIFY))
                .thenReturn(List.of("playlist-read-private", "playlist-read-collaborative", "streaming"));

        assertThatThrownBy(() -> importService.importFrom(ProviderId.SPOTIFY))
                .isInstanceOf(ProviderException.class)
                .satisfies(thrown -> {
                    String message = thrown.getMessage();
                    // The useful half: this tells the user to stop reconnecting, which
                    // will never work, and go look at the dashboard instead.
                    assertThat(message).contains("not sign-in");
                    assertThat(message).contains("Web API is enabled");
                    assertThat(message).contains("allowed-users list");
                    assertThat(message).doesNotContain("Disconnect Spotify and connect again");
                });
    }

    @Test
    @DisplayName("a token that granted nothing says so plainly")
    void handlesAnEmptyGrant() {
        when(connectionService.grantedScopesFor(ProviderId.SPOTIFY)).thenReturn(List.of());

        assertThatThrownBy(() -> importService.importFrom(ProviderId.SPOTIFY))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("it only granted nothing");
    }

    @Test
    @DisplayName("the diagnosis stays a 403, so the UI still treats it as refused")
    void keepsTheKind() {
        when(connectionService.grantedScopesFor(ProviderId.SPOTIFY)).thenReturn(List.of());

        assertThatThrownBy(() -> importService.importFrom(ProviderId.SPOTIFY))
                .satisfies(thrown -> assertThat(((ProviderException) thrown).getKind())
                        .isEqualTo(ProviderException.Kind.FORBIDDEN));
    }

    @Test
    @DisplayName("failures that are not 403 are left exactly as they are")
    void doesNotTouchOtherFailures() {
        ImportService service = new ImportService(
                new ProviderRegistry(List.of(new MusicProvider() {
                    @Override
                    public ProviderId id() {
                        return ProviderId.SPOTIFY;
                    }

                    @Override
                    public String displayName() {
                        return "Spotify";
                    }

                    @Override
                    public List<ImportedPlaylist> fetchPlaylists(ProviderCredentials credentials) {
                        throw new ProviderException(
                                ProviderId.SPOTIFY, ProviderException.Kind.RATE_LIMITED,
                                "SPOTIFY: rate limited");
                    }

                    @Override
                    public List<dev.unitedplaylists.domain.Track> search(
                            ProviderCredentials credentials, String query, int limit) {
                        return List.of();
                    }

                    @Override
                    public PlaybackTicket resolvePlayback(
                            ProviderCredentials credentials, dev.unitedplaylists.domain.TrackRef ref) {
                        throw new UnsupportedOperationException();
                    }
                })),
                connectionService, repository, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.importFrom(ProviderId.SPOTIFY))
                .hasMessage("SPOTIFY: rate limited");
    }
}
