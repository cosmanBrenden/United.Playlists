import type { ProviderId } from "./api/types";

/**
 * The surface the Electron preload exposes to the page.
 *
 * Deliberately tiny. The renderer runs third-party SDK code from Spotify and
 * YouTube, so anything reachable from here is reachable by them; this exposes four
 * specific operations rather than any general capability.
 */
export interface UnitedPlaylistsBridge {
  /** The local backend's origin and shared secret, known only at runtime. */
  getBackendInfo(): Promise<{ baseUrl: string; token: string }>;

  /** A current Spotify access token, for the Web Playback SDK. */
  getSpotifyAccessToken(): Promise<string>;

  /** Opens the sign-in page in the user's browser and waits for the callback. */
  authorize(provider: ProviderId, url: string): Promise<{ code: string; state: string }>;

  /** Opens an https link in the user's browser. Other schemes are refused. */
  openExternal(url: string): Promise<void>;

  readonly platform: string;
}

declare global {
  interface Window {
    readonly unitedPlaylists?: UnitedPlaylistsBridge;
  }
}
