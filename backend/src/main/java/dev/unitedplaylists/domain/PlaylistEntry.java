package dev.unitedplaylists.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One track inside a local playlist.
 *
 * <p>Holds a {@link TrackRef} plus a cached copy of the display metadata. The
 * cache is what makes a playlist readable offline and keeps rendering a 500-track
 * playlist from fanning out into 500 API calls; it is refreshed on import and
 * otherwise allowed to go stale.
 */
@Entity
@Table(name = "playlist_entry")
public class PlaylistEntry {

    /**
     * Separator for the denormalised artist list. ASCII unit separator: chosen
     * because it cannot occur in an artist name, unlike a comma.
     */
    private static final String ARTIST_SEP = "\u001F";

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "playlist_id", nullable = false)
    private Playlist playlist;

    /** 0-based position within the playlist. Kept dense by {@link Playlist}. */
    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private ProviderId provider;

    @Column(name = "provider_track_id", nullable = false, length = 255)
    private String providerTrackId;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "artists", length = 1024)
    private String artists;

    @Column(name = "album", length = 512)
    private String album;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "artwork_url", length = 1024)
    private String artworkUrl;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected PlaylistEntry() {
        // JPA
    }

    PlaylistEntry(Playlist playlist, int position, Track track, Instant addedAt) {
        this.playlist = playlist;
        this.position = position;
        this.provider = track.ref().provider();
        this.providerTrackId = track.ref().providerTrackId();
        this.title = track.title();
        this.artists = String.join(ARTIST_SEP, track.artists());
        this.album = track.album();
        this.durationMs = track.duration() == null ? null : track.duration().toMillis();
        this.artworkUrl = track.artworkUrl();
        this.addedAt = addedAt;
    }

    public UUID getId() {
        return id;
    }

    public int getPosition() {
        return position;
    }

    void setPosition(int position) {
        this.position = position;
    }

    public Playlist getPlaylist() {
        return playlist;
    }

    public TrackRef getRef() {
        return new TrackRef(provider, providerTrackId);
    }

    /**
     * Overwrites this slot's track with another, keeping its position in the
     * playlist and its {@code addedAt}.
     *
     * <p>This is how cross-service migration swaps "the Spotify copy" for "the
     * YouTube copy" without disturbing the surrounding order: the slot is the same,
     * only what fills it changes. Replacing in place rather than remove-then-insert
     * is deliberate — it cannot renumber the rest of the playlist, so a batch
     * migration of several tracks never has to worry about positions shifting under
     * it.
     */
    void replaceTrack(Track track) {
        this.provider = track.ref().provider();
        this.providerTrackId = track.ref().providerTrackId();
        this.title = track.title();
        this.artists = String.join(ARTIST_SEP, track.artists());
        this.album = track.album();
        this.durationMs = track.duration() == null ? null : track.duration().toMillis();
        this.artworkUrl = track.artworkUrl();
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    /** The cached metadata, rebuilt as a {@link Track}. Playability is not cached. */
    public Track toTrack() {
        return new Track(
                getRef(),
                title,
                artists == null || artists.isEmpty() ? List.of() : List.of(artists.split(ARTIST_SEP)),
                album,
                durationMs == null ? null : java.time.Duration.ofMillis(durationMs),
                artworkUrl,
                true);
    }
}
