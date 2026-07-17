package dev.unitedplaylists.repository;

import dev.unitedplaylists.domain.Playlist;
import dev.unitedplaylists.domain.ProviderId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaylistRepository extends JpaRepository<Playlist, UUID> {

    /**
     * Loads a playlist with its entries in one query.
     *
     * <p>The plain finder plus a lazy {@code entries} walk is the classic N+1 here:
     * rendering a playlist always touches every entry, so fetching them separately
     * buys nothing and costs a round trip per playlist.
     */
    @Query("SELECT p FROM Playlist p LEFT JOIN FETCH p.entries WHERE p.id = :id")
    Optional<Playlist> findByIdWithEntries(@Param("id") UUID id);

    /**
     * All playlists, entries included, newest first.
     *
     * <p>{@code DISTINCT} because the join fans out one row per entry. This is
     * acceptable for a local library of tens of playlists; if that assumption ever
     * breaks, this becomes a projection plus a lazy entry load.
     */
    @Query("SELECT DISTINCT p FROM Playlist p LEFT JOIN FETCH p.entries ORDER BY p.updatedAt DESC")
    List<Playlist> findAllWithEntries();

    /** Used by import to spot a playlist that was already brought in. */
    @Query("""
            SELECT p FROM Playlist p
            WHERE p.origin.provider = :provider
              AND p.origin.originPlaylistId = :originId
            """)
    Optional<Playlist> findByOrigin(
            @Param("provider") ProviderId provider, @Param("originId") String originId);
}
