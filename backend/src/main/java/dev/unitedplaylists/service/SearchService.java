package dev.unitedplaylists.service;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.provider.MusicProvider;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderException;
import dev.unitedplaylists.provider.ProviderRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Searches every connected service at once and merges the results (spec 4).
 *
 * <p>Fan-out is concurrent because latency here is the sum of the user's patience
 * and the slowest service: run serially, three services at 400ms each is 1.2s of
 * dead air per keystroke-completed search.
 *
 * <p>Failure is treated as normal rather than exceptional. Any one service can be
 * rate-limited, expired, or down at any time, and the useful behaviour is to
 * return what the others found and report who was missing, so the UI can show
 * "YouTube didn't respond" beside real Spotify results.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** Ceiling on per-service results, to bound both memory and provider quota burn. */
    private static final int MAX_LIMIT_PER_PROVIDER = 50;

    private final ProviderRegistry registry;
    private final ConnectionService connectionService;
    private final SearchExecutor executor;
    private final Duration perProviderTimeout;

    public SearchService(
            ProviderRegistry registry,
            ConnectionService connectionService,
            SearchExecutor executor,
            @Value("${unitedplaylists.search.provider-timeout:5s}") Duration perProviderTimeout) {
        this.registry = registry;
        this.connectionService = connectionService;
        this.executor = executor;
        this.perProviderTimeout = perProviderTimeout;
    }

    /**
     * Runs {@code query} against every connected service.
     *
     * @param limitPerProvider max results from each service, so one prolific
     *     service cannot crowd the others out of the merged list
     * @return merged results plus a note of any service that failed; never throws
     *     for a service-side failure
     */
    public SearchResults searchAll(String query, int limitPerProvider) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return new SearchResults(trimmed, List.of(), List.of());
        }
        int limit = Math.clamp(limitPerProvider, 1, MAX_LIMIT_PER_PROVIDER);

        Map<ProviderId, CompletableFuture<List<Track>>> inFlight = new LinkedHashMap<>();

        // Authenticated providers are searched only when connected, and with the user's
        // token.
        for (ProviderCredentials credentials : connectionService.activeCredentials()) {
            registry.find(credentials.provider())
                    .filter(MusicProvider::requiresAuthentication)
                    .ifPresent(provider -> inFlight.put(
                            credentials.provider(),
                            searchOne(provider, credentials, trimmed, limit)));
        }

        // Anonymous providers (the scrapers) need no connection, so they are always
        // searched — this is what makes YouTube and SoundCloud work with nothing set up.
        for (MusicProvider provider : registry.available()) {
            if (!provider.requiresAuthentication()) {
                inFlight.put(
                        provider.id(),
                        searchOne(provider, ProviderCredentials.anonymous(provider.id()), trimmed, limit));
            }
        }

        if (inFlight.isEmpty()) {
            return new SearchResults(trimmed, List.of(), List.of());
        }

        List<Track> merged = new ArrayList<>();
        List<ProviderFailure> failures = new ArrayList<>();
        for (Map.Entry<ProviderId, CompletableFuture<List<Track>>> entry : inFlight.entrySet()) {
            try {
                merged.addAll(entry.getValue().get(perProviderTimeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                entry.getValue().cancel(true);
                failures.add(ProviderFailure.timeout(entry.getKey(), perProviderTimeout));
            } catch (ExecutionException e) {
                failures.add(ProviderFailure.from(entry.getKey(), e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add(ProviderFailure.from(entry.getKey(), e));
            }
        }

        merged.sort(Comparator.comparing(Track::title, String.CASE_INSENSITIVE_ORDER));
        return new SearchResults(trimmed, List.copyOf(merged), List.copyOf(failures));
    }

    private CompletableFuture<List<Track>> searchOne(
            MusicProvider provider, ProviderCredentials credentials, String query, int limit) {
        return CompletableFuture.supplyAsync(
                () -> provider.search(credentials, query, limit), executor.pool());
    }

    /** Merged search results, plus whoever failed to contribute. */
    public record SearchResults(String query, List<Track> tracks, List<ProviderFailure> failures) {

        public SearchResults {
            tracks = tracks == null ? List.of() : List.copyOf(tracks);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        /** True when at least one service failed, so the UI can say results are incomplete. */
        public boolean isPartial() {
            return !failures.isEmpty();
        }

        /** Results grouped by service, for a per-service UI. */
        public Map<ProviderId, List<Track>> byProvider() {
            Map<ProviderId, List<Track>> grouped = new LinkedHashMap<>();
            for (Track track : tracks) {
                grouped.computeIfAbsent(track.provider(), p -> new ArrayList<>()).add(track);
            }
            return grouped;
        }
    }

    /** One service's failure to answer a search. */
    public record ProviderFailure(
            ProviderId provider, ProviderException.Kind kind, String message, boolean requiresReconnect) {

        static ProviderFailure timeout(ProviderId provider, Duration after) {
            return new ProviderFailure(
                    provider,
                    ProviderException.Kind.UNAVAILABLE,
                    "Did not respond within " + after.toMillis() + "ms",
                    false);
        }

        static ProviderFailure from(ProviderId provider, Throwable cause) {
            if (cause instanceof ProviderException pe) {
                return new ProviderFailure(provider, pe.getKind(), pe.getMessage(), pe.requiresReconnect());
            }
            // A provider threw something other than ProviderException. That is a bug in
            // the provider, but it must not become a failed search for everyone else.
            log.warn("Provider {} threw an unexpected exception during search", provider, cause);
            return new ProviderFailure(
                    provider,
                    ProviderException.Kind.UNAVAILABLE,
                    "Unexpected error: " + cause.getClass().getSimpleName(),
                    false);
        }
    }

    /**
     * The executor backing search fan-out.
     *
     * <p>Virtual threads, because every task here is a provider HTTP call that spends
     * essentially all its time blocked on the network. A platform-thread pool would
     * need a size, and any size is wrong: too small and a third service waits behind
     * the first two, too large and idle threads sit on stacks. A virtual thread per
     * request costs almost nothing while parked, so the question disappears along
     * with the tuning knob.
     *
     * <p>Wrapped in its own bean so tests can build one without a Spring context and
     * shut it down deterministically.
     */
    public static class SearchExecutor implements AutoCloseable {

        private final ExecutorService pool;

        public SearchExecutor() {
            // Named so a stuck provider call is legible in a thread dump.
            this.pool = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("provider-search-", 0).factory());
        }

        ExecutorService pool() {
            return pool;
        }

        public void shutdown() {
            pool.shutdownNow();
        }

        @Override
        public void close() {
            shutdown();
        }
    }
}
