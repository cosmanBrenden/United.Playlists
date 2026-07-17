/**
 * Minimal declarations for the two third-party SDKs.
 *
 * Only what the adapters actually touch. Pulling in @types/spotify-web-playback-sdk
 * and @types/youtube would add two dependencies to describe a handful of calls that
 * are already isolated behind PlayerAdapter.
 */

declare namespace Spotify {
  interface PlaybackState {
    readonly paused: boolean;
    readonly position: number;
    readonly duration: number;
  }

  interface Player {
    connect(): Promise<boolean>;
    disconnect(): void;
    getCurrentState(): Promise<PlaybackState | null>;
    pause(): Promise<void>;
    resume(): Promise<void>;
    seek(positionMs: number): Promise<void>;
    setVolume(volume: number): Promise<void>;
    addListener(event: "ready", cb: (arg: { device_id: string }) => void): void;
    addListener(event: "not_ready", cb: (arg: { device_id: string }) => void): void;
    addListener(event: "player_state_changed", cb: (state: PlaybackState | null) => void): void;
    addListener(
      event: "initialization_error" | "authentication_error" | "account_error" | "playback_error",
      cb: (arg: { message: string }) => void,
    ): void;
  }

  interface PlayerConstructorOptions {
    readonly name: string;
    readonly getOAuthToken: (callback: (token: string) => void) => void;
    readonly volume?: number;
  }
}

interface Window {
  Spotify: {
    Player: new (options: Spotify.PlayerConstructorOptions) => Spotify.Player;
  };
  onSpotifyWebPlaybackSDKReady: () => void;
}
