package dev.unitedplaylists.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.ProviderRegistry;
import dev.unitedplaylists.support.FakeProvider;
import dev.unitedplaylists.support.Fixtures;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The aggregation contract from spec 4: one search, every connected service,
 * results labelled with where they came from.
 *
 * <p>The partial-failure tests are the important ones. With three services
 * connected, any of them can be down or rate-limited at any moment, and a search
 * that fails whole because Spotify hiccuped would make the app feel broken far
 * more often than it actually is.
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private ConnectionService connectionService;

    private SearchService searchService;
    private SearchService.SearchExecutor executor;

    private SearchService build(FakeProvider... providers) {
        ProviderRegistry registry = new ProviderRegistry(List.of(providers));
        executor = new SearchService.SearchExecutor();
        searchService = new SearchService(registry, connectionService, executor, Duration.ofSeconds(5));
        return searchService;
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    private void connected(ProviderId... ids) {
        List<ProviderCredentials> creds = java.util.Arrays.stream(ids)
                .map(Fixtures::credentials)
                .toList();
        when(connectionService.activeCredentials()).thenReturn(creds);
    }

    @Test
    @DisplayName("fans out to every connected service and merges the results")
    void searchesAllConnectedServices() {
        FakeProvider spotify = FakeProvider.of(ProviderId.SPOTIFY).returningTracks("Alpha", "Beta");
        FakeProvider youtube = FakeProvider.of(ProviderId.YOUTUBE).returningTracks("Gamma");
        build(spotify, youtube);
        connected(ProviderId.SPOTIFY, ProviderId.YOUTUBE);

        SearchService.SearchResults results = searchService.searchAll("anything", 10);

        assertThat(results.tracks()).hasSize(3);
        assertThat(results.tracks()).extracting(Track::title)
                .containsExactlyInAnyOrder("Alpha", "Beta", "Gamma");
        assertThat(spotify.searchCallCount()).isEqualTo(1);
        assertThat(youtube.searchCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("every result states which service it came from")
    void resultsAreTaggedWithTheirProvider() {
        build(
                FakeProvider.of(ProviderId.SPOTIFY).returningTracks("Alpha"),
                FakeProvider.of(ProviderId.YOUTUBE).returningTracks("Gamma"));
        connected(ProviderId.SPOTIFY, ProviderId.YOUTUBE);

        SearchService.SearchResults results = searchService.searchAll("anything", 10);

        assertThat(results.tracks())
                .allSatisfy(track -> assertThat(track.provider()).isNotNull());
        assertThat(results.tracks())
                .filteredOn(t -> t.title().equals("Alpha"))
                .singleElement()
                .satisfies(t -> assertThat(t.provider()).isEqualTo(ProviderId.SPOTIFY));
        assertThat(results.tracks())
                .filteredOn(t -> t.title().equals("Gamma"))
                .singleElement()
                .satisfies(t -> assertThat(t.provider()).isEqualTo(ProviderId.YOUTUBE));
    }

    @Test
    @DisplayName("skips services the user has not connected")
    void doesNotQueryUnconnectedServices() {
        FakeProvider spotify = FakeProvider.of(ProviderId.SPOTIFY).returningTracks("Alpha");
        FakeProvider youtube = FakeProvider.of(ProviderId.YOUTUBE).returningTracks("Gamma");
        build(spotify, youtube);
        connected(ProviderId.SPOTIFY);

        SearchService.SearchResults results = searchService.searchAll("anything", 10);

        assertThat(results.tracks()).extracting(Track::title).containsExactly("Alpha");
        assertThat(youtube.searchCallCount()).isZero();
    }

    @Test
    @DisplayName("one failing service does not sink the whole search")
    void survivesPartialProviderFailure() {
        build(
                FakeProvider.of(ProviderId.SPOTIFY).returningTracks("Alpha"),
                FakeProvider.of(ProviderId.YOUTUBE).failingWith(ProviderException.Kind.RATE_LIMITED));
        connected(ProviderId.SPOTIFY, ProviderId.YOUTUBE);

        SearchService.SearchResults results = searchService.searchAll("anything", 10);

        assertThat(results.tracks()).extracting(Track::title).containsExactly("Alpha");
        assertThat(results.failures()).singleElement().satisfies(f -> {
            assertThat(f.provider()).isEqualTo(ProviderId.YOUTUBE);
            assertThat(f.kind()).isEqualTo(ProviderException.Kind.RATE_LIMITED);
        });
        assertThat(results.isPartial()).isTrue();
    }

    @Test
    @DisplayName("an unexpected exception is contained like any other failure")
    void containsUnexpectedExceptions() {
        build(
                FakeProvider.of(ProviderId.SPOTIFY).returningTracks("Alpha"),
                FakeProvider.of(ProviderId.YOUTUBE).failingWithRuntimeException());
        connected(ProviderId.SPOTIFY, ProviderId.YOUTUBE);

        SearchService.SearchResults results = searchService.searchAll("anything", 10);

        assertThat(results.tracks()).extracting(Track::title).containsExactly("Alpha");
        assertThat(results.failures()).singleElement().satisfies(f -> {
            assertThat(f.provider()).isEqualTo(ProviderId.YOUTUBE);
            assertThat(f.kind()).isEqualTo(ProviderException.Kind.UNAVAILABLE);
        });
    }

    @Test
    @DisplayName("a slow service is cut off rather than holding up the rest")
    void timesOutSlowProviders() {
        ProviderRegistry registry = new ProviderRegistry(List.of(
                FakeProvider.of(ProviderId.SPOTIFY).returningTracks("Alpha"),
                FakeProvider.of(ProviderId.YOUTUBE)
                        .returningTracks("Gamma")
                        .slowBy(Duration.ofSeconds(3))));
        executor = new SearchService.SearchExecutor();
        searchService = new SearchService(
                registry, connectionService, executor, Duration.ofMillis(150));
        connected(ProviderId.SPOTIFY, ProviderId.YOUTUBE);

        SearchService.SearchResults results = searchService.searchAll("anything", 10);

        assertThat(results.tracks()).extracting(Track::title).containsExactly("Alpha");
        assertThat(results.failures()).singleElement().satisfies(f -> {
            assertThat(f.provider()).isEqualTo(ProviderId.YOUTUBE);
            assertThat(f.kind()).isEqualTo(ProviderException.Kind.UNAVAILABLE);
        });
    }

    @Test
    @DisplayName("all services failing yields an empty, explicitly partial result")
    void allProvidersFailing() {
        build(
                FakeProvider.of(ProviderId.SPOTIFY).failingWith(ProviderException.Kind.UNAUTHORIZED),
                FakeProvider.of(ProviderId.YOUTUBE).failingWith(ProviderException.Kind.UNAVAILABLE));
        connected(ProviderId.SPOTIFY, ProviderId.YOUTUBE);

        SearchService.SearchResults results = searchService.searchAll("anything", 10);

        assertThat(results.tracks()).isEmpty();
        assertThat(results.failures()).hasSize(2);
        assertThat(results.isPartial()).isTrue();
    }

    @Test
    @DisplayName("no connected services yields empty rather than an error")
    void noConnectedServices() {
        build(FakeProvider.of(ProviderId.SPOTIFY).returningTracks("Alpha"));
        when(connectionService.activeCredentials()).thenReturn(List.of());

        SearchService.SearchResults results = searchService.searchAll("anything", 10);

        assertThat(results.tracks()).isEmpty();
        assertThat(results.failures()).isEmpty();
        assertThat(results.isPartial()).isFalse();
    }

    @Test
    @DisplayName("the per-service limit is applied per service, not overall")
    void limitIsPerProvider() {
        build(
                FakeProvider.of(ProviderId.SPOTIFY).returningTracks("A1", "A2", "A3"),
                FakeProvider.of(ProviderId.YOUTUBE).returningTracks("B1", "B2", "B3"));
        connected(ProviderId.SPOTIFY, ProviderId.YOUTUBE);

        SearchService.SearchResults results = searchService.searchAll("anything", 2);

        assertThat(results.tracks()).hasSize(4);
        assertThat(results.tracks()).extracting(Track::title)
                .containsExactlyInAnyOrder("A1", "A2", "B1", "B2");
    }

    @Test
    @DisplayName("a blank query short-circuits without touching any service")
    void blankQueryIsRejectedEarly() {
        FakeProvider spotify = FakeProvider.of(ProviderId.SPOTIFY).returningTracks("Alpha");
        build(spotify);

        SearchService.SearchResults results = searchService.searchAll("   ", 10);

        assertThat(results.tracks()).isEmpty();
        assertThat(spotify.searchCallCount()).isZero();
    }
}
