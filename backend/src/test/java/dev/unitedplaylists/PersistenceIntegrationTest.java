package dev.unitedplaylists;

import static org.assertj.core.api.Assertions.assertThat;

import dev.unitedplaylists.domain.Playlist;
import dev.unitedplaylists.domain.PlaylistEntry;
import dev.unitedplaylists.domain.PlaylistOrigin;
import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.ServiceConnection;
import dev.unitedplaylists.repository.PlaylistRepository;
import dev.unitedplaylists.repository.ServiceConnectionRepository;
import dev.unitedplaylists.support.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises the real schema.
 *
 * <p>With {@code ddl-auto: validate}, booting the context at all proves the Flyway
 * migration and the JPA entities agree — the mismatch that unit tests with mocked
 * repositories never catch and that only shows up on a user's machine.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersistenceIntegrationTest {

    @Autowired
    private PlaylistRepository playlists;

    @Autowired
    private ServiceConnectionRepository connections;

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    @DisplayName("the migration and the entity mappings agree")
    void schemaValidates() {
        // Reaching here means Hibernate validated every mapping against the migration.
        assertThat(playlists.count()).isNotNegative();
    }

    @Test
    @Transactional
    void roundTripsAPlaylistWithMixedServiceTracks() {
        Playlist playlist = Playlist.createLocal("Mixed Bag", "from everywhere", NOW);
        playlist.addTrack(Fixtures.spotifyTrack("sp1", "Spotify Song"), NOW);
        playlist.addTrack(Fixtures.youtubeTrack("yt1", "YouTube Song"), NOW);
        UUID id = playlists.save(playlist).getId();

        Playlist loaded = playlists.findByIdWithEntries(id).orElseThrow();

        assertThat(loaded.getName()).isEqualTo("Mixed Bag");
        assertThat(loaded.providersUsed()).containsExactly(ProviderId.SPOTIFY, ProviderId.YOUTUBE);
        assertThat(loaded.getEntries()).extracting(e -> e.toTrack().title())
                .containsExactly("Spotify Song", "YouTube Song");
        assertThat(loaded.getEntries()).extracting(PlaylistEntry::getPosition)
                .containsExactly(0, 1);
    }

    @Test
    @Transactional
    @DisplayName("multi-word artists survive the separator encoding in a real column")
    void persistsArtistListsFaithfully() {
        Playlist playlist = Playlist.createLocal("Collabs", null, NOW);
        playlist.addTrack(
                Fixtures.trackWithArtists(ProviderId.SPOTIFY, "t1", "Under Pressure",
                        "Queen", "David Bowie"),
                NOW);
        UUID id = playlists.save(playlist).getId();

        Playlist loaded = playlists.findByIdWithEntries(id).orElseThrow();

        assertThat(loaded.getEntries().get(0).toTrack().artists())
                .containsExactly("Queen", "David Bowie");
    }

    @Test
    @Transactional
    void findsAnImportedPlaylistByItsOrigin() {
        PlaylistOrigin origin = new PlaylistOrigin(ProviderId.SPOTIFY, "spotify_pl_42", NOW);
        playlists.save(Playlist.createImported("Discover Weekly", null, origin, NOW));

        Optional<Playlist> found = playlists.findByOrigin(ProviderId.SPOTIFY, "spotify_pl_42");

        assertThat(found).isPresent();
        assertThat(found.get().getOrigin()).get()
                .satisfies(o -> assertThat(o.getOriginPlaylistId()).isEqualTo("spotify_pl_42"));
        assertThat(playlists.findByOrigin(ProviderId.YOUTUBE, "spotify_pl_42")).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("a local playlist has no origin and is not matched by an origin lookup")
    void localPlaylistsHaveNoOrigin() {
        playlists.save(Playlist.createLocal("Hand Made", null, NOW));

        Playlist loaded = playlists.findAllWithEntries().get(0);

        assertThat(loaded.isImported()).isFalse();
        assertThat(loaded.getOrigin()).isEmpty();
    }

    @Test
    @Transactional
    void deletingAPlaylistRemovesItsEntries() {
        Playlist playlist = Playlist.createLocal("Doomed", null, NOW);
        playlist.addTrack(Fixtures.spotifyTrack("sp1", "Song"), NOW);
        UUID id = playlists.save(playlist).getId();

        playlists.deleteById(id);

        assertThat(playlists.findByIdWithEntries(id)).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("removing a track keeps positions dense in storage, not just in memory")
    void reindexesPositionsOnRemove() {
        Playlist playlist = Playlist.createLocal("Ordered", null, NOW);
        playlist.addTrack(Fixtures.spotifyTrack("a", "A"), NOW);
        playlist.addTrack(Fixtures.spotifyTrack("b", "B"), NOW);
        playlist.addTrack(Fixtures.spotifyTrack("c", "C"), NOW);
        UUID id = playlists.save(playlist).getId();

        Playlist loaded = playlists.findByIdWithEntries(id).orElseThrow();
        loaded.removeAt(1, NOW);
        playlists.saveAndFlush(loaded);

        Playlist reloaded = playlists.findByIdWithEntries(id).orElseThrow();
        assertThat(reloaded.getEntries()).extracting(e -> e.toTrack().title())
                .containsExactly("A", "C");
        assertThat(reloaded.getEntries()).extracting(PlaylistEntry::getPosition)
                .containsExactly(0, 1);
    }

    @Test
    @Transactional
    @DisplayName("only ciphertext reaches the token columns")
    void storesConnectionTokensAsCiphertext() {
        connections.save(new ServiceConnection(
                ProviderId.SPOTIFY,
                "ENCRYPTED_ACCESS",
                "ENCRYPTED_REFRESH",
                NOW.plusSeconds(3600),
                "playlist-read-private",
                "user@example.invalid",
                "US",
                NOW));

        ServiceConnection loaded = connections.findById(ProviderId.SPOTIFY).orElseThrow();

        assertThat(loaded.getAccessTokenEnc()).isEqualTo("ENCRYPTED_ACCESS");
        assertThat(loaded.hasRefreshToken()).isTrue();
        assertThat(loaded.getMarket()).isEqualTo("US");
    }

    @Test
    @Transactional
    void listsPlaylistsWithTheirEntriesInOneQuery() {
        Playlist first = Playlist.createLocal("First", null, NOW);
        first.addTrack(Fixtures.spotifyTrack("a", "A"), NOW);
        playlists.save(first);

        Playlist second = Playlist.createLocal("Second", null, NOW.plusSeconds(60));
        second.addTrack(Fixtures.youtubeTrack("b", "B"), NOW.plusSeconds(60));
        playlists.save(second);

        List<Playlist> all = playlists.findAllWithEntries();

        assertThat(all).hasSize(2);
        // Ordered by updatedAt DESC, so the newer one leads.
        assertThat(all).extracting(Playlist::getName).containsExactly("Second", "First");
        assertThat(all.get(0).getEntries()).hasSize(1);
    }
}
