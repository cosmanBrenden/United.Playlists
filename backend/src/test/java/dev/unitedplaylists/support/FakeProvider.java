package dev.unitedplaylists.support;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.provider.ImportedPlaylist;
import dev.unitedplaylists.provider.MusicProvider;
import dev.unitedplaylists.provider.PlaybackMethod;
import dev.unitedplaylists.provider.PlaybackTicket;
import dev.unitedplaylists.provider.ProviderCredentials;
import dev.unitedplaylists.provider.ProviderException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A scriptable {@link MusicProvider} for testing aggregation.
 *
 * <p>Lets a test say "this service is slow", "this one is rate-limited", and
 * "this one works" and then assert on how the aggregate behaves, without any HTTP.
 */
public final class FakeProvider implements MusicProvider {

    private final ProviderId id;
    private final AtomicInteger searchCalls = new AtomicInteger();
    private Supplier<List<Track>> searchBehaviour;
    private Duration searchDelay = Duration.ZERO;
    private List<ImportedPlaylist> playlists = List.of();

    private FakeProvider(ProviderId id) {
        this.id = id;
        this.searchBehaviour = List::of;
    }

    public static FakeProvider of(ProviderId id) {
        return new FakeProvider(id);
    }

    /** Returns one track per title given, tagged with this provider. */
    public FakeProvider returningTracks(String... titles) {
        this.searchBehaviour = () -> {
            List<Track> out = new java.util.ArrayList<>();
            for (int i = 0; i < titles.length; i++) {
                out.add(Fixtures.track(id, id.name().toLowerCase() + "-" + i, titles[i]));
            }
            return List.copyOf(out);
        };
        return this;
    }

    public FakeProvider failingWith(ProviderException.Kind kind) {
        this.searchBehaviour = () -> {
            throw new ProviderException(id, kind, id + " is unhappy");
        };
        return this;
    }

    /** Simulates a non-{@code ProviderException} blowing up inside a provider. */
    public FakeProvider failingWithRuntimeException() {
        this.searchBehaviour = () -> {
            throw new IllegalStateException("unexpected boom in " + id);
        };
        return this;
    }

    public FakeProvider slowBy(Duration delay) {
        this.searchDelay = delay;
        return this;
    }

    public FakeProvider withPlaylists(List<ImportedPlaylist> imported) {
        this.playlists = List.copyOf(imported);
        return this;
    }

    public int searchCallCount() {
        return searchCalls.get();
    }

    @Override
    public ProviderId id() {
        return id;
    }

    @Override
    public String displayName() {
        return "Fake " + id;
    }

    @Override
    public List<ImportedPlaylist> fetchPlaylists(ProviderCredentials credentials) {
        return playlists;
    }

    @Override
    public List<Track> search(ProviderCredentials credentials, String query, int limit) {
        searchCalls.incrementAndGet();
        if (!searchDelay.isZero()) {
            try {
                Thread.sleep(searchDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderException(id, ProviderException.Kind.UNAVAILABLE, "interrupted", e);
            }
        }
        return searchBehaviour.get().stream().limit(limit).toList();
    }

    @Override
    public PlaybackTicket resolvePlayback(ProviderCredentials credentials, TrackRef ref) {
        return new PlaybackTicket(ref, PlaybackMethod.SPOTIFY_WEB_SDK, Map.of("id", ref.providerTrackId()));
    }
}
