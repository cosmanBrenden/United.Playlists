package dev.unitedplaylists.provider;

import dev.unitedplaylists.domain.Track;
import java.util.List;
import java.util.Objects;

/**
 * A playlist as it exists on a service, before it is copied into local storage.
 *
 * <p>Read-only by construction: this is a snapshot of what the service returned,
 * not a handle that can be written back to.
 *
 * @param providerPlaylistId the service's id for this playlist
 * @param name               playlist name on the service
 * @param description        playlist description, or null
 * @param artworkUrl         cover image, or null
 * @param tracks             tracks in service order; empty when {@code readable} is false
 * @param readable           whether the service let us read the track list. Spotify
 *                           lists playlists the user follows alongside their own, but
 *                           refuses to hand over the contents of anything they do not
 *                           own or collaborate on — so a playlist can be visible and
 *                           unreadable at the same time. Reported rather than dropped,
 *                           so the user can be told why their Discover Weekly did not
 *                           come across.
 */
public record ImportedPlaylist(
        String providerPlaylistId,
        String name,
        String description,
        String artworkUrl,
        List<Track> tracks,
        boolean readable) {

    public ImportedPlaylist {
        Objects.requireNonNull(providerPlaylistId, "providerPlaylistId");
        Objects.requireNonNull(name, "name");
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
    }

    /** A playlist whose contents the service allowed us to read. */
    public static ImportedPlaylist readable(
            String providerPlaylistId,
            String name,
            String description,
            String artworkUrl,
            List<Track> tracks) {
        return new ImportedPlaylist(providerPlaylistId, name, description, artworkUrl, tracks, true);
    }

    /** A playlist the service listed but would not let us read the contents of. */
    public static ImportedPlaylist unreadable(
            String providerPlaylistId, String name, String description, String artworkUrl) {
        return new ImportedPlaylist(providerPlaylistId, name, description, artworkUrl, List.of(), false);
    }
}
