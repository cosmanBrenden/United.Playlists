package dev.unitedplaylists.domain;

import java.util.Objects;

/**
 * Coordinates of a track on a specific service.
 *
 * <p>This is the atom the whole app is built on: a playlist entry stores a
 * {@code TrackRef} rather than a copy of the audio or a service-agnostic "song",
 * because the same recording on two services is two different playable things
 * with different ids, licences, and availability.
 *
 * @param provider        the service the track lives on
 * @param providerTrackId the service's own id (Spotify track id, YouTube video id, ...)
 */
public record TrackRef(ProviderId provider, String providerTrackId) {

    public TrackRef {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(providerTrackId, "providerTrackId");
        if (providerTrackId.isBlank()) {
            throw new IllegalArgumentException("providerTrackId must not be blank");
        }
    }

    /** Stable string form used as a persistence key and in API payloads. */
    public String toKey() {
        return provider.name() + ":" + providerTrackId;
    }

    public static TrackRef fromKey(String key) {
        Objects.requireNonNull(key, "key");
        int split = key.indexOf(':');
        if (split <= 0 || split == key.length() - 1) {
            throw new IllegalArgumentException("Malformed track key: " + key);
        }
        return new TrackRef(
                ProviderId.valueOf(key.substring(0, split)),
                key.substring(split + 1));
    }
}
