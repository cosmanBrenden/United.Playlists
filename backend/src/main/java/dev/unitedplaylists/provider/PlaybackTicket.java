package dev.unitedplaylists.provider;

import dev.unitedplaylists.domain.TrackRef;
import java.util.Map;
import java.util.Objects;

/**
 * Instructions for the client to play a track using the origin service's own SDK.
 *
 * <p>Deliberately carries no audio and no URL that could be used to fetch audio.
 * It names a track and the SDK to hand it to; the SDK then does its own
 * licensing, DRM, and playback reporting, which is what keeps the app on the
 * right side of each service's terms.
 *
 * @param ref    the track to play
 * @param method the client-side SDK to play it with
 * @param params SDK-specific arguments, e.g. a Spotify URI or a YouTube video id
 */
public record PlaybackTicket(TrackRef ref, PlaybackMethod method, Map<String, String> params) {

    public PlaybackTicket {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(method, "method");
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
