package dev.unitedplaylists.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.unitedplaylists.config.LocalAuthFilter;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * End-to-end over real HTTP, through the auth filter and the real database.
 *
 * <p>Uses a live server rather than MockMvc specifically so the filter is exercised:
 * MockMvc would skip it, and the filter is the app's only access control.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// Its own database. This class drives real HTTP, so its writes commit rather than
// rolling back, and sharing the default in-memory database would leak those rows
// into other test classes and make them order-dependent.
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:up-api-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class PlaylistApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    private HttpHeaders authed() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(LocalAuthFilter.HEADER, API_TOKEN);
        return headers;
    }

    private <T> ResponseEntity<T> exchange(
            HttpMethod method, String path, Object body, Class<T> type) {
        return rest.exchange(path, method, new HttpEntity<>(body, authed()), type);
    }

    private JsonNode createPlaylist(String name) {
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST, "/api/v1/playlists",
                Map.of("name", name, "description", "made in a test"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    @DisplayName("a request without the shared secret is refused")
    void rejectsUnauthenticatedRequests() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/playlists", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAWrongSecret() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(LocalAuthFilter.HEADER, "not-the-token");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/playlists", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // Cross-origin rejection is covered by LocalAuthFilterTest instead: the JDK HTTP
    // client will not set an Origin header (it is on HttpURLConnection's restricted
    // list), so it cannot be exercised from here.

    @Test
    void createsAndReadsBackAPlaylist() {
        JsonNode created = createPlaylist("Integration Playlist");

        assertThat(created.path("name").asText()).isEqualTo("Integration Playlist");
        assertThat(created.path("id").asText()).isNotBlank();
        assertThat(created.path("origin").isNull()).isTrue();
        assertThat(created.path("trackCount").asInt()).isZero();

        ResponseEntity<JsonNode> fetched = exchange(
                HttpMethod.GET, "/api/v1/playlists/" + created.path("id").asText(),
                null, JsonNode.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().path("name").asText()).isEqualTo("Integration Playlist");
    }

    @Test
    void rejectsABlankName() {
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST, "/api/v1/playlists",
                Map.of("name", "   "), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("error").asText()).isEqualTo("validation_failed");
    }

    @Test
    @DisplayName("tracks from different services go into one playlist")
    void addsTracksFromMultipleServices() {
        String id = createPlaylist("Cross Service").path("id").asText();

        exchange(HttpMethod.POST, "/api/v1/playlists/" + id + "/tracks",
                Map.of("trackKey", "SPOTIFY:4iV5W9uYEdYUVa79Axb7Rh",
                        "title", "A Spotify Song",
                        "artists", java.util.List.of("Some Artist"),
                        "durationMs", 210000),
                JsonNode.class);

        ResponseEntity<JsonNode> afterSecond = exchange(
                HttpMethod.POST, "/api/v1/playlists/" + id + "/tracks",
                Map.of("trackKey", "YOUTUBE:dQw4w9WgXcQ",
                        "title", "A YouTube Song",
                        "artists", java.util.List.of("A Channel")),
                JsonNode.class);

        assertThat(afterSecond.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode playlist = afterSecond.getBody();
        assertThat(playlist.path("trackCount").asInt()).isEqualTo(2);

        JsonNode entries = playlist.path("entries");
        assertThat(entries.get(0).path("track").path("provider").asText()).isEqualTo("SPOTIFY");
        assertThat(entries.get(1).path("track").path("provider").asText()).isEqualTo("YOUTUBE");
        // Spec 4's "say which service they came from", surfacing on a playlist too.
        assertThat(playlist.path("providersUsed")).hasSize(2);
    }

    @Test
    void rejectsAMalformedTrackKey() {
        String id = createPlaylist("Bad Keys").path("id").asText();

        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST, "/api/v1/playlists/" + id + "/tracks",
                Map.of("trackKey", "not-a-valid-key", "title", "Nope"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reordersAndRemovesTracks() {
        String id = createPlaylist("Reorder Me").path("id").asText();
        for (String title : java.util.List.of("First", "Second", "Third")) {
            exchange(HttpMethod.POST, "/api/v1/playlists/" + id + "/tracks",
                    Map.of("trackKey", "SPOTIFY:" + title, "title", title), JsonNode.class);
        }

        ResponseEntity<JsonNode> moved = exchange(
                HttpMethod.POST, "/api/v1/playlists/" + id + "/tracks/move",
                Map.of("from", 0, "to", 2), JsonNode.class);

        assertThat(moved.getBody().path("entries")).hasSize(3);
        assertThat(moved.getBody().path("entries").get(2).path("track").path("title").asText())
                .isEqualTo("First");

        ResponseEntity<JsonNode> removed = exchange(
                HttpMethod.DELETE, "/api/v1/playlists/" + id + "/tracks/0", null, JsonNode.class);

        assertThat(removed.getBody().path("trackCount").asInt()).isEqualTo(2);
        assertThat(removed.getBody().path("entries").get(0).path("track").path("title").asText())
                .isEqualTo("Third");
    }

    @Test
    @DisplayName("a track can be replaced in place with the same song on another service")
    void replacesATrackInPlace() {
        String id = createPlaylist("Migrate Me").path("id").asText();
        exchange(HttpMethod.POST, "/api/v1/playlists/" + id + "/tracks",
                Map.of("trackKey", "SPOTIFY:sp1", "title", "Hello",
                        "artists", java.util.List.of("Adele")),
                JsonNode.class);

        ResponseEntity<JsonNode> replaced = exchange(
                HttpMethod.POST, "/api/v1/playlists/" + id + "/tracks/0/replace",
                Map.of("trackKey", "YOUTUBE:yt1", "title", "Hello",
                        "artists", java.util.List.of("Adele"),
                        "expectedKey", "SPOTIFY:sp1"),
                JsonNode.class);

        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entry = replaced.getBody().path("entries").get(0);
        assertThat(entry.path("position").asInt()).isEqualTo(0);
        assertThat(entry.path("track").path("provider").asText()).isEqualTo("YOUTUBE");
        assertThat(replaced.getBody().path("trackCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a replace is refused when the slot no longer holds the expected track")
    void rejectsAStaleReplace() {
        String id = createPlaylist("Raced").path("id").asText();
        exchange(HttpMethod.POST, "/api/v1/playlists/" + id + "/tracks",
                Map.of("trackKey", "SPOTIFY:sp1", "title", "Hello"), JsonNode.class);

        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST, "/api/v1/playlists/" + id + "/tracks/0/replace",
                Map.of("trackKey", "YOUTUBE:yt1", "title", "Hello",
                        "expectedKey", "SPOTIFY:somethingElse"),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().path("error").asText()).isEqualTo("stale_replacement");
    }

    @Test
    @DisplayName("migrating with no service able to match surfaces the track for manual choice")
    void migrateSurfacesUnmatchedTracks() {
        // In the test profile the scrapers are off and nothing is connected, so the
        // target search finds nothing — every track lands in `unresolved` and the
        // playlist is left untouched. That is exactly the "no exact match" path.
        String id = createPlaylist("To Migrate").path("id").asText();
        exchange(HttpMethod.POST, "/api/v1/playlists/" + id + "/tracks",
                Map.of("trackKey", "SPOTIFY:sp1", "title", "Hello",
                        "artists", java.util.List.of("Adele")),
                JsonNode.class);

        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST, "/api/v1/playlists/" + id + "/migrate",
                Map.of("targetProvider", "YOUTUBE"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("target").asText()).isEqualTo("YOUTUBE");
        assertThat(response.getBody().path("replaced")).isEmpty();
        assertThat(response.getBody().path("unresolved")).hasSize(1);
        assertThat(response.getBody().path("unresolved").get(0).path("source").path("title").asText())
                .isEqualTo("Hello");
        // Untouched: still the Spotify original.
        assertThat(response.getBody().path("playlist").path("entries").get(0)
                .path("track").path("provider").asText()).isEqualTo("SPOTIFY");
    }

    @Test
    @DisplayName("migrating requires a target service")
    void migrateRejectsMissingTarget() {
        String id = createPlaylist("No Target").path("id").asText();

        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST, "/api/v1/playlists/" + id + "/migrate",
                Map.of(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void removingAnOutOfRangePositionIsABadRequest() {
        String id = createPlaylist("Empty").path("id").asText();

        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.DELETE, "/api/v1/playlists/" + id + "/tracks/5", null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deletesAPlaylist() {
        String id = createPlaylist("Doomed").path("id").asText();

        ResponseEntity<Void> deleted = exchange(
                HttpMethod.DELETE, "/api/v1/playlists/" + id, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> fetched = exchange(
                HttpMethod.GET, "/api/v1/playlists/" + id, null, JsonNode.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fetched.getBody().path("error").asText()).isEqualTo("playlist_not_found");
    }

    @Test
    void unknownPlaylistIsA404() {
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.GET, "/api/v1/playlists/" + java.util.UUID.randomUUID(),
                null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("search with nothing connected and scrapers off returns empty rather than failing")
    void searchWithNoConnections() {
        // The test profile disables the scraper providers, so with no Spotify connection
        // there is genuinely nothing to search. In production the scrapers would answer.
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.GET, "/api/v1/search?q=anything", null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("results")).isEmpty();
        assertThat(response.getBody().path("partial").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the provider list includes every known service")
    void listsProvidersIncludingUnimplementedOnes() {
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.GET, "/api/v1/connections/providers", null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode providers = response.getBody();
        // Spotify, YouTube, Apple Music, SoundCloud.
        assertThat(providers).hasSize(4);

        JsonNode apple = null;
        for (JsonNode provider : providers) {
            if ("APPLE_MUSIC".equals(provider.path("id").asText())) {
                apple = provider;
            }
        }
        assertThat(apple).isNotNull();
        assertThat(apple.path("available").asBoolean()).isFalse();
        assertThat(apple.path("connected").asBoolean()).isFalse();
        assertThat(apple.path("displayName").asText()).isEqualTo("Apple Music");
    }

    @Test
    @DisplayName("each unavailable service reaches the UI with its own reason")
    void providersCarryTheirOwnUnavailableReason() {
        // The test profile configures client IDs for Spotify and YouTube, so only
        // Apple Music should be unavailable here.
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.GET, "/api/v1/connections/providers", null, JsonNode.class);

        for (JsonNode provider : response.getBody()) {
            String id = provider.path("id").asText();
            if (provider.path("available").asBoolean()) {
                assertThat(provider.path("unavailableReason").isNull())
                        .as("%s is available and should give no reason", id)
                        .isTrue();
            } else {
                String reason = provider.path("unavailableReason").asText();
                assertThat(reason).as("%s must explain why it is unavailable", id).isNotBlank();
                // The regression: an Apple-flavoured excuse shown for every service.
                if (!"APPLE_MUSIC".equals(id)) {
                    assertThat(reason)
                            .as("%s must not blame Apple", id)
                            .doesNotContainIgnoringCase("apple");
                }
            }
        }
    }

    @Test
    @DisplayName("playback for a disconnected service asks for a reconnect")
    void playbackTicketRequiresAConnection() {
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.GET, "/api/v1/playback/ticket?trackKey=SPOTIFY:abc123",
                null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().path("requiresReconnect").asBoolean()).isTrue();
        assertThat(response.getBody().path("provider").asText()).isEqualTo("SPOTIFY");
    }

    @Test
    @DisplayName("the SDK access-token endpoint requires a connected service")
    void accessTokenRequiresAConnection() {
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.GET, "/api/v1/connections/SPOTIFY/access-token", null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().path("requiresReconnect").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the access-token endpoint is not reachable without the shared secret")
    void accessTokenIsProtected() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/connections/SPOTIFY/access-token", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("health is reachable without the secret, so the launcher can poll it")
    void healthIsOpen() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
