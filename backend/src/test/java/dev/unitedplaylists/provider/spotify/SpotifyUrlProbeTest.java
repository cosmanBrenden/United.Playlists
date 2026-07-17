package dev.unitedplaylists.provider.spotify;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Guards the exact bytes that leave for Spotify.
 *
 * <p>This exists because of a bug that every other test missed. The provider built
 * its URL with {@code UriComponentsBuilder.encode()} and handed the result to
 * RestClient as a String — and RestClient encodes a String again. A space became
 * {@code %20} and then {@code %2520}, so a search for "rick astley" asked Spotify
 * for the literal text "rick%20astley" and quietly returned nothing.
 *
 * <p>Nothing caught it because the WireMock stubs matched on path only, and the
 * assertions were about the parsed response. The request itself was never
 * inspected — so these tests inspect it.
 */
class SpotifyUrlProbeTest {

    private WireMockServer server;
    private SpotifyProvider provider;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"tracks\":{\"items\":[]},\"items\":[],\"next\":null}")));
        provider = new SpotifyProvider(
                RestClient.builder(),
                new SpotifyProperties("cid", server.baseUrl(), server.baseUrl(), true),
                Fixtures.configuredSettings(ProviderId.SPOTIFY));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private String urlOf(int index) {
        return server.getAllServeEvents().get(index).getRequest().getUrl();
    }

    private String onlyUrl() {
        assertThat(server.getAllServeEvents()).hasSize(1);
        return urlOf(0);
    }

    @Test
    @DisplayName("a space is encoded once, not twice")
    void searchQueryIsEncodedExactlyOnce() {
        provider.search(Fixtures.credentials(ProviderId.SPOTIFY), "rick astley", 20);

        String url = onlyUrl();
        assertThat(url).contains("q=rick%20astley");
        // %2520 is the signature of double encoding: the '%' of '%20' re-encoded.
        assertThat(url).doesNotContain("%2520");
    }

    @Test
    @DisplayName("ampersands and slashes survive without corrupting the query string")
    void awkwardQueriesAreEncodedSafely() {
        provider.search(Fixtures.credentials(ProviderId.SPOTIFY), "AC/DC & Guns N' Roses", 20);

        String url = onlyUrl();
        assertThat(url).doesNotContain("%2520");
        assertThat(url).doesNotContain("%2526");
        // The '&' must be escaped, or it would split the query and mangle the
        // parameters that follow.
        assertThat(url).contains("%26");
        assertThat(url).contains("type=track");
    }

    @Test
    @DisplayName("the search limit stays inside Spotify's ceiling of 10")
    void searchLimitIsNeverAboveTen() {
        // The February 2026 migration cut this ceiling from 50 to 10. Asking for more
        // is answered with a 400 "Invalid limit" — which is exactly what the app did
        // for every single search, because 20 had been legal for years.
        provider.search(Fixtures.credentials(ProviderId.SPOTIFY), "anything", 500);

        assertThat(onlyUrl()).contains("limit=10");
    }

    @Test
    @DisplayName("the app's default of 20 is capped to what Spotify now accepts")
    void theAppsDefaultLimitIsCapped() {
        provider.search(Fixtures.credentials(ProviderId.SPOTIFY), "anything", 20);

        String url = onlyUrl();
        assertThat(url).contains("limit=10");
        assertThat(url).doesNotContain("limit=20");
    }

    @Test
    void aSmallerLimitIsPassedThroughUntouched() {
        provider.search(Fixtures.credentials(ProviderId.SPOTIFY), "anything", 5);

        assertThat(onlyUrl()).contains("limit=5");
    }

    @Test
    @DisplayName("playlist pages stay inside Spotify's ceiling of 50")
    void playlistPageSizeIsWithinLimits() {
        provider.fetchPlaylists(Fixtures.credentials(ProviderId.SPOTIFY));

        // More than one request now: the profile is fetched first, to work out which
        // playlists are the user's own and therefore readable.
        String url = server.getAllServeEvents().stream()
                .map(event -> event.getRequest().getUrl())
                .filter(candidate -> candidate.startsWith("/v1/me/playlists"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no /v1/me/playlists request"));

        assertThat(url).contains("limit=50");
        assertThat(url).contains("offset=0");
    }

    @Test
    @DisplayName("every request carries the bearer token")
    void requestsAreAuthenticated() {
        provider.search(Fixtures.credentials(ProviderId.SPOTIFY), "anything", 20);

        assertThat(server.getAllServeEvents().get(0).getRequest().getHeader("Authorization"))
                .isEqualTo("Bearer test-access-token");
    }

    @Test
    @DisplayName("no request exceeds the ceiling Spotify currently documents")
    void noRequestExceedsSpotifyLimits() {
        provider.search(Fixtures.credentials(ProviderId.SPOTIFY), "anything", 50);
        provider.fetchPlaylists(Fixtures.credentials(ProviderId.SPOTIFY));

        List<String> urls = server.getAllServeEvents().stream()
                .map(event -> event.getRequest().getUrl())
                // Not every request paginates: /v1/me carries no limit.
                .filter(url -> url.contains("limit="))
                .toList();

        assertThat(urls).isNotEmpty();
        assertThat(urls).allSatisfy(url -> {
            int limit = limitOf(url);
            // Post-February-2026 ceilings: /v1/search caps at 10, everything else at 50.
            // These are lower than they used to be, and exceeding one is a 400 rather
            // than a truncated response, so it takes the whole call down.
            int ceiling = url.startsWith("/v1/search") ? 10 : 50;
            assertThat(limit)
                    .as("limit in %s must be 1..%d or Spotify 400s with \"Invalid limit\"", url, ceiling)
                    .isBetween(1, ceiling);
        });
    }

    @Test
    @DisplayName("playlist items use the endpoint that still exists")
    void playlistItemsUseTheItemsEndpoint() {
        server.stubFor(any(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"next": null, "items": [{"id": "pl1", "name": "P", "images": []}]}
                        """)));

        provider.fetchPlaylists(Fixtures.credentials(ProviderId.SPOTIFY));

        List<String> urls = server.getAllServeEvents().stream()
                .map(event -> event.getRequest().getUrl())
                .toList();

        // "/tracks" was removed outright in February 2026; calling it 404s.
        assertThat(urls).anySatisfy(url -> assertThat(url).contains("/v1/playlists/pl1/items"));
        assertThat(urls).noneSatisfy(url -> assertThat(url).contains("/v1/playlists/pl1/tracks"));
    }

    private int limitOf(String url) {
        for (String param : url.substring(url.indexOf('?') + 1).split("&")) {
            if (param.startsWith("limit=")) {
                return Integer.parseInt(param.substring("limit=".length()));
            }
        }
        throw new AssertionError("no limit parameter in " + url);
    }
}
