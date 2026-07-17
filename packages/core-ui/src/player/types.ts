import type { PlaybackMethod, PlaybackTicket, Track } from "../api/types";

/** What the player is doing, independent of which SDK is doing it. */
export type PlayerStatus = "idle" | "loading" | "playing" | "paused" | "error";

export interface PlayerState {
  readonly status: PlayerStatus;
  readonly track: Track | null;
  readonly positionMs: number;
  readonly durationMs: number;
  /** 0..1. Held by the facade so it survives an adapter swap. */
  readonly volume: number;
  readonly error: string | null;
}

/**
 * One streaming service's playback SDK, behind a common interface.
 *
 * Adding a service means writing one of these; the facade and the UI do not
 * change. Mirrors `MusicProvider` on the backend deliberately.
 */
export interface PlayerAdapter {
  /** The ticket method this adapter handles. */
  readonly method: PlaybackMethod;

  /** Loads the SDK. Called once, lazily, the first time this service is played. */
  init(): Promise<void>;

  /** Starts playing. The ticket's params carry the SDK-specific arguments. */
  play(ticket: PlaybackTicket): Promise<void>;

  resume(): Promise<void>;
  pause(): Promise<void>;

  /** Stops and releases anything holding audio. Called when swapping adapters. */
  stop(): Promise<void>;

  seek(positionMs: number): Promise<void>;

  /** @param volume 0..1 */
  setVolume(volume: number): Promise<void>;

  /** Current position, or null if the SDK cannot say. */
  getPositionMs(): Promise<number | null>;

  /** Called when the track reaches its end, so the facade can advance the queue. */
  onEnded(callback: () => void): void;

  /** Releases the SDK entirely. Called on teardown. */
  dispose(): Promise<void>;
}

export type PlayerListener = (state: PlayerState) => void;
