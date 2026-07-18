package dev.unitedplaylists.service;

import dev.unitedplaylists.domain.Track;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Decides whether a track on one service is "the same song" as a track on another.
 *
 * <p>This is the judgement that makes cross-service migration safe to do
 * automatically. Two services describe the same recording differently — casing,
 * punctuation, accented characters, a trailing "- Remastered 2011", a
 * "(feat. …)" that one lists and the other folds into the title — so a raw string
 * compare would call almost nothing a match. Normalisation strips that noise; the
 * score then rewards a shared title and shared artists.
 *
 * <p>The bar for an <em>exact</em> match (the only kind we replace without asking)
 * is deliberately high: a wrong automatic swap is worse than asking, because the
 * user loses the track they had and may not notice. So EXACT demands the cleaned
 * titles be identical, the artists overlap, and — when both services report a
 * length — the durations agree within a few seconds. That last check is what keeps
 * a studio cut from being silently replaced by a live or extended version that
 * happens to share a title and artist.
 *
 * <p>Stateless and therefore safe to share across the concurrent search fan-out.
 */
@Component
public class TrackMatcher {

    /**
     * How far two durations may differ and still count as the same recording.
     *
     * <p>Four seconds absorbs the usual disagreement between services (trailing
     * silence, a fade counted differently) without waving through a radio edit or a
     * live take, which typically differ by far more.
     */
    private static final Duration DURATION_TOLERANCE = Duration.ofSeconds(4);

    /**
     * Parenthetical/bracketed qualifiers are dropped before comparing titles:
     * "Song (Remastered)" and "Song" are the same song. The duration check is what
     * still separates versions that genuinely differ in length.
     */
    private static final Pattern BRACKETED = Pattern.compile("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]");

    /** A trailing "feat./ft./featuring/with …" is collaborator noise, not the title. */
    private static final Pattern TRAILING_FEAT =
            Pattern.compile("\\b(feat|ft|featuring|with)\\b.*$");

    /** A trailing "- Remastered 2011", "- Live", "- Radio Edit" and friends. */
    private static final Pattern TRAILING_DASH_QUALIFIER =
            Pattern.compile("\\s-\\s.*$");

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    /**
     * How good a candidate is as a replacement for a source track, best first.
     *
     * <ul>
     *   <li>{@code EXACT} — same title, overlapping artists, matching duration.
     *       Safe to replace automatically.
     *   <li>{@code STRONG} — very likely the same song, but something (a differing
     *       duration, a looser title) warrants a human glance.
     *   <li>{@code PARTIAL} — plausibly related; worth showing, not worth trusting.
     *   <li>{@code NONE} — not a match.
     * </ul>
     */
    public enum MatchQuality {
        EXACT,
        STRONG,
        PARTIAL,
        NONE
    }

    /** A candidate track paired with how well it matches the source. */
    public record Match(Track track, MatchQuality quality, double score) {

        public boolean isExact() {
            return quality == MatchQuality.EXACT;
        }
    }

    /**
     * The single best replacement to apply without asking, if any.
     *
     * <p>Returns a candidate only when it is an unambiguous EXACT match: the highest
     * scorer must be EXACT. When two different candidates are both EXACT — which
     * really means the query was ambiguous — this declines rather than guess, so the
     * choice falls to the user.
     */
    public Optional<Track> bestExact(Track source, List<Track> candidates) {
        List<Match> ranked = rank(source, candidates);
        if (ranked.isEmpty() || !ranked.get(0).isExact()) {
            return Optional.empty();
        }
        long exactCount = ranked.stream().filter(Match::isExact).count();
        if (exactCount > 1 && !sameTrack(ranked.get(0).track(), ranked.get(1).track())) {
            return Optional.empty();
        }
        return Optional.of(ranked.get(0).track());
    }

    /**
     * Every candidate scored against the source, best match first, matches only.
     *
     * <p>{@link MatchQuality#NONE} candidates are dropped: on the manual-pick screen
     * they are noise, and offering a track that is not the song helps no one.
     */
    public List<Match> rank(Track source, List<Track> candidates) {
        Normalized src = Normalized.of(source);
        return candidates.stream()
                .map(candidate -> score(src, source, candidate))
                .filter(match -> match.quality() != MatchQuality.NONE)
                .sorted(Comparator.comparingDouble(Match::score).reversed())
                .toList();
    }

