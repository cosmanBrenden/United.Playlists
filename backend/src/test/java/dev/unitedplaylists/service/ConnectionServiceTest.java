package dev.unitedplaylists.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.ServiceConnection;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.oauth.OAuthClient;
import dev.unitedplaylists.provider.oauth.AuthorizationRequest;
import dev.unitedplaylists.provider.oauth.TokenSet;
import dev.unitedplaylists.repository.ServiceConnectionRepository;
import dev.unitedplaylists.security.TokenCipher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Mock
    private ServiceConnectionRepository repository;

    private TokenCipher cipher;
    private StubOAuthClient oauthClient;
    private ConnectionService service;

    /** Returns a scripted token set, or throws if told to. */
    private static final class StubOAuthClient implements OAuthClient {
        int refreshCalls;
        TokenSet next = new TokenSet("refreshed-access", "refreshed-refresh",
                NOW.plusSeconds(3600), "scope");
        RuntimeException failWith;

        @Override
        public ProviderId provider() {
            return ProviderId.SPOTIFY;
        }

        @Override
        public AuthorizationRequest buildAuthorizationRequest(String redirectUri) {
            return new AuthorizationRequest("https://example.invalid/authorize", "state", "verifier");
        }

        @Override
        public TokenSet exchangeCode(String code, String codeVerifier, String redirectUri) {
            return next;
        }

        @Override
        public TokenSet refresh(String refreshToken) {
            refreshCalls++;
            if (failWith != null) {
                throw failWith;
            }
            return next;
        }
    }

    @BeforeEach
    void setUp() {
        cipher = new TokenCipher(TokenCipher.generateKey());
        oauthClient = new StubOAuthClient();
        service = new ConnectionService(
                repository, cipher, List.of(oauthClient), Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.save(any(ServiceConnection.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ServiceConnection connection(Instant expiresAt, boolean withRefreshToken) {
        return new ServiceConnection(
                ProviderId.SPOTIFY,
                cipher.encrypt("current-access-token"),
                withRefreshToken ? cipher.encrypt("current-refresh-token") : null,
                expiresAt,
                "scope",
                "user@example.invalid",
                "US",
                NOW.minusSeconds(86400));
    }

    @Nested
    @DisplayName("storing a connection")
    class Storing {

        @Test
        @DisplayName("tokens are encrypted before they reach the repository")
        void encryptsTokensOnConnect() {
            TokenSet tokens = new TokenSet(
                    "plain-access", "plain-refresh", NOW.plusSeconds(3600), "scope");

            ServiceConnection saved = service.connect(
                    ProviderId.SPOTIFY, tokens, "user@example.invalid", "GB");

            assertThat(saved.getAccessTokenEnc()).isNotEqualTo("plain-access");
            assertThat(saved.getRefreshTokenEnc()).isNotEqualTo("plain-refresh");
            assertThat(cipher.decrypt(saved.getAccessTokenEnc())).isEqualTo("plain-access");
            assertThat(cipher.decrypt(saved.getRefreshTokenEnc())).isEqualTo("plain-refresh");
            assertThat(saved.getMarket()).isEqualTo("GB");
        }

        @Test
        void disconnectRemovesTheConnection() {
            service.disconnect(ProviderId.SPOTIFY);

            verify(repository).deleteById(ProviderId.SPOTIFY);
        }
    }

    @Nested
    @DisplayName("supplying credentials")
    class Credentials {

        @Test
        void decryptsAValidTokenWithoutRefreshing() {
            when(repository.findById(ProviderId.SPOTIFY))
                    .thenReturn(Optional.of(connection(NOW.plusSeconds(3600), true)));

            ProviderCredentials credentials = service.credentialsFor(ProviderId.SPOTIFY);

            assertThat(credentials.accessToken()).isEqualTo("current-access-token");
            assertThat(credentials.userMarket()).isEqualTo("US");
            assertThat(oauthClient.refreshCalls).isZero();
        }

        @Test
        void failsWhenTheServiceIsNotConnected() {
            when(repository.findById(ProviderId.SPOTIFY)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.credentialsFor(ProviderId.SPOTIFY))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(e -> assertThat(((ProviderException) e).requiresReconnect()).isTrue());
        }
    }

    @Nested
    @DisplayName("refreshing")
    class Refreshing {

        @Test
        void refreshesAnExpiredToken() {
            when(repository.findById(ProviderId.SPOTIFY))
                    .thenReturn(Optional.of(connection(NOW.minusSeconds(60), true)));

            ProviderCredentials credentials = service.credentialsFor(ProviderId.SPOTIFY);

            assertThat(oauthClient.refreshCalls).isEqualTo(1);
            assertThat(credentials.accessToken()).isEqualTo("refreshed-access");
        }

        @Test
        @DisplayName("a token expiring within the skew window is refreshed pre-emptively")
        void refreshesWithinTheSkewWindow() {
            // Valid for another 30s: still live, but a request started now could land
            // after it dies, so it is refreshed rather than gambled on.
            when(repository.findById(ProviderId.SPOTIFY))
                    .thenReturn(Optional.of(connection(NOW.plusSeconds(30), true)));

            service.credentialsFor(ProviderId.SPOTIFY);

            assertThat(oauthClient.refreshCalls).isEqualTo(1);
        }

        @Test
        void doesNotRefreshWellBeforeExpiry() {
            when(repository.findById(ProviderId.SPOTIFY))
                    .thenReturn(Optional.of(connection(NOW.plusSeconds(600), true)));

            service.credentialsFor(ProviderId.SPOTIFY);

            assertThat(oauthClient.refreshCalls).isZero();
        }

        @Test
        @DisplayName("a service that does not rotate its refresh token keeps the old one")
        void keepsTheExistingRefreshTokenWhenNoneIsReturned() {
            ServiceConnection stored = connection(NOW.minusSeconds(60), true);
            when(repository.findById(ProviderId.SPOTIFY)).thenReturn(Optional.of(stored));
            // Spotify usually omits refresh_token on a refresh response.
            oauthClient.next = new TokenSet("new-access", null, NOW.plusSeconds(3600), "scope");

            service.credentialsFor(ProviderId.SPOTIFY);

            assertThat(cipher.decrypt(stored.getRefreshTokenEnc())).isEqualTo("current-refresh-token");
        }

        @Test
        void adoptsARotatedRefreshToken() {
            ServiceConnection stored = connection(NOW.minusSeconds(60), true);
            when(repository.findById(ProviderId.SPOTIFY)).thenReturn(Optional.of(stored));
            oauthClient.next = new TokenSet(
                    "new-access", "rotated-refresh", NOW.plusSeconds(3600), "scope");

            service.credentialsFor(ProviderId.SPOTIFY);

            assertThat(cipher.decrypt(stored.getRefreshTokenEnc())).isEqualTo("rotated-refresh");
        }

        @Test
        @DisplayName("an expired token with no refresh token asks for a reconnect")
        void cannotRefreshWithoutARefreshToken() {
            when(repository.findById(ProviderId.SPOTIFY))
                    .thenReturn(Optional.of(connection(NOW.minusSeconds(60), false)));

            assertThatThrownBy(() -> service.credentialsFor(ProviderId.SPOTIFY))
                    .isInstanceOf(ProviderException.class)
                    .satisfies(e -> assertThat(((ProviderException) e).requiresReconnect()).isTrue());
            verify(repository, never()).save(any(ServiceConnection.class));
        }

        @Test
        void aRevokedRefreshTokenPropagatesAsReconnectRequired() {
            when(repository.findById(ProviderId.SPOTIFY))
                    .thenReturn(Optional.of(connection(NOW.minusSeconds(60), true)));
            oauthClient.failWith = new ProviderException(
                    ProviderId.SPOTIFY, ProviderException.Kind.UNAUTHORIZED, "refresh token revoked");

            assertThatThrownBy(() -> service.credentialsFor(ProviderId.SPOTIFY))
                    .isInstanceOf(ProviderException.class)
                    .hasMessageContaining("revoked");
        }
    }

    @Nested
    @DisplayName("activeCredentials across services")
    class ActiveCredentials {

        @Test
        @DisplayName("one broken connection does not hide the working ones")
        void skipsUnusableConnectionsRatherThanFailing() {
            ServiceConnection healthy = connection(NOW.plusSeconds(3600), true);
            // Expired with no way to refresh: unusable.
            ServiceConnection broken = new ServiceConnection(
                    ProviderId.YOUTUBE,
                    cipher.encrypt("yt-access"),
                    null,
                    NOW.minusSeconds(60),
                    "scope", "yt@example.invalid", "US", NOW.minusSeconds(86400));
            when(repository.findAll()).thenReturn(List.of(healthy, broken));

            List<ProviderCredentials> active = service.activeCredentials();

            assertThat(active).singleElement().satisfies(c -> {
                assertThat(c.provider()).isEqualTo(ProviderId.SPOTIFY);
                assertThat(c.accessToken()).isEqualTo("current-access-token");
            });
        }

        @Test
        @DisplayName("a token encrypted under a lost key is skipped, not fatal")
        void skipsUndecryptableConnections() {
            ServiceConnection corrupt = new ServiceConnection(
                    ProviderId.SPOTIFY,
                    new TokenCipher(TokenCipher.generateKey()).encrypt("written-with-another-key"),
                    null,
                    NOW.plusSeconds(3600),
                    "scope", "user", "US", NOW);
            when(repository.findAll()).thenReturn(List.of(corrupt));

            assertThat(service.activeCredentials()).isEmpty();
        }

        @Test
        void noConnectionsYieldsEmpty() {
            when(repository.findAll()).thenReturn(List.of());

            assertThat(service.activeCredentials()).isEmpty();
        }
    }

    @Nested
    @DisplayName("status listing")
    class StatusListing {

        @Test
        @DisplayName("status carries no token material")
        void listsConnectionsWithoutSecrets() {
            when(repository.findAll()).thenReturn(List.of(connection(NOW.plusSeconds(3600), true)));

            List<ConnectionService.ConnectionStatus> statuses = service.listConnections();

            assertThat(statuses).singleElement().satisfies(status -> {
                assertThat(status.provider()).isEqualTo(ProviderId.SPOTIFY);
                assertThat(status.accountLabel()).isEqualTo("user@example.invalid");
                assertThat(status.needsReconnect()).isFalse();
            });
            assertThat(statuses.toString()).doesNotContain("access-token", "refresh-token");
        }

        @Test
        void flagsAConnectionThatCannotRecover() {
            when(repository.findAll()).thenReturn(List.of(connection(NOW.minusSeconds(60), false)));

            assertThat(service.listConnections()).singleElement()
                    .satisfies(status -> assertThat(status.needsReconnect()).isTrue());
        }

        @Test
        void isConnectedReflectsTheRepository() {
            when(repository.existsById(ProviderId.SPOTIFY)).thenReturn(true);
            when(repository.existsById(ProviderId.YOUTUBE)).thenReturn(false);

            assertThat(service.isConnected(ProviderId.SPOTIFY)).isTrue();
            assertThat(service.isConnected(ProviderId.YOUTUBE)).isFalse();
        }
    }
}
