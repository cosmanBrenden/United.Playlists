package dev.unitedplaylists.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.oauth.AuthorizationRequest;
import dev.unitedplaylists.provider.oauth.OAuthClient;
import dev.unitedplaylists.provider.oauth.OAuthProperties;
import dev.unitedplaylists.provider.oauth.Pkce;
import dev.unitedplaylists.provider.oauth.TokenSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The state check here is security-critical, so it gets tested from both sides:
 * the happy path, and every way a callback can fail to prove it started here.
 */
@ExtendWith(MockitoExtension.class)
class OAuthFlowServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final String REDIRECT = "http://127.0.0.1:8420/callback";

    @Mock
    private ConnectionService connectionService;

    private RecordingOAuthClient client;
    private MutableClock clock;
    private OAuthFlowService service;

    /** A clock the test can advance, to exercise expiry without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Captures what it was handed, so the test can assert on the PKCE verifier. */
    private static final class RecordingOAuthClient implements OAuthClient {
        String issuedState;
        String issuedVerifier;
        String receivedCode;
        String receivedVerifier;

        @Override
        public ProviderId provider() {
            return ProviderId.SPOTIFY;
        }

        @Override
        public AuthorizationRequest buildAuthorizationRequest(String redirectUri) {
            issuedState = Pkce.generateState();
            issuedVerifier = Pkce.generateVerifier();
            return new AuthorizationRequest(
                    "https://accounts.spotify.com/authorize?state=" + issuedState,
                    issuedState,
                    issuedVerifier);
        }

        @Override
        public TokenSet exchangeCode(String code, String codeVerifier, String redirectUri) {
            receivedCode = code;
            receivedVerifier = codeVerifier;
            return new TokenSet("new-access", "new-refresh", NOW.plusSeconds(3600), "scope");
        }

        @Override
        public TokenSet refresh(String refreshToken) {
            throw new UnsupportedOperationException();
        }
    }

    @BeforeEach
    void setUp() {
        client = new RecordingOAuthClient();
        clock = new MutableClock(NOW);
        service = new OAuthFlowService(
                List.of(client), connectionService, clock, new OAuthProperties(REDIRECT));
    }

    @Test
    void returnsAnAuthorizationUrlToOpen() {
        String url = service.beginAuthorization(ProviderId.SPOTIFY);

        assertThat(url).startsWith("https://accounts.spotify.com/authorize");
        assertThat(url).contains(client.issuedState);
    }

    @Test
    @DisplayName("a matching callback exchanges the code and stores the connection")
    void completesTheHappyPath() {
        service.beginAuthorization(ProviderId.SPOTIFY);

        service.completeAuthorization(ProviderId.SPOTIFY, "auth-code-123", client.issuedState);

        assertThat(client.receivedCode).isEqualTo("auth-code-123");
        // The verifier must be the one paired with the challenge sent to Spotify;
        // PKCE is worthless if a fresh one is generated here.
        assertThat(client.receivedVerifier).isEqualTo(client.issuedVerifier);
        verify(connectionService).connect(
                eq(ProviderId.SPOTIFY), any(TokenSet.class), any(), any());
    }

    @Test
    @DisplayName("a callback with the wrong state is refused")
    void rejectsMismatchedState() {
        service.beginAuthorization(ProviderId.SPOTIFY);

        assertThatThrownBy(() -> service.completeAuthorization(
                ProviderId.SPOTIFY, "attacker-code", "not-the-right-state"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("state did not match");

        verify(connectionService, never()).connect(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a callback that no flow started is refused")
    void rejectsUnsolicitedCallback() {
        assertThatThrownBy(() -> service.completeAuthorization(
                ProviderId.SPOTIFY, "code", "state"))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("No authorization is in progress");

        verify(connectionService, never()).connect(any(), any(), any(), any());
    }

    @Test
    void rejectsAnExpiredAuthorization() {
        service.beginAuthorization(ProviderId.SPOTIFY);
        clock.advance(Duration.ofMinutes(11));

        assertThatThrownBy(() -> service.completeAuthorization(
                ProviderId.SPOTIFY, "code", client.issuedState))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("timed out");

        verify(connectionService, never()).connect(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a state cannot be replayed after it is used once")
    void consumesPendingStateOnUse() {
        service.beginAuthorization(ProviderId.SPOTIFY);
        String state = client.issuedState;
        service.completeAuthorization(ProviderId.SPOTIFY, "code", state);

        assertThatThrownBy(() -> service.completeAuthorization(ProviderId.SPOTIFY, "code", state))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("No authorization is in progress");
    }

    @Test
    @DisplayName("restarting a flow invalidates the previous attempt's state")
    void restartingSupersedesTheOldState() {
        service.beginAuthorization(ProviderId.SPOTIFY);
        String firstState = client.issuedState;
        service.beginAuthorization(ProviderId.SPOTIFY);

        assertThatThrownBy(() -> service.completeAuthorization(
                ProviderId.SPOTIFY, "code", firstState))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("state did not match");
    }

    @Test
    void rejectsAServiceWithNoOAuthClient() {
        assertThatThrownBy(() -> service.beginAuthorization(ProviderId.APPLE_MUSIC))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(((ProviderException) e).getKind())
                        .isEqualTo(ProviderException.Kind.UNSUPPORTED));
    }

    @Test
    void evictsExpiredPendingAuthorizations() {
        service.beginAuthorization(ProviderId.SPOTIFY);
        clock.advance(Duration.ofMinutes(11));

        service.evictExpired();

        assertThatThrownBy(() -> service.completeAuthorization(
                ProviderId.SPOTIFY, "code", client.issuedState))
                .hasMessageContaining("No authorization is in progress");
    }
}
