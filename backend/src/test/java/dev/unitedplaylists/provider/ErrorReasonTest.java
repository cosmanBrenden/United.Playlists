package dev.unitedplaylists.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.spotify.SpotifyProperties;
import dev.unitedplaylists.provider.spotify.SpotifyProvider;
import dev.unitedplaylists.support.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The service's own words must reach the user.
 *
 * <p>Spotify answers several completely different problems with a bare 403 — an app
 * that has not enabled Web API, an account missing from the app's User Management
 * list, a missing scope. The status alone cannot tell them apart; the message can.
 * Burying it in 200 characters of raw JSON, as this used to, made every one of them
 * look the same.
 */
class ErrorReasonTest {

    private WireMockServer server;
    private SpotifyProvider provider;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        provider = new SpotifyProvider(
                RestClient.builder(),
                new SpotifyProperties("cid", server.baseUrl(), server.baseUrl(), true),
                Fixtures.configuredSettings(ProviderId.SPOTIFY));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private void stub(int status, String body) {
        server.stubFor(get(urlPathMatching("/v1/.*")).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    private String messageFrom(int status, String body) {
        stub(status, body);
        try {
            provider.fetchPlaylists(Fixtures.credentials(ProviderId.SPOTIFY));
            throw new AssertionError("expected the call to fail");
        } catch (ProviderException e) {
            return e.getMessage();
        }
    }

    @Test
    @DisplayName("a 403 for an unregistered account says exactly that")
    void surfacesSpotifysOwnReasonForA403() {
        String message = messageFrom(403, """
                {"error": {"status": 403, "message": "User not registered in the Developer Dashboard"}}
                """);

        assertThat(message).contains("User not registered in the Developer Dashboard");
        // The old behaviour dumped raw JSON, which hid the sentence that matters.
        assertThat(message).doesNotContain("{");
        assertThat(message).doesNotContain("\"status\"");
    }

    @Test
    @DisplayName("a 400 for a bad limit says exactly that")
    void surfacesInvalidLimit() {
        String message = messageFrom(400, """
                {"error": {"status": 400, "message": "Invalid limit"}}
                """);

        assertThat(message).contains("Invalid limit");
    }

    @Test
    void surfacesAnExpiredToken() {
        String message = messageFrom(401, """
                {"error": {"status": 401, "message": "The access token expired"}}
                """);

        assertThat(message).contains("The access token expired");
    }

    @Test
    @DisplayName("a Google-shaped error keeps its machine-readable reason too")
    void surfacesGoogleReason() {
        String message = messageFrom(403, """
                {"error": {"code": 403, "message": "The request cannot be completed because you have exceeded your quota.",
                 "errors": [{"reason": "quotaExceeded"}]}}
                """);

        assertThat(message).contains("exceeded your quota");
        // The reason code is what a support search actually turns up.
        assertThat(message).contains("quotaExceeded");
    }

    @Test
    @DisplayName("an OAuth-shaped error is unwrapped as well")
    void surfacesOauthError() {
        String message = messageFrom(400, """
                {"error": "invalid_grant", "error_description": "Refresh token revoked"}
                """);

        assertThat(message).contains("invalid_grant");
        assertThat(message).contains("Refresh token revoked");
    }

    @Test
    @DisplayName("a non-JSON body still produces something readable")
    void survivesANonJsonBody() {
        stub(502, "<html><body>Bad Gateway</body></html>");

        assertThatThrownBy(() -> provider.fetchPlaylists(Fixtures.credentials(ProviderId.SPOTIFY)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("Bad Gateway");
    }

    @Test
    void anEmptyBodyStillNamesTheProviderAndStatus() {
        stub(503, "");

        assertThatThrownBy(() -> provider.fetchPlaylists(Fixtures.credentials(ProviderId.SPOTIFY)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("SPOTIFY");
    }
}
