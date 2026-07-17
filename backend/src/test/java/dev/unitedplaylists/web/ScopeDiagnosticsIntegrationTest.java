package dev.unitedplaylists.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.unitedplaylists.config.LocalAuthFilter;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.oauth.TokenSet;
import dev.unitedplaylists.service.ConnectionService;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Reproduces a real, badly-diagnosable failure: Spotify hands back a token without
 * {@code playlist-read-private}.
 *
 * <p>The symptom is maddening. Search keeps working, because searching the catalogue
 * needs no scope at all. Import fails with a bare 403 that names no scope. Nothing
 * distinguishes it from a dashboard misconfiguration, and the only fix — reconnect
 * and approve everything — is the one thing the app never suggests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:up-scope-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class ScopeDiagnosticsIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ConnectionService connectionService;

    private JsonNode spotify() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(LocalAuthFilter.HEADER, "test-api-token");
        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/v1/connections/providers", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        for (JsonNode provider : response.getBody()) {
            if ("SPOTIFY".equals(provider.path("id").asText())) {
                return provider;
            }
        }
        throw new AssertionError("Spotify missing from the provider list");
    }

    private void connectWithScopes(String scopes) {
        connectionService.connect(
                ProviderId.SPOTIFY,
                new TokenSet("an-access-token", "a-refresh-token",
                        Instant.now().plusSeconds(3600), scopes),
                "user@example.invalid",
                "US");
    }

    @Test
    @DisplayName("a token missing the playlist scope is reported, not left to fail later")
    void missingScopeIsSurfaced() {
        // What Spotify returns when the user was not asked for, or declined, the
        // playlist permissions.
        connectWithScopes("user-read-email user-read-private streaming");

        JsonNode spotify = spotify();

        assertThat(spotify.path("connected").asBoolean()).isTrue();
        assertThat(spotify.path("missingScopes")).isNotEmpty();
        assertThat(spotify.path("missingScopes").toString()).contains("playlist-read-private");
        assertThat(spotify.path("grantedScopes").toString()).contains("streaming");
    }

    @Test
    @DisplayName("a fully-granted token reports nothing missing")
    void fullyGrantedTokenIsClean() {
        connectWithScopes(
                "playlist-read-private playlist-read-collaborative user-read-email streaming");

        JsonNode spotify = spotify();

        assertThat(spotify.path("missingScopes")).isEmpty();
        assertThat(spotify.path("grantedScopes").toString()).contains("playlist-read-private");
    }

    @Test
    @DisplayName("a token with no scope string at all is treated as granting nothing")
    void emptyScopeStringMeansNothingGranted() {
        connectWithScopes(null);

        JsonNode spotify = spotify();

        assertThat(spotify.path("missingScopes").toString()).contains("playlist-read-private");
        assertThat(spotify.path("grantedScopes")).isEmpty();
    }

    @Test
    @DisplayName("Premium-only playback scope is not treated as required")
    void streamingScopeIsNotRequired() {
        // A free account cannot get "streaming". It must still be able to import.
        connectWithScopes("playlist-read-private playlist-read-collaborative");

        assertThat(spotify().path("missingScopes")).isEmpty();
    }

    @Test
    void disconnectedServiceReportsNoScopes() {
        connectionService.disconnect(ProviderId.SPOTIFY);

        JsonNode spotify = spotify();

        assertThat(spotify.path("connected").asBoolean()).isFalse();
        assertThat(spotify.path("grantedScopes")).isEmpty();
        assertThat(spotify.path("missingScopes")).isEmpty();
    }
}
