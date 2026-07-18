import type { PlaybackMethod, PlaybackTicket, Track } from "../api/types";

/** What the player is doing, independent of which SDK is doing it. */
export type PlayerStatus = "idle" | "loading" | "playing" | "paused" | "error";

export interface PlayerState {
  readonly status: PlayerStatus;
  readonly track: Track | null;
  readonly positionMs: number;
  readonly durationMs: number;
  /**
   * How much of the current track is buffered ahead, in ms, or null when the SDK
   * cannot report it (Spotify's SDK does not expose buffering). Null means the UI
   * hides the buffered bar rather than guessing.
   */
  readonly bufferedMs: number | null;
  /** 0..1. Held by the facade so it survives an adapter swap. */
  readonly volume: number;
  readonly error: string | null;
  /**
   * The play queue and the index of the current track within it. Held in state so
   * the queue panel re-renders whenever it changes, the same way the transport does.
   */
  readonly queue: readonly Track[];
  readonly queueIndex: number;
  /** Whether auto-advance follows a shuffled order. */
  readonly shuffle: boolean;
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

  /**
   * Optionally warms up the track that will play next, so the swap at track's end is
   * instant instead of stalling on a network fetch. Implementing it is a courtesy,
   * not a contract: an adapter that cannot pre-load (Spotify's SDK plays only what it
   * is told to play now) simply omits it. Must never throw — a failed pre-load just
   * means the track loads normally when it actually plays.
   */
  prepare?(ticket: PlaybackTicket): Promise<void>;

  resume(): Promise<void>;
  pause(): Promise<void>;

  /** Stops and releases anything holding audio. Called when swapping adapters. */
  stop(): Promise<void>;

  seek(positionMs: number): Promise<void>;

  /** @param volume 0..1 */
  setVolume(volume: number): Promise<void>;

  /** Current position, or null if the SDK cannot say. */
  getPositionMs(): Promise<number | null>;

  /**
   * How many ms from the start of the track are buffered, or null if the SDK will
   * not say. Drives the "loaded ahead" portion of the progress bar.
   */
  getBufferedMs(): Promise<number | null>;

  /**
   * The track's true duration in ms once known, or null to fall back to the
   * metadata estimate. A scraped stream often reports a duration that differs by a
   * second or two from the catalog value, and seeking needs the real one.
   */
  getDurationMs(): Promise<number | null>;

  /** Called when the track reaches its end, so the facade can advance the queue. */
  onEnded(callback: () => void): void;

  /** Releases the SDK entirely. Called on teardown. */
  dispose(): Promise<void>;
}

export type PlayerListener = (state: PlayerState) => void;
