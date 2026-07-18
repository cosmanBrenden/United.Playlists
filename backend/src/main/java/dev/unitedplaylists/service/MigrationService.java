package dev.unitedplaylists.service;

import dev.unitedplaylists.domain.Playlist;
import dev.unitedplaylists.domain.PlaylistEntry;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.service.SearchService.ProviderFailure;
import dev.unitedplaylists.service.SearchService.SearchResults;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Moves playlist tracks from one service to the same song on another (roadmap 6:
 * "find this track on another service").
 *
 * <p>The flow the user asked for: pick some tracks (or the whole playlist) and a
 * target service. For each track the app searches the target; when it finds an
 * unambiguous match it swaps the track in place there and then. Anything it cannot
 * confidently match is returned unresolved, with candidates drawn from
 * <em>every</em> service, so the user can pick the right one — or a copy on a third
 * service — from a single screen.
 *
 * <p>Why the search happens here and not inside a transaction: each track is a
 * network round-trip to the providers, and holding a database transaction open
 * across seconds of HTTP would pin a connection for no reason and widen the window
 * for a lock. So this reads the playlist, does all its searching with no
 * transaction held, and applies the confident replacements in one short write at
 * the end.
 *
 * <p>Tracks are processed one at a time rather than all at once on purpose. A
 * single provider search can cost real quota (YouTube bills 100 units each), and
 * firing fifty in parallel is the fastest way to get rate-limited mid-job. The
 * per-track search already fans out across services concurrently; serialising the
 * tracks trades a little wall-clock for staying under the services' limits.
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final PlaylistService playlistService;
    private final SearchService searchService;
    private final TrackMatcher matcher;

    /**
     * How many candidates the target search asks each service for. The user framed
     * it as "an exact match within the first ten or so"; ten is also plenty for the
     * manual picker without turning it into a wall of near-misses.
     */
    private final int candidateLimit;

    public MigrationService(
            PlaylistService playlistService,
            SearchService searchService,
            TrackMatcher matcher,
            @Value("${unitedplaylists.migration.candidate-limit:10}") int candidateLimit) {
        this.playlistService = playlistService;
        this.searchService = searchService;
        this.matcher = matcher;
        this.candidateLimit = candidateLimit;
    }

    /**
     * Runs a migration job over a playlist.
     *
     * @param playlistId  the playlist to migrate within
     * @param target      the service to move tracks onto
     * @param positions   the 0-based positions to migrate; empty means the whole
     *                    playlist. Positions already on {@code target} are skipped.
     * @return what was replaced automatically, what still needs the user, and which
     *     services failed to answer
     */
    public MigrationResult migrate(java.util.UUID playlistId, ProviderId target, List<Integer> positions) {
        Playlist playlist = playlistService.findById(playlistId);
        List<PlaylistEntry> entries = playlist.getEntries();

        // Sorted, de-duplicated, in-range. A TreeSet also makes the report read
        // top-to-bottom regardless of the order positions arrived in.
        SequencedSet<Integer> targets = new TreeSet<>();
        if (positions == null || positions.isEmpty()) {
            for (int i = 0; i < entries.size(); i++) {
                targets.add(i);
            }
        } else {
            for (Integer position : positions) {
                if (position != null && position >= 0 && position < entries.size()) {
                    targets.add(position);
                }
            }
        }

        Map<Integer, Track> autoReplacements = new LinkedHashMap<>();
        List<Replacement> replaced = new ArrayList<>();
        List<Unresolved> unresolved = new ArrayList<>();
        int alreadyOnTarget = 0;
        // Distinct failures, keyed by provider so a service that is down for every
        // track is reported once rather than fifty times.
        Map<ProviderId, ProviderFailure> failures = new LinkedHashMap<>();

        for (Integer position : targets) {
            Track source = entries.get(position).toTrack();
            if (source.provider() == target) {
                alreadyOnTarget++;
                continue;
            }

            SearchResults results = searchService.searchAll(queryFor(source), candidateLimit);
            for (ProviderFailure failure : results.failures()) {
                failures.putIfAbsent(failure.provider(), failure);
            }

            List<Track> onTarget = results.byProvider().getOrDefault(target, List.of());
            Optional<Track> exact = matcher.bestExact(source, onTarget);
            if (exact.isPresent()) {
                autoReplacements.put(position, exact.get());
                replaced.add(new Replacement(position, source, exact.get()));
            } else {
                unresolved.add(new Unresolved(position, source, candidatesFor(source, results)));
            }
        }

        Playlist updated = autoReplacements.isEmpty()
                ? playlist
                : playlistService.replaceTracks(playlistId, autoReplacements);

        log.info("Migration of playlist {} to {}: {} auto-replaced, {} need review, {} already on target",
                playlistId, target, replaced.size(), unresolved.size(), alreadyOnTarget);

        return new MigrationResult(
                updated, target, replaced, unresolved, alreadyOnTarget, List.copyOf(failures.values()));
    }

    /**
     * The pool of candidates shown when a track needs manual resolution — every
     * service's results, best match first, minus the track being migrated from
     * itself (offering the user their own current track as a "replacement" is
     * pointless).
     */
    private List<Track> candidatesFor(Track source, SearchResults results) {
        return matcher.rank(source, results.tracks()).stream()
                .map(TrackMatcher.Match::track)
                .filter(candidate -> !candidate.ref().equals(source.ref()))
                .toList();
    }

    /**
     * The query for finding a track elsewhere: its title and lead artist. The album
     * is left out on purpose — a compilation or single often carries a different
     * album name across services and would only narrow away good matches.
     */
    private static String queryFor(Track source) {
        String primaryArtist = source.artists().isEmpty() ? "" : source.artists().get(0);
        return (source.title() + " " + primaryArtist).trim();
    }

    /** A track that was swapped automatically, and what it was swapped for. */
    public record Replacement(int position, Track from, Track to) {
    }

    /** A track the app could not confidently match, with candidates to choose from. */
    public record Unresolved(int position, Track source, List<Track> candidates) {

        public Unresolved {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /**
     * The outcome of a migration job.
     *
     * @param playlist       the playlist after the automatic replacements
     * @param target         the service tracks were migrated toward
     * @param replaced       tracks swapped automatically
     * @param unresolved     tracks awaiting a manual choice
     * @param alreadyOnTarget how many selected tracks were already on the target and
     *                        so left alone
     * @param failures       services that failed to answer during the job; a
     *                        non-empty list means some tracks may have been marked
     *                        unresolved only because a service was unreachable
     */
    public record MigrationResult(
            Playlist playlist,
            ProviderId target,
            List<Replacement> replaced,
            List<Unresolved> unresolved,
            int alreadyOnTarget,
            List<ProviderFailure> failures) {

        public MigrationResult {
            replaced = replaced == null ? List.of() : List.copyOf(replaced);
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }
}
