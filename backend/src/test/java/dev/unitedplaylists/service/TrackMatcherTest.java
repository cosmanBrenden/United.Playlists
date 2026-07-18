package dev.unitedplaylists.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.Track;
import dev.unitedplaylists.domain.TrackRef;
import dev.unitedplaylists.service.TrackMatcher.MatchQuality;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TrackMatcherTest {

    private final TrackMatcher matcher = new TrackMatcher();

    private static Track track(
            ProviderId provider, String title, List<String> artists, Duration duration) {
        return new Track(
                new TrackRef(provider, provider.name() + "-" + title.hashCode()),
                title,
                artists,
                "Album",
                duration,
                null,
                true);
    }

    private static Track spotify(String title, String artist, Duration d) {
        return track(ProviderId.SPOTIFY, title, List.of(artist), d);
    }

    private static Track youtube(String title, String artist, Duration d) {
        return track(ProviderId.YOUTUBE, title, List.of(artist), d);
    }

    @Nested
    @DisplayName("exact matches")
    class Exact {

        @Test
        void identicalTitleArtistAndDurationIsExact() {
            Track source = spotify("Bohemian Rhapsody", "Queen", Duration.ofSeconds(355));
            Track candidate = youtube("Bohemian Rhapsody", "Queen", Duration.ofSeconds(355));

            assertThat(matcher.classify(source, candidate)).isEqualTo(MatchQuality.EXACT);
        }

        @Test
        void casingPunctuationAndAccentsAreIgnored() {
            Track source = spotify("Café del Mar", "Energy 52", Duration.ofSeconds(400));
            Track candidate = youtube("CAFE DEL MAR!!!", "Energy 52", Duration.ofSeconds(401));

            assertThat(matcher.classify(source, candidate)).isEqualTo(MatchQuality.EXACT);
        }

        @Test
        void remasterAndFeatQualifiersDoNotBlockAnExactMatch() {
            Track source = spotify("Come Together", "The Beatles", Duration.ofSeconds(259));
            Track candidate = youtube(
                    "Come Together - Remastered 2009", "The Beatles", Duration.ofSeconds(260));

            assertThat(matcher.classify(source, candidate)).isEqualTo(MatchQuality.EXACT);
        }

        @Test
        void featuredArtistInParenthesesIsStripped() {
            Track source = spotify("Stay (feat. Justin Bieber)", "The Kid LAROI", Duration.ofSeconds(141));
            Track candidate = youtube("Stay", "The Kid LAROI", Duration.ofSeconds(142));

            assertThat(matcher.classify(source, candidate)).isEqualTo(MatchQuality.EXACT);
        }

        @Test
        void unknownDurationsDoNotPreventAnExactMatch() {
            Track source = spotify("Yesterday", "The Beatles", null);
            Track candidate = youtube("Yesterday", "The Beatles", null);

            assertThat(matcher.classify(source, candidate)).isEqualTo(MatchQuality.EXACT);
        }
    }

    @Nested
    @DisplayName("versions that should not auto-replace")
    class Versions {

        @Test
        void aLiveVersionWithAVeryDifferentLengthIsStrongNotExact() {
            // Same title and artist, but two minutes longer: a live take, not the studio cut.
            Track source = spotify("Hurt", "Johnny Cash", Duration.ofSeconds(216));
            Track candidate = youtube("Hurt", "Johnny Cash", Duration.ofSeconds(340));

            assertThat(matcher.classify(source, candidate)).isEqualTo(MatchQuality.STRONG);
        }

        @Test
        void aCoverBySomeoneElseIsNotExact() {
            Track source = spotify("Hallelujah", "Leonard Cohen", Duration.ofSeconds(280));
            Track candidate = youtube("Hallelujah", "Jeff Buckley", Duration.ofSeconds(413));

            assertThat(matcher.classify(source, candidate)).isNotEqualTo(MatchQuality.EXACT);
        }
    }

    @Nested
    @DisplayName("non-matches")
    class NonMatches {

        @Test
        void aDifferentSongIsNone() {
            Track source = spotify("Yesterday", "The Beatles", Duration.ofSeconds(125));
            Track candidate = youtube("Thriller", "Michael Jackson", Duration.ofSeconds(357));

            assertThat(matcher.classify(source, candidate)).isEqualTo(MatchQuality.NONE);
        }
    }

    @Nested
    @DisplayName("bestExact")
    class BestExact {

        @Test
        void picksTheExactCandidateFromAMixedList() {
            Track source = spotify("Wonderwall", "Oasis", Duration.ofSeconds(258));
            Track wrong = youtube("Wonderwall (Live)", "Some Cover Band", Duration.ofSeconds(300));
            Track right = youtube("Wonderwall", "Oasis", Duration.ofSeconds(259));

            assertThat(matcher.bestExact(source, List.of(wrong, right)))
                    .contains(right);
        }

        @Test
        void declinesWhenNoCandidateIsExact() {
            Track source = spotify("Wonderwall", "Oasis", Duration.ofSeconds(258));
            Track live = youtube("Wonderwall", "Oasis", Duration.ofSeconds(360));

            assertThat(matcher.bestExact(source, List.of(live))).isEmpty();
        }

        @Test
        void declinesWhenTwoDifferentCandidatesAreBothExact() {
            // An ambiguous query returning two equally-good but distinct matches: the
            // safe move is to ask, not to guess.
            Track source = spotify("Intro", "Various", Duration.ofSeconds(120));
            Track a = new Track(
                    new TrackRef(ProviderId.YOUTUBE, "vid-a"), "Intro", List.of("Various"),
                    "Album", Duration.ofSeconds(120), null, true);
            Track b = new Track(
                    new TrackRef(ProviderId.YOUTUBE, "vid-b"), "Intro", List.of("Various"),
                    "Album", Duration.ofSeconds(121), null, true);

            assertThat(matcher.bestExact(source, List.of(a, b))).isEmpty();
        }

        @Test
        void emptyCandidatesYieldNothing() {
            Track source = spotify("Anything", "Anyone", Duration.ofSeconds(200));
            assertThat(matcher.bestExact(source, List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        void ordersBestFirstAndDropsNonMatches() {
            Track source = spotify("Numb", "Linkin Park", Duration.ofSeconds(187));
            Track best = youtube("Numb", "Linkin Park", Duration.ofSeconds(188));
            Track weaker = youtube("Numb / Encore", "Linkin Park", Duration.ofSeconds(205));
            Track unrelated = youtube("Clocks", "Coldplay", Duration.ofSeconds(307));

            List<TrackMatcher.Match> ranked =
                    matcher.rank(source, List.of(unrelated, weaker, best));

            assertThat(ranked).extracting(TrackMatcher.Match::track).doesNotContain(unrelated);
            assertThat(ranked.get(0).track()).isEqualTo(best);
        }
    }
}
