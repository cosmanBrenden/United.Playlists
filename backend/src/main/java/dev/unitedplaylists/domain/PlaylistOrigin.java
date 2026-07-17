package dev.unitedplaylists.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;

/**
 * Where an imported playlist was copied from.
 *
 * <p>This is a record of provenance only. It is deliberately not a link: the app
 * never pushes edits back to the origin service, so this exists to show the user
 * "imported from Spotify on 3 May" and to let a re-import find its counterpart.
 */
@Embeddable
public class PlaylistOrigin {

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_provider", length = 32)
    private ProviderId provider;

    @Column(name = "origin_playlist_id", length = 255)
    private String originPlaylistId;

    @Column(name = "imported_at")
    private Instant importedAt;

    protected PlaylistOrigin() {
        // JPA
    }

    public PlaylistOrigin(ProviderId provider, String originPlaylistId, Instant importedAt) {
        this.provider = provider;
        this.originPlaylistId = originPlaylistId;
        this.importedAt = importedAt;
    }

    public ProviderId getProvider() {
        return provider;
    }

    public String getOriginPlaylistId() {
        return originPlaylistId;
    }

    public Instant getImportedAt() {
        return importedAt;
    }
}
