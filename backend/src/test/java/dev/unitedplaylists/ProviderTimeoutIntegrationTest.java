package dev.unitedplaylists;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.spotify.SpotifyProvider;
import dev.unitedplaylists.support.Fixtures;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves outbound HTTP timeouts are actually applied to providers.
 *
 * <p>This exists because the timeouts are configured rather than coded:
 * {@code spring.http.client.read-timeout} is applied by Spring Boot to the
 * auto-configured {@code RestClient.Builder}, several layers away from the
 * provider that depends on it. A typo in the property name, or a future Boot
 * upgrade moving it, would silently restore "wait forever" — and nothing else in
 * the suite would notice, because every other test builds its own
 * {@code RestClient} by hand.
 *
 * <p>An unbounded provider call is not a cosmetic problem: it parks a search
 * thread forever and the user's search never returns.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // Deliberately tiny, so the test asserts on the timeout firing rather than
        // on how patient the suite is willing to be.
        "spring.http.client.read-timeout=250ms",
        "spring.http.client.connect-timeout=250ms",
})
class ProviderTimeoutIntegrationTest {

    private static WireMockServer server;

    @Autowired
    private SpotifyProvider spotifyProvider;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        // Slower than the configured read timeout by a wide margin.
        server.stubFor(get(urlPathMatching("/v1/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"tracks\":{\"items\":[]}}")
                        .withFixedDelay(5000)));
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    /** Points the real, auto-configured provider bean at the slow stub. */
    @DynamicPropertySource
    static void spotifyPointsAtWireMock(DynamicPropertyRegistry registry) {
        registry.add("unitedplaylists.spotify.api-base-url", () -> server.baseUrl());
        registry.add("unitedplaylists.spotify.auth-base-url", () -> server.baseUrl());
    }

    @Test
    @DisplayName("a provider call gives up instead of hanging forever")
    void readTimeoutIsAppliedToProviders() {
        long startedAt = System.currentTimeMillis();

        assertThatThrownBy(() ->
                spotifyProvider.search(Fixtures.credentials(ProviderId.SPOTIFY), "anything", 10))
                .isInstanceOf(ProviderException.class)
                .satisfies(thrown -> {
                    ProviderException e = (ProviderException) thrown;
                    assertThat(e.getKind()).isEqualTo(ProviderException.Kind.UNAVAILABLE);
                    assertThat(e.getProvider()).isEqualTo(ProviderId.SPOTIFY);
                    // A timeout is not the user's fault and reconnecting will not help.
                    assertThat(e.requiresReconnect()).isFalse();
                });

        Duration elapsed = Duration.ofMillis(System.currentTimeMillis() - startedAt);
        // The stub sleeps 5s. Returning well before that is the proof the timeout
        // fired rather than the request simply completing.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
    }
}
