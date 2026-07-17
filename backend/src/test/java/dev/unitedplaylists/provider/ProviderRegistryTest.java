package dev.unitedplaylists.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.support.FakeProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The extensibility contract from spec 5: a new service is added by contributing a
 * bean, and everything else picks it up without edits.
 */
class ProviderRegistryTest {

    @Test
    void findsARegisteredProvider() {
        FakeProvider spotify = FakeProvider.of(ProviderId.SPOTIFY);
        ProviderRegistry registry = new ProviderRegistry(List.of(spotify));

        assertThat(registry.find(ProviderId.SPOTIFY)).contains(spotify);
        assertThat(registry.require(ProviderId.SPOTIFY)).isSameAs(spotify);
    }

    @Test
    void unregisteredProviderIsEmptyRatherThanNull() {
        ProviderRegistry registry = new ProviderRegistry(List.of(FakeProvider.of(ProviderId.SPOTIFY)));

        assertThat(registry.find(ProviderId.YOUTUBE)).isEmpty();
    }

    @Test
    void requiringAnUnregisteredProviderFailsClearly() {
        ProviderRegistry registry = new ProviderRegistry(List.of());

        assertThatThrownBy(() -> registry.require(ProviderId.APPLE_MUSIC))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).getKind())
                        .isEqualTo(ProviderException.Kind.UNSUPPORTED));
    }

    @Test
    @DisplayName("a duplicate registration fails loudly at startup, not silently at runtime")
    void rejectsTwoProvidersForTheSameService() {
        assertThatThrownBy(() -> new ProviderRegistry(List.of(
                FakeProvider.of(ProviderId.SPOTIFY),
                FakeProvider.of(ProviderId.SPOTIFY))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two providers registered for SPOTIFY");
    }

    @Test
    @DisplayName("a brand new service needs no change to the registry")
    void discoversAnyProviderWithoutRegistryChanges() {
        // Stands in for a future service: implemented from scratch against the
        // interface, and simply handed to the registry.
        MusicProvider brandNew = new MusicProvider() {
            @Override
            public ProviderId id() {
                return ProviderId.APPLE_MUSIC;
            }

            @Override
            public String displayName() {
                return "A Future Service";
            }

            @Override
            public List<ImportedPlaylist> fetchPlaylists(ProviderCredentials credentials) {
                return List.of();
            }

            @Override
            public List<Track> search(ProviderCredentials credentials, String query, int limit) {
                return List.of();
            }

            @Override
            public PlaybackTicket resolvePlayback(ProviderCredentials credentials, TrackRef ref) {
                throw new UnsupportedOperationException();
            }
        };

        ProviderRegistry registry = new ProviderRegistry(List.of(brandNew));

        assertThat(registry.require(ProviderId.APPLE_MUSIC).displayName()).isEqualTo("A Future Service");
        assertThat(registry.available()).containsExactly(brandNew);
    }

    @Test
    @DisplayName("unimplemented services are listed but excluded from available()")
    void separatesAvailableFromRegistered() {
        MusicProvider stub = new dev.unitedplaylists.provider.apple.AppleMusicProvider();
        FakeProvider working = FakeProvider.of(ProviderId.SPOTIFY);
        ProviderRegistry registry = new ProviderRegistry(List.of(working, stub));

        assertThat(registry.all()).hasSize(2);
        assertThat(registry.available()).containsExactly(working);
    }
}
