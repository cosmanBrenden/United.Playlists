package dev.unitedplaylists.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.spotify.SpotifyProperties;
import dev.unitedplaylists.repository.ProviderSettingRepository;
import dev.unitedplaylists.security.TokenCipher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credentials entered in the app, against a real database.
 *
 * <p>Uses a real context rather than mocks because the point of the feature is that
 * a saved client id is visible to the very next call — which is a question about
 * transactions and storage, not about a service in isolation.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // Stands in for what a developer would export, so the environment-fallback
        // behaviour can be exercised. Only Spotify has such config now; the scraper
        // providers need none.
        "unitedplaylists.spotify.client-id=env-spotify-client",
        "spring.datasource.url=jdbc:h2:mem:up-settings-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
})
class ProviderSettingsServiceTest {

    @Autowired
    private ProviderSettingsService service;

    @Autowired
    private ProviderSettingRepository repository;

    @Autowired
    private TokenCipher cipher;

    @BeforeEach
    void clearStoredSettings() {
        repository.deleteAll();
    }

    @Nested
    @DisplayName("where credentials come from")
    class Precedence {

        @Test
        @DisplayName("falls back to what was configured at startup")
        void environmentIsUsedWhenNothingIsSaved() {
            assertThat(service.clientId(ProviderId.SPOTIFY)).contains("env-spotify-client");
            assertThat(service.sourceOf(ProviderId.SPOTIFY))
                    .isEqualTo(ProviderSettingsService.Source.ENVIRONMENT);
            assertThat(service.isConfigured(ProviderId.SPOTIFY)).isTrue();
        }

        @Test
        @DisplayName("what the user typed beats what the build shipped")
        void appCredentialsOverrideTheEnvironment() {
            service.save(ProviderId.SPOTIFY, "user-entered-client", null);

            assertThat(service.clientId(ProviderId.SPOTIFY)).contains("user-entered-client");
            assertThat(service.sourceOf(ProviderId.SPOTIFY))
                    .isEqualTo(ProviderSettingsService.Source.APP);
        }

        @Test
        @DisplayName("clearing falls back to the startup value rather than to nothing")
        void clearingRevertsToTheEnvironment() {
            service.save(ProviderId.SPOTIFY, "user-entered-client", null);

            service.clear(ProviderId.SPOTIFY);

            assertThat(service.clientId(ProviderId.SPOTIFY)).contains("env-spotify-client");
            assertThat(service.sourceOf(ProviderId.SPOTIFY))
                    .isEqualTo(ProviderSettingsService.Source.ENVIRONMENT);
        }

        @Test
        void unsetServiceReportsNothing() {
            assertThat(service.clientId(ProviderId.APPLE_MUSIC)).isEmpty();
            assertThat(service.isConfigured(ProviderId.APPLE_MUSIC)).isFalse();
            assertThat(service.sourceOf(ProviderId.APPLE_MUSIC))
                    .isEqualTo(ProviderSettingsService.Source.NONE);
        }
    }

    @Nested
    @DisplayName("saving")
    class Saving {

        @Test
        @DisplayName("a saved client id is visible immediately, with no restart")
        void savedCredentialsApplyAtOnce() {
            assertThat(service.clientId(ProviderId.SPOTIFY)).contains("env-spotify-client");

            service.save(ProviderId.SPOTIFY, "brand-new-client", null);

            // The whole point of the feature: the next read sees it.
            assertThat(service.clientId(ProviderId.SPOTIFY)).contains("brand-new-client");
        }

        @Test
        void savingTwiceUpdatesRatherThanDuplicating() {
            service.save(ProviderId.SPOTIFY, "first", null);
            service.save(ProviderId.SPOTIFY, "second", null);

            assertThat(service.clientId(ProviderId.SPOTIFY)).contains("second");
            assertThat(repository.count()).isEqualTo(1);
        }

        @Test
        void rejectsABlankClientId() {
            assertThatThrownBy(() -> service.save(ProviderId.SPOTIFY, "   ", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void trimsSurroundingWhitespaceFromAPastedId() {
            // Copying from a web console routinely brings whitespace along.
            service.save(ProviderId.SPOTIFY, "  padded-client-id  ", null);

            assertThat(service.clientId(ProviderId.SPOTIFY)).contains("padded-client-id");
        }
    }

    @Nested
    @DisplayName("the client secret")
    class Secrets {

        @Test
        @Transactional
        @DisplayName("is encrypted at rest, never stored as typed")
        void secretIsEncryptedInTheDatabase() {
            service.save(ProviderId.APPLE_MUSIC, "a-client", "a-plaintext-secret");

            String stored = repository.findById(ProviderId.APPLE_MUSIC).orElseThrow()
                    .getClientSecretEnc();

            assertThat(stored).isNotEqualTo("a-plaintext-secret");
            assertThat(stored).doesNotContain("a-plaintext-secret");
            assertThat(cipher.decrypt(stored)).isEqualTo("a-plaintext-secret");
        }

        @Test
        void roundTripsThroughTheService() {
            service.save(ProviderId.APPLE_MUSIC, "a-client", "a-plaintext-secret");

            assertThat(service.clientSecret(ProviderId.APPLE_MUSIC)).contains("a-plaintext-secret");
        }

        @Test
        @DisplayName("saving without one means none, not fall back to the build's")
        void anExplicitlyEmptySecretIsNotFilledInFromTheEnvironment() {
            service.save(ProviderId.APPLE_MUSIC, "a-client", null);

            // Otherwise a user who deliberately cleared the secret would silently keep
            // using the one the build shipped, and never understand why.
            assertThat(service.clientSecret(ProviderId.APPLE_MUSIC)).isEmpty();
        }

        @Test
        void blankIsTreatedAsAbsent() {
            service.save(ProviderId.APPLE_MUSIC, "a-client", "   ");

            assertThat(service.clientSecret(ProviderId.APPLE_MUSIC)).isEmpty();
        }

        @Test
        @DisplayName("Spotify legitimately has none")
        void noSecretIsNormal() {
            service.save(ProviderId.SPOTIFY, "a-client", null);

            assertThat(service.clientSecret(ProviderId.SPOTIFY)).isEmpty();
            assertThat(service.clientId(ProviderId.SPOTIFY)).contains("a-client");
        }
    }
}
