package dev.unitedplaylists.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.unitedplaylists.config.LocalAuthFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Simulates what a browser actually does, which is not what the other tests do.
 *
 * <p>Every other integration test here calls the backend with Java's HTTP client,
 * which ignores CORS entirely. That blind spot let a completely broken app pass a
 * green suite: the renderer sends {@code X-UnitedPlaylists-Token}, which makes
 * every call a "non-simple" request, so the browser sends an {@code OPTIONS}
 * preflight first — and a preflight carries no custom headers by design. The auth
 * filter answered 401, the browser abandoned the real request, and the UI reported
 * that the backend could not be reached while the backend was running perfectly.
 *
 * <p>These tests reproduce the preflight by hand. They are the only thing standing
 * between that bug and a future reappearance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:up-cors-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class CorsIntegrationTest {

    private static final String API_TOKEN = "test-api-token";
    private static final String DEV_ORIGIN = "http://localhost:5173";

    @Autowired
    private TestRestTemplate rest;

    /** The exact preflight Chromium sends before a tokened GET. */
    private ResponseEntity<String> preflight(String origin, String requestHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, origin);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders);
        return rest.exchange(
                "/api/v1/playlists", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("the preflight succeeds without the secret, because it cannot carry one")
    void preflightIsNotRejectedForMissingToken() {
        ResponseEntity<String> response = preflight(DEV_ORIGIN, "x-unitedplaylists-token");

        assertThat(response.getStatusCode())
                .as("a 401 here is the bug: the browser gives up and never sends the real request")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("the preflight allows the secret header the client actually sends")
    void preflightAllowsTheTokenHeader() {
        ResponseEntity<String> response = preflight(DEV_ORIGIN, "x-unitedplaylists-token");

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DEV_ORIGIN);
        // Without the header named here, the browser blocks the real request even
        // though the preflight itself succeeded.
        assertThat(response.getHeaders().getAccessControlAllowHeaders())
                .anySatisfy(header ->
                        assertThat(header).containsIgnoringCase(LocalAuthFilter.HEADER));
    }

    @Test
    void preflightAllowsEveryMethodTheClientUses() {
        ResponseEntity<String> response = preflight(DEV_ORIGIN, "content-type");

        assertThat(response.getHeaders().getAccessControlAllowMethods())
                .contains(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE);
    }

    @ParameterizedTest
    @DisplayName("every origin the app legitimately runs from is allowed")
    @ValueSource(strings = {
            "http://localhost:5173",   // Vite dev server
            "http://127.0.0.1:5173",
            "http://localhost:4173",   // Vite preview
            "null",                    // packaged renderer on file://
    })
    void allowsTheAppsOwnOrigins(String origin) {
        ResponseEntity<String> response = preflight(origin, "x-unitedplaylists-token");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(origin);
    }

    @ParameterizedTest
    @DisplayName("a random website is refused at the preflight")
    @ValueSource(strings = {
            "https://evil.example",
            "http://127.0.0.1.evil.example",
            "https://open.spotify.com",
    })
    void refusesForeignOrigins(String origin) {
        ResponseEntity<String> response = preflight(origin, "x-unitedplaylists-token");

        // Either a non-2xx, or no allow-origin header: both stop the browser dead.
        boolean refused = !response.getStatusCode().is2xxSuccessful()
                || response.getHeaders().getAccessControlAllowOrigin() == null;
        assertThat(refused)
                .as("CORS must not hand a foreign page permission to read the user's library")
                .isTrue();
    }

    @Test
    @DisplayName("the real GET carries an allow-origin header the browser will accept")
    void actualRequestIsReadableByTheRenderer() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, DEV_ORIGIN);
        headers.set(LocalAuthFilter.HEADER, API_TOKEN);

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/playlists", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // A 200 without this header is still unreadable to the page: the browser
        // blocks it, and fetch rejects with an opaque failure.
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DEV_ORIGIN);
    }

    @Test
    @DisplayName("CORS does not become a way around the shared secret")
    void corsDoesNotBypassAuthentication() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, DEV_ORIGIN);

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/playlists", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        // Allowed origin, no secret: still refused. Preflight is the only exemption.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
