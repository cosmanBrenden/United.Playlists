package dev.unitedplaylists.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.unitedplaylists.config.LocalAuthFilter;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.service.OAuthFlowService;
import dev.unitedplaylists.service.ProviderSettingsService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The redirect URI the app tells the user to register must be the one it sends.
 *
 * <p>These two came from separate inline defaults that had quietly drifted apart —
 * one said port 842, the other 8420. Nothing caught it, because each was correct in
 * isolation and no test ever compared them. The failure it produces is one of the
 * most confusing in OAuth: the setup screen tells the user to register one URI, the
 * authorize request sends another, and the service rejects the sign-in with an
 * error that quotes neither.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:up-redirect-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class RedirectUriConsistencyTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OAuthFlowService oauthFlowService;

    @Autowired
    private ProviderSettingsService settingsService;

    @BeforeEach
    void configureSpotify() {
        settingsService.save(ProviderId.SPOTIFY, "a-client-id", null);
    }

    /** The redirect URI the setup screen tells the user to register. */
    private String advertisedRedirectUri() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(LocalAuthFilter.HEADER, "test-api-token");
        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/v1/connections/SPOTIFY/setup", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().path("redirectUri").asText();
    }

    /** The redirect_uri actually put in the authorize request. */
    private String sentRedirectUri() {
        String authorizeUrl = oauthFlowService.beginAuthorization(ProviderId.SPOTIFY);
        String raw = UriComponentsBuilder.fromUriString(authorizeUrl).build()
                .getQueryParams().getFirst("redirect_uri");
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("what we advertise is exactly what we send")
    void advertisedAndSentRedirectUrisMatch() {
        assertThat(sentRedirectUri())
                .as("the URI the user is told to register must be the one the app sends, "
                        + "or every sign-in fails with an unhelpful error")
                .isEqualTo(advertisedRedirectUri());
    }

    @Test
    @DisplayName("the setup instructions quote that same URI")
    void instructionsQuoteTheRealRedirectUri() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(LocalAuthFilter.HEADER, "test-api-token");
        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/v1/connections/SPOTIFY/setup", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);

        String instructions = response.getBody().path("instructions").toString();
        assertThat(instructions).contains(sentRedirectUri());
    }

    @Test
    @DisplayName("it is a loopback IP, which is what the services require of native apps")
    void redirectUriIsALoopbackIpNotLocalhost() {
        String uri = sentRedirectUri();

        assertThat(uri).startsWith("http://127.0.0.1:");
        // Spotify rejects "localhost" outright and requires the IP literal; RFC 8252
        // says the same for native apps. Swapping one for the other looks harmless and
        // breaks sign-in.
        assertThat(uri).doesNotContain("localhost");
    }

    @Test
    @DisplayName("the port matches the one the desktop shell listens on")
    void portMatchesTheElectronCallbackListener() {
        // packages/desktop/src/main.js listens on 8420. If these disagree the browser
        // redirects to a port with nothing behind it and the sign-in hangs forever.
        assertThat(sentRedirectUri()).isEqualTo("http://127.0.0.1:8420/callback");
    }
}
