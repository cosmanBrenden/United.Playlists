package dev.unitedplaylists.provider.oauth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.spotify.SpotifyOAuthClient;
import dev.unitedplaylists.provider.spotify.SpotifyProperties;
import dev.unitedplaylists.support.Fixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class OAuthClientTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String REDIRECT = "http://127.0.0.1:8420/callback";

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private void stubToken(String path, String body) {
        server.stubFor(post(urlPathEqualTo(path))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private static java.util.Map<String, String> queryOf(String url) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();
    }

    @Nested
    @DisplayName("PKCE")
    class PkceTest {

        @Test
        @DisplayName("the challenge is the S256 hash of the verifier, not the verifier itself")
        void challengeIsSha256OfVerifier() throws Exception {
            String verifier = Pkce.generateVerifier();

            String challenge = Pkce.challengeFor(verifier);

            byte[] expected = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertThat(challenge)
                    .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(expected));
            assertThat(challenge).isNotEqualTo(verifier);
        }

        @Test
        @DisplayName("verifiers are unique and long enough to be unguessable")
        void verifiersAreRandomAndWellFormed() {
            Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < 100; i++) {
                seen.add(Pkce.generateVerifier());
            }

            assertThat(seen).hasSize(100);
            // RFC 7636 requires 43-128 characters.
            assertThat(seen).allSatisfy(v -> assertThat(v.length()).isBetween(43, 128));
            assertThat(seen).allSatisfy(v -> assertThat(v).matches("[A-Za-z0-9_-]+"));
        }

        @Test
        void statesAreUnique() {
            Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < 100; i++) {
                seen.add(Pkce.generateState());
            }

            assertThat(seen).hasSize(100);
        }
    }

    @Nested
    @DisplayName("Spotify")
    class Spotify {

        private SpotifyOAuthClient client;

        @BeforeEach
        void setUp() {
            client = new SpotifyOAuthClient(
                    RestClient.builder(),
                    new SpotifyProperties("spotify-client", server.baseUrl(), server.baseUrl(), true),
                    Fixtures.settingsFor(ProviderId.SPOTIFY, "spotify-client", null),
                    CLOCK);
        }

        @Test
        @DisplayName("the authorize URL carries a challenge, never the verifier")
        void buildsAuthorizationUrl() {
            AuthorizationRequest request = client.buildAuthorizationRequest(REDIRECT);

            var params = queryOf(request.authorizationUrl());
            assertThat(params).containsEntry("client_id", "spotify-client");
            assertThat(params).containsEntry("response_type", "code");
            assertThat(params).containsEntry("code_challenge_method", "S256");
            assertThat(params.get("code_challenge")).isEqualTo(Pkce.challengeFor(request.codeVerifier()));
            assertThat(params.get("state")).isEqualTo(request.state());
            // Sending the verifier would defeat the entire point of PKCE.
            assertThat(request.authorizationUrl()).doesNotContain(request.codeVerifier());
        }

        @Test
        void requestsOnlyReadScopes() {
            AuthorizationRequest request = client.buildAuthorizationRequest(REDIRECT);

            String scope = queryOf(request.authorizationUrl()).get("scope");
            assertThat(scope).contains("playlist-read-private");
            // The app never writes to the user's Spotify account.
            assertThat(scope).doesNotContain("playlist-modify");
        }

        @Test
        void exchangesCodeForTokens() {
            stubToken("/api/token", """
                    {"access_token": "BQD-access", "refresh_token": "AQD-refresh",
                     "expires_in": 3600, "scope": "playlist-read-private", "token_type": "Bearer"}
                    """);

            TokenSet tokens = client.exchangeCode("the-code", "the-verifier", REDIRECT);

            assertThat(tokens.accessToken()).isEqualTo("BQD-access");
            assertThat(tokens.refreshToken()).isEqualTo("AQD-refresh");
            // expires_in is relative; it must be resolved against now to be meaningful later.
            assertThat(tokens.expiresAt()).isEqualTo(NOW.plusSeconds(3600));

            server.verify(postRequestedFor(urlPathEqualTo("/api/token"))
                    .withRequestBody(containing("grant_type=authorization_code"))
                    .withRequestBody(containing("code=the-code"))
                    .withRequestBody(containing("code_verifier=the-verifier")));
        }

        @Test
        void refreshesAnAccessToken() {
            stubToken("/api/token", """
                    {"access_token": "BQD-new", "expires_in": 3600, "token_type": "Bearer"}
                    """);

            TokenSet tokens = client.refresh("AQD-refresh");

            assertThat(tokens.accessToken()).isEqualTo("BQD-new");
            // Spotify usually omits refresh_token here; null means "keep the old one".
            assertThat(tokens.refreshToken()).isNull();
            server.verify(postRequestedFor(urlPathEqualTo("/api/token"))
                    .withRequestBody(containing("grant_type=refresh_token")));
        }

        @Test
        void aRevokedRefreshTokenSurfacesAsUnauthorized() {
            server.stubFor(post(urlPathEqualTo("/api/token"))
                    .willReturn(aResponse().withStatus(400)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"invalid_grant\"}")));

            assertThatThrownBy(() -> client.refresh("revoked"))
                    .isInstanceOf(ProviderException.class);
        }

        @Test
        @DisplayName("a 200 with no access_token is treated as a failure, not a success")
        void rejectsAResponseWithoutAnAccessToken() {
            stubToken("/api/token", """
                    {"token_type": "Bearer"}
                    """);

            assertThatThrownBy(() -> client.refresh("some-token"))
                    .isInstanceOf(ProviderException.class)
                    .hasMessageContaining("no access_token");
        }
    }

}