    /** Classifies one candidate against one source track. */
    public MatchQuality classify(Track source, Track candidate) {
        return score(Normalized.of(source), source, candidate).quality();
    }

    private Match score(Normalized src, Track source, Track candidate) {
        Normalized cand = Normalized.of(candidate);

        double titleSim = jaccard(src.titleTokens(), cand.titleTokens());
        double artistSim = jaccard(src.artistTokens(), cand.artistTokens());
        boolean titlesIdentical = src.title().equals(cand.title()) && !src.title().isEmpty();
        boolean durationOk = durationsAgree(source.duration(), candidate.duration());
        boolean anyDurationKnown = source.duration() != null && candidate.duration() != null;

        double combined = 0.6 * titleSim + 0.4 * artistSim;

        MatchQuality quality;
        if (titlesIdentical && artistSim >= 0.5 && durationOk) {
            // Everything lines up — and if durations are known they agree.
            quality = MatchQuality.EXACT;
        } else if (titlesIdentical && artistSim >= 0.5) {
            // Same song by the same artist, but the durations disagree: probably a
            // different version. Show it, do not auto-apply it.
            quality = MatchQuality.STRONG;
        } else if (combined >= 0.72 && artistSim > 0) {
            quality = MatchQuality.STRONG;
        } else if (titleSim >= 0.5 && artistSim > 0) {
            quality = MatchQuality.PARTIAL;
        } else if (titlesIdentical) {
            // Right title, but the artist does not line up at all (a cover, or a
            // different song sharing a name). Worth a look, not worth trusting.
            quality = MatchQuality.PARTIAL;
        } else {
            quality = MatchQuality.NONE;
        }

        // Nudge exact-duration agreement above near-misses so the best version sorts
        // first among otherwise-equal candidates on the manual screen.
        double bonus = anyDurationKnown && durationOk ? 0.05 : 0.0;
        return new Match(candidate, quality, combined + bonus);
    }

    private static boolean durationsAgree(Duration a, Duration b) {
        if (a == null || b == null) {
            // A service that does not report a length must not block a match; the
            // title-and-artist agreement already carries the decision.
            return true;
        }
        return a.minus(b).abs().compareTo(DURATION_TOLERANCE) <= 0;
    }

    private static boolean sameTrack(Track a, Track b) {
        return a.ref().equals(b.ref());
    }

    /** Set overlap: |A ∩ B| / |A ∪ B|, with two empty sets treated as no signal. */
    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        int unionSize = a.size() + b.size() - intersection.size();
        return unionSize == 0 ? 0.0 : (double) intersection.size() / unionSize;
    }

    /**
     * A track reduced to what matters for comparison: a cleaned title and the two
     * token sets the score is built from. Computed once per track so the O(n²) of
     * ranking every candidate against the source does not re-normalise the source n
     * times.
     */
    private record Normalized(String title, Set<String> titleTokens, Set<String> artistTokens) {

        static Normalized of(Track track) {
            String title = cleanTitle(track.title());
            return new Normalized(
                    title,
                    tokens(title),
                    tokens(String.join(" ", track.artists())));
        }
    }

    /** Lower-cases, strips accents, and drops "(feat …)"/"- Remastered" style noise. */
    static String cleanTitle(String raw) {
        String s = foldAccents(raw).toLowerCase(Locale.ROOT);
        s = BRACKETED.matcher(s).replaceAll(" ");
        s = TRAILING_FEAT.matcher(s).replaceAll(" ");
        s = TRAILING_DASH_QUALIFIER.matcher(s).replaceAll(" ");
        return NON_ALNUM.matcher(s).replaceAll(" ").trim();
    }

    private static Set<String> tokens(String raw) {
        String s = foldAccents(raw).toLowerCase(Locale.ROOT);
        s = BRACKETED.matcher(s).replaceAll(" ");
        s = NON_ALNUM.matcher(s).replaceAll(" ").trim();
        if (s.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(s.split(" ")));
    }

    private static String foldAccents(String s) {
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("");
    }
}
