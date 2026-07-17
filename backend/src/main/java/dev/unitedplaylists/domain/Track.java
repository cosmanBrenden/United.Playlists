package dev.unitedplaylists.domain;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Track metadata as reported by the service it came from.
 *
 * <p>{@code ref.provider()} is what lets search results show their origin
 * service, so it is never dropped during aggregation.
 *
 * @param ref        which service this came from, and its id there
 * @param title      track title
 * @param artists    contributing artists, in the service's own order
 * @param album      album name, or null if the service does not report one
 * @param duration   track length, or null if unknown
 * @param artworkUrl cover art URL, or null if none
 * @param playable   false when the service says this user cannot play it
 *                   (region lock, premium-only, or taken down)
 */
public record Track(
        TrackRef ref,
        String title,
        List<String> artists,
        String album,
        Duration duration,
        String artworkUrl,
        boolean playable) {

    public Track {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(title, "title");
        artists = artists == null ? List.of() : List.copyOf(artists);
    }

    public ProviderId provider() {
        return ref.provider();
    }

    /** Artists joined for display, e.g. "Radiohead, Thom Yorke". */
    public String artistLine() {
        return String.join(", ", artists);
    }
}
