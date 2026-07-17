package dev.unitedplaylists.provider;

/**
 * How the client is expected to play a track.
 *
 * <p>Every method here runs in the client. None of them route audio through this
 * backend, and that is not an implementation shortcut: no supported service
 * licenses server-side redistribution of audio, so a backend that proxied the
 * stream would be infringing. The backend's job stops at telling the client what
 * to ask its SDK for.
 */
public enum PlaybackMethod {

    /**
     * Spotify Web Playback SDK. Needs a Premium account and a Widevine-capable
     * browser context; in Electron that means the Castlabs build.
     */
    SPOTIFY_WEB_SDK,

    /** YouTube IFrame player embed. */
    YOUTUBE_IFRAME,

    /** Apple MusicKit JS. Needs an Apple Music subscription. */
    APPLE_MUSICKIT_JS,

    /**
     * A direct audio stream URL, played by a plain HTML5 {@code <audio>} element.
     *
     * <p>Used for the scraper-backed services (YouTube via NewPipe, SoundCloud), where
     * the backend resolves a real stream URL rather than delegating to a vendor SDK.
     * The URL is time-limited and IP-bound, so the ticket carrying it is resolved on
     * demand and never cached.
     */
    DIRECT_AUDIO
}
