package dev.unitedplaylists.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.unitedplaylists.support.Fixtures;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlaylistTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        void localPlaylistHasNoOrigin() {
            Playlist playlist = Playlist.createLocal("Road trip", null, T0);

            assertThat(playlist.isImported()).isFalse();
            assertThat(playlist.getOrigin()).isEmpty();
            assertThat(playlist.getName()).isEqualTo("Road trip");
        }

        @Test
        void importedPlaylistRecordsWhereItCameFrom() {
            PlaylistOrigin origin = new PlaylistOrigin(ProviderId.SPOTIFY, "sp_playlist_1", T0);
            Playlist playlist = Playlist.createImported("Discover Weekly", "weekly", origin, T0);

            assertThat(playlist.isImported()).isTrue();
            assertThat(playlist.getOrigin()).get().satisfies(o -> {
                assertThat(o.getProvider()).isEqualTo(ProviderId.SPOTIFY);
                assertThat(o.getOriginPlaylistId()).isEqualTo("sp_playlist_1");
            });
        }

        @Test
        void nameIsTrimmed() {
            assertThat(Playlist.createLocal("  padded  ", null, T0).getName()).isEqualTo("padded");
        }

        @Test
        void blankNameIsRejected() {
            assertThatThrownBy(() -> Playlist.createLocal("   ", null, T0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }
    }

    @Nested
    @DisplayName("mixing services")
    class Mixing {

        @Test
        void tracksFromDifferentServicesCoexist() {
            Playlist playlist = Playlist.createLocal("Mixed", null, T0);
            playlist.addTrack(Fixtures.spotifyTrack("a", "Alpha"), T0);
            playlist.addTrack(Fixtures.youtubeTrack("b", "Beta"), T0);
            playlist.addTrack(Fixtures.spotifyTrack("c", "Gamma"), T0);

            assertThat(playlist.size()).isEqualTo(3);
            assertThat(playlist.providersUsed())
                    .containsExactly(ProviderId.SPOTIFY, ProviderId.YOUTUBE);
        }

        @Test
        void entryRemembersItsOriginService() {
            Playlist playlist = Playlist.createLocal("Mixed", null, T0);
            playlist.addTrack(Fixtures.youtubeTrack("vid1", "Beta"), T0);

            TrackRef ref = playlist.getEntries().get(0).getRef();
            assertThat(ref.provider()).isEqualTo(ProviderId.YOUTUBE);
            assertThat(ref.providerTrackId()).isEqualTo("vid1");
        }

        @Test
        void multiWordArtistListSurvivesTheRoundTrip() {
            Playlist playlist = Playlist.createLocal("Mixed", null, T0);
            Track original = Fixtures.trackWithArtists(
                    ProviderId.SPOTIFY, "x", "Song", "Simon & Garfunkel", "Miles Davis");
            playlist.addTrack(original, T0);

            Track restored = playlist.getEntries().get(0).toTrack();
            assertThat(restored.artists()).containsExactly("Simon & Garfunkel", "Miles Davis");
            assertThat(restored.artistLine()).isEqualTo("Simon & Garfunkel, Miles Davis");
        }

        @Test
        void duplicateTracksAreAllowed() {
            Playlist playlist = Playlist.createLocal("Repeat", null, T0);
            playlist.addTrack(Fixtures.spotifyTrack("same", "Loop"), T0);
            playlist.addTrack(Fixtures.spotifyTrack("same", "Loop"), T0);

            assertThat(playlist.size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        private Playlist threeTracks() {
            Playlist playlist = Playlist.createLocal("Ordered", null, T0);
            playlist.addTrack(Fixtures.spotifyTrack("1", "One"), T0);
            playlist.addTrack(Fixtures.spotifyTrack("2", "Two"), T0);
            playlist.addTrack(Fixtures.spotifyTrack("3", "Three"), T0);
            return playlist;
        }

        @Test
        void addAppendsWithDensePositions() {
            Playlist playlist = threeTracks();

            assertThat(playlist.getEntries()).extracting(PlaylistEntry::getPosition)
                    .containsExactly(0, 1, 2);
        }

        @Test
        void removeClosesTheGap() {
            Playlist playlist = threeTracks();
            playlist.removeAt(0, T1);

            assertThat(playlist.getEntries()).extracting(PlaylistEntry::getPosition)
                    .containsExactly(0, 1);
            assertThat(playlist.getEntries()).extracting(e -> e.toTrack().title())
                    .containsExactly("Two", "Three");
        }

        @Test
        void moveDownShiftsInterveningTracksUp() {
            Playlist playlist = threeTracks();
            playlist.move(0, 2, T1);

            assertThat(playlist.getEntries()).extracting(e -> e.toTrack().title())
                    .containsExactly("Two", "Three", "One");
            assertThat(playlist.getEntries()).extracting(PlaylistEntry::getPosition)
                    .containsExactly(0, 1, 2);
        }

        @Test
        void moveUpShiftsInterveningTracksDown() {
            Playlist playlist = threeTracks();
            playlist.move(2, 0, T1);

            assertThat(playlist.getEntries()).extracting(e -> e.toTrack().title())
                    .containsExactly("Three", "One", "Two");
        }

        @Test
        void moveToSamePositionIsANoOp() {
            Playlist playlist = threeTracks();
            playlist.move(1, 1, T1);

            assertThat(playlist.getEntries()).extracting(e -> e.toTrack().title())
                    .containsExactly("One", "Two", "Three");
        }

        @Test
        void outOfRangePositionIsRejected() {
            Playlist playlist = threeTracks();

            assertThatThrownBy(() -> playlist.removeAt(3, T1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> playlist.move(0, -1, T1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        void entriesAreNotMutableFromOutside() {
            Playlist playlist = threeTracks();

            assertThatThrownBy(() -> playlist.getEntries().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void replaceSwapsTheTrackKeepingItsPosition() {
            Playlist playlist = threeTracks();
            playlist.replaceAt(1, Fixtures.youtubeTrack("yt2", "Two on YouTube"), T1);

            assertThat(playlist.getEntries()).extracting(e -> e.toTrack().title())
                    .containsExactly("One", "Two on YouTube", "Three");
            assertThat(playlist.getEntries()).extracting(PlaylistEntry::getPosition)
                    .containsExactly(0, 1, 2);
            assertThat(playlist.getEntries().get(1).getRef().provider())
                    .isEqualTo(ProviderId.YOUTUBE);
        }

        @Test
        void replaceOutOfRangeIsRejected() {
            Playlist playlist = threeTracks();

            assertThatThrownBy(() -> playlist.replaceAt(3, Fixtures.spotifyTrack("x", "X"), T1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("timestamps")
    class Timestamps {

        @Test
        void mutationsAdvanceUpdatedAt() {
            Playlist playlist = Playlist.createLocal("Timed", null, T0);
            assertThat(playlist.getUpdatedAt()).isEqualTo(T0);

            playlist.rename("Renamed", T1);

            assertThat(playlist.getUpdatedAt()).isEqualTo(T1);
            assertThat(playlist.getCreatedAt()).isEqualTo(T0);
        }
    }
}
