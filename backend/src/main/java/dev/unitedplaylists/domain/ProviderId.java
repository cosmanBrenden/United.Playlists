package dev.unitedplaylists.domain;

/**
 * Identifies a music streaming service.
 *
 * <p>Adding a service means adding a constant here and contributing a
 * {@code MusicProvider} bean; nothing else in the core needs to change.
 */
public enum ProviderId {
    SPOTIFY,
    YOUTUBE,
    APPLE_MUSIC,
    SOUNDCLOUD
}
