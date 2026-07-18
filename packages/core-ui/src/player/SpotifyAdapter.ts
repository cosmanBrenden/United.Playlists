import type { PlaybackMethod, PlaybackTicket } from "../api/types";
import type { PlayerAdapter } from "./types";

const SDK_URL = "https://sdk.scdn.co/spotify-player.js";

/**
 * Playback via the Spotify Web Playback SDK.
 *
 * Three things about this are non-negotiable and worth stating plainly, because
 * they are the difference between this working and silently failing for a user:
 *
 * 1. The user must have Spotify Premium. Free accounts authenticate and can browse
 *    their playlists, but the SDK refuses to play for them.
 * 2. The page must have a Widevine CDM. Stock Electron ships without one, so the
 *    desktop build has to use Castlabs' Electron for Content Security. In plain
 *    Electron this adapter fails at `init`, which is why the failure is reported
 *    with an explanation rather than a bare error.
 * 3. Audio never touches our backend. The SDK streams it directly from Spotify
 *    under the user's own account, which is what makes this legal.
 */
export class SpotifyAdapter implements PlayerAdapter {
  readonly method: PlaybackMethod = "SPOTIFY_WEB_SDK";

  readonly #getAccessToken: () => Promise<string>;
  #player: Spotify.Player | null = null;
  #deviceId: string | null = null;
  #onEndedCallback?: () => void;
  #wasPlaying = false;

  /**
   * @param getAccessToken supplies a fresh Spotify token. Called again whenever the
   *   SDK asks, because the SDK outlives any single token.
   */
  constructor(getAccessToken: () => Promise<string>) {
    this.#getAccessToken = getAccessToken;
  }

  async init(): Promise<void> {
    await this.#loadSdkScript();

    const player = new window.Spotify.Player({
      name: "UnitedPlaylists",
      getOAuthToken: (callback) => {
        // The SDK calls this whenever it needs a token, including after expiry, so
        // it must fetch a current one rather than close over a stale one.
        void this.#getAccessToken().then(callback);
      },
      volume: 1,
    });

    player.addListener("player_state_changed", (state) => {
      if (!state) {
        return;
      }
      // The SDK has no "ended" event. A track that just became paused at position 0
      // having previously been playing is how the end of a track presents itself.
      const ended = state.paused && state.position === 0 && this.#wasPlaying;
      this.#wasPlaying = !state.paused;
      if (ended) {
        this.#onEndedCallback?.();
      }
    });

    const ready = new Promise<string>((resolve, reject) => {
      player.addListener("ready", ({ device_id }) => resolve(device_id));
      player.addListener("initialization_error", ({ message }) =>
        reject(new Error(`Spotify SDK could not start: ${message}. ` +
          "This usually means the app is running without Widevine support.")));
      player.addListener("authentication_error", ({ message }) =>
        reject(new Error(
          `Spotify rejected the token: ${message}. ` +
            // Refreshing will not help: a refresh preserves whatever scopes the
            // original grant had, so a token issued before the app asked for a scope
            // never gains it. Only a fresh sign-in does.
            "If this mentions scopes, disconnect Spotify in Services and connect again — " +
            "the permissions on an existing sign-in cannot be upgraded by refreshing.",
        )));
      player.addListener("account_error", () =>
        reject(new Error("Spotify playback requires a Premium account.")));
    });

    const connected = await player.connect();
    if (!connected) {
      throw new Error("Could not connect to Spotify playback.");
    }
    this.#deviceId = await ready;
    this.#player = player;
  }

  async play(ticket: PlaybackTicket): Promise<void> {
    const uri = ticket.params.uri;
    if (!uri) {
      throw new Error("Spotify playback ticket carried no track URI");
    }
    if (!this.#deviceId) {
      throw new Error("Spotify player is not ready");
    }
    // Starting playback is a Web API call against this SDK's device id; the SDK
    // itself has no "play this URI" method.
    const token = await this.#getAccessToken();
    const response = await fetch(
      `https://api.spotify.com/v1/me/player/play?device_id=${encodeURIComponent(this.#deviceId)}`,
      {
        method: "PUT",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ uris: [uri] }),
      },
    );
    if (!response.ok) {
      throw new Error(
        response.status === 403
          ? "Spotify refused playback. A Premium subscription is required."
          : `Spotify playback failed with HTTP ${response.status}`,
      );
    }
    this.#wasPlaying = true;
  }

  async resume(): Promise<void> {
    await this.#player?.resume();
  }

  async pause(): Promise<void> {
    await this.#player?.pause();
  }

  async stop(): Promise<void> {
    // The SDK offers no stop, and pause is what actually releases the audio.
    await this.#player?.pause();
    this.#wasPlaying = false;
  }

  async seek(positionMs: number): Promise<void> {
    await this.#player?.seek(positionMs);
  }

  async setVolume(volume: number): Promise<void> {
    await this.#player?.setVolume(volume);
  }

  async getPositionMs(): Promise<number | null> {
    const state = await this.#player?.getCurrentState();
    return state?.position ?? null;
  }

  async getBufferedMs(): Promise<number | null> {
    // The Web Playback SDK streams DRM audio and exposes no buffer window. Returning
    // null tells the UI to omit the "loaded ahead" portion of the bar rather than
    // invent one — Spotify's own player never shows buffering either.
    return null;
  }

  async getDurationMs(): Promise<number | null> {
    const state = await this.#player?.getCurrentState();
    const duration = state?.duration;
    return duration !== undefined && duration > 0 ? duration : null;
  }

  onEnded(callback: () => void): void {
    this.#onEndedCallback = callback;
  }

  async dispose(): Promise<void> {
    this.#player?.disconnect();
    this.#player = null;
    this.#deviceId = null;
  }

  /** Injects the SDK script and waits for its global ready callback. */
  async #loadSdkScript(): Promise<void> {
    if (window.Spotify) {
      return;
    }
    await new Promise<void>((resolve, reject) => {
      // The SDK calls this global as soon as it loads. It must exist first.
      window.onSpotifyWebPlaybackSDKReady = () => resolve();

      const script = document.createElement("script");
      script.src = SDK_URL;
      script.async = true;
      script.onerror = () =>
        reject(new Error("Could not load the Spotify player. Check your connection."));
      document.head.appendChild(script);
    });
  }
}
